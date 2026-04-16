package org.fabt.shared.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a repository method or service-layer SQL site as an intentional
 * exception to the project's tenant-predicate convention. Used ONLY when
 * a SQL query against a tenant-owned table must run without a
 * {@code tenant_id = ?} predicate by design — typically retention purges,
 * platform-wide event-fanout queries, batch reconcilers, or queries
 * filtered by a column that is itself tenant-scoped via FK
 * (e.g., {@code recipient_id} → {@code app_user.tenant_id}).
 *
 * <p>The justification string is required and must be non-empty. It
 * documents <em>why</em> the cross-tenant query is safe: which other
 * predicate keeps it tenant-bounded, which scheduled context is
 * platform-wide by design, etc. The
 * {@code TenantPredicateCoverageTest} in {@code org.fabt.architecture}
 * enforces both the annotation's presence on opt-out methods and the
 * non-emptiness of {@code value}.
 *
 * <p>Distinct from {@link TenantUnscoped} which annotates Java methods
 * for the call-graph rule. {@code @TenantUnscopedQuery} annotates the
 * specific SQL site that opts out of the predicate rule. The two rules
 * are independent — a method may carry one, both, or neither.
 *
 * <p>See the {@code cross-tenant-isolation-audit} OpenSpec change
 * (design decision D15) for the rationale and full contract.
 *
 * <h2>Important caveat</h2>
 *
 * <p>This annotation suppresses a <em>build-time</em> developer-discipline
 * check. It is NOT the integrity gate. The runtime integrity gate is
 * Postgres RLS plus the {@code app.tenant_id} session variable installed
 * by {@code RlsDataSourceConfig} (Phase 4.8 / design D13). A query that
 * legitimately bypasses the static check still flows through the
 * runtime gate.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @Query("DELETE FROM notification WHERE read_at IS NOT NULL AND created_at < :cutoff")
 * @TenantUnscopedQuery("daily retention purge — runs across all tenants")
 * int deleteOldRead(@Param("cutoff") Instant cutoff);
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TenantUnscopedQuery {

    /**
     * Non-empty justification — human-readable explanation of why this
     * query is safe to run against a tenant-owned table without a
     * {@code tenant_id} predicate. Enforced non-empty by
     * {@code TenantPredicateCoverageTest}.
     */
    String value();
}
