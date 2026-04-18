package org.fabt.architecture;

import java.util.Set;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.DisplayName;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * Build-failing architecture rule enforcing the Phase B invariant from
 * {@code feedback_transactional_rls_scoped_value_ordering.md}:
 * {@code @Transactional} methods MUST NOT call
 * {@link TenantContext#runWithContext}.
 *
 * <h2>Why</h2>
 * The {@code RlsAwareDataSource} wraps Hikari; on {@code getConnection()} it
 * executes {@code SET ROLE fabt_app} + three {@code set_config(...)} calls
 * sourced from the <em>current</em> {@link TenantContext}. Spring's
 * {@code @Transactional} boundary borrows the connection at method entry
 * and reuses it for every subsequent query in that transaction. If the
 * method body later calls {@code runWithContext(otherTenant, ...)}, the
 * {@link java.lang.ScopedValue} binding changes but the already-borrowed
 * connection's session GUCs do not — RLS policies read
 * {@code current_setting('app.tenant_id', true)} and see the original
 * (possibly null) value, producing quiet cross-tenant visibility or
 * {@code row violates row-level security policy} errors that look like
 * a Phase B regression.
 *
 * <h2>Correct pattern</h2>
 * <pre>{@code
 * // GOOD: runWithContext wraps @Transactional.
 * TenantContext.runWithContext(tenantId, dvAccess, () -> {
 *     transactionalService.doWork();   // @Transactional acquires conn now
 * });                                  // → SET ROLE + set_config reflect tenantId
 *
 * // BAD: @Transactional acquires connection BEFORE runWithContext binds.
 * @Transactional
 * public void doWork() {
 *     TenantContext.runWithContext(tenantId, dvAccess, () -> ...);
 * }
 * }</pre>
 *
 * <h2>Scope</h2>
 * The rule scans every non-test class in {@code org.fabt} and fires when a
 * method (or a method on a class) annotated with Spring's
 * {@code @Transactional} contains a call to
 * {@code TenantContext.runWithContext} anywhere in its body (including
 * nested lambdas — ArchUnit's {@code getMethodCallsFromSelf} flattens
 * synthetic lambda bodies into the enclosing method).
 *
 * <h2>Allowlist</h2>
 * Two self-invocation patterns from before Phase B are known and
 * intentionally preserved (they run on scheduler threads with no inherited
 * tenant, then fan out to worker threads that do NOT inherit the
 * transaction):
 * <ul>
 *   <li>{@code HmisPushService.processOutbox} — submits to a
 *       {@code VirtualThreadPerTaskExecutor}; the {@code runWithContext}
 *       call executes on a worker thread that is outside the outer
 *       {@code @Transactional} boundary. The outer transaction only
 *       performs the {@code findPending()} read.</li>
 *   <li>{@code ReservationService.expireReservation} — flagged
 *       {@code @TenantUnscoped}; the outer transaction performs the
 *       {@code UPDATE reservation SET status=EXPIRED} under the
 *       reservation-table RLS (not Phase B), and the inner
 *       {@code runWithContext} only wraps event publication which goes
 *       through the application event bus (new connection per persist).</li>
 * </ul>
 *
 * <p>Any new entry added to {@link #ALLOWLIST} requires a warroom review —
 * see design D-B-RLS-B11.
 *
 * @see feedback_transactional_rls_scoped_value_ordering
 */
@AnalyzeClasses(packages = "org.fabt", importOptions = ImportOption.DoNotIncludeTests.class)
@DisplayName("Phase B B11: @Transactional methods must not call TenantContext.runWithContext")
class TenantContextTransactionalRuleTest {

    /**
     * Fully qualified class-name-dot-method-name entries intentionally
     * exempted from the B11 rule. Every entry ships with an inline
     * justification and a warroom pointer. Adding entries requires the same
     * review gate as adding an {@code @TenantUnscoped} annotation.
     */
    private static final Set<String> ALLOWLIST = Set.of(
            // Outer @Transactional performs only findPending() read; the
            // runWithContext call runs inside executor.submit(() -> ...)
            // on a virtual worker thread that does NOT inherit the outer
            // transaction. ArchUnit sees the lexical call but it executes
            // on a fresh connection. See design D-B-RLS-B11.
            "HmisPushService.processOutbox",
            // @TenantUnscoped system expirer. Outer @Transactional borrows
            // connection with null tenant; the inner runWithContext wraps
            // event publication via ApplicationEventPublisher (each listener
            // runs on its own transaction boundary and re-borrows a
            // connection with the bound tenant). The reservation UPDATE at
            // the top of the method is on a table with shelter-based RLS
            // (not Phase B tenant-RLS). See design D-B-RLS-B11.
            "ReservationService.expireReservation"
    );

    private static final String TRANSACTIONAL_ANNOTATION =
            "org.springframework.transaction.annotation.Transactional";

    private static final String TENANT_CONTEXT_CLASS = TenantContext.class.getName();

    private static final Set<String> RUN_WITH_CONTEXT_NAMES =
            Set.of("runWithContext", "callWithContext");

    @ArchTest
    static final ArchRule transactional_methods_must_not_invoke_runWithContext =
            methods()
                    .that(new com.tngtech.archunit.base.DescribedPredicate<JavaMethod>(
                            "are @Transactional (method- or class-level)") {
                        @Override
                        public boolean test(JavaMethod method) {
                            if (method.isAnnotatedWith(TRANSACTIONAL_ANNOTATION)) return true;
                            JavaClass owner = method.getOwner();
                            return owner.isAnnotatedWith(TRANSACTIONAL_ANNOTATION);
                        }
                    })
                    .should(notCallRunWithContext())
                    .as("@Transactional methods must NOT call TenantContext.runWithContext. "
                            + "The transaction's connection is borrowed at method entry with "
                            + "RLS session vars set from the OUTER TenantContext; changing "
                            + "ScopedValue mid-transaction does NOT update the connection's "
                            + "app.tenant_id / app.dv_access GUCs. Invert the nesting: wrap "
                            + "the @Transactional call site in runWithContext from the caller. "
                            + "See feedback_transactional_rls_scoped_value_ordering.md and "
                            + "design D-B-RLS-B11.");

    private static ArchCondition<JavaMethod> notCallRunWithContext() {
        return new ArchCondition<>("not call TenantContext.runWithContext / callWithContext") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                String site = method.getOwner().getSimpleName() + "." + method.getName();
                if (ALLOWLIST.contains(site)) return;

                for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
                    if (!RUN_WITH_CONTEXT_NAMES.contains(call.getName())) continue;
                    if (!TENANT_CONTEXT_CLASS.equals(call.getTargetOwner().getFullName())) continue;
                    events.add(SimpleConditionEvent.violated(method,
                            method.getFullName() + " is @Transactional and calls "
                                    + call.getName() + "() at " + call.getSourceCodeLocation()
                                    + " — either remove @Transactional, move runWithContext to "
                                    + "the caller, or add to ALLOWLIST with warroom review."));
                }
            }
        };
    }
}
