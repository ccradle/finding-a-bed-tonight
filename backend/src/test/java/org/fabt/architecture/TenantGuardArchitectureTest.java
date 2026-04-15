package org.fabt.architecture;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Build-failing architecture rule forbidding bare {@code findById(UUID)} or
 * {@code existsById(UUID)} calls on tenant-owned repositories from classes in
 * {@code org.fabt.*.service} or {@code org.fabt.*.api} packages, unless
 * either (a) the calling method carries a {@code @TenantUnscoped(value = "...")}
 * annotation with a non-empty value, or (b) the call routes through a
 * tenant-scoped repository method such as {@code findByIdAndTenantId}.
 *
 * <h2>Status: SKELETON — activated in Phase 3 of cross-tenant-isolation-audit</h2>
 *
 * <p>This class is a compiling placeholder. Phase 1 (foundations) introduces
 * the file so downstream work can reference it; the rule itself is defined
 * and activated in Phase 3 AFTER Phase 2 lands the 5 VULN-HIGH + 2 VULN-MED
 * fixes from the audit. Activating the rule before the call sites are clean
 * would break the build pre-fix.
 *
 * <h2>Intended rule shape (Phase 3)</h2>
 *
 * <p>The rule requires {@code methods()}-level granularity because the
 * {@code @TenantUnscoped} annotation is carried by the calling method, not
 * by its enclosing class. The likely shape uses a custom
 * {@code ArchCondition<JavaMethod>} that inspects each method's call graph
 * for disallowed repository invocations:
 * <pre>{@code
 * @AnalyzeClasses(packages = "org.fabt", importOptions = ImportOption.DoNotIncludeTests.class)
 * public class TenantGuardArchitectureTest {
 *
 *   @ArchTest
 *   static final ArchRule no_bare_findById_on_tenant_owned_repos =
 *       methods()
 *           .that().areDeclaredInClassesThat().resideInAnyPackage("org.fabt..service..", "org.fabt..api..")
 *           .and().areNotAnnotatedWith(TenantUnscoped.class)
 *           .should(notCallBareFindByIdOnTenantOwnedRepository())
 *           .as("Service and controller methods MUST route through findByIdAndTenantId or carry @TenantUnscoped(\"...\") with a non-empty justification. See openspec/changes/cross-tenant-isolation-audit (design D2).");
 *
 *   // notCallBareFindByIdOnTenantOwnedRepository() is a custom ArchCondition<JavaMethod>
 *   // to be written in Phase 3 (task 3.5). It scans the method's outgoing method calls
 *   // and fails when it sees findById(UUID) or existsById(UUID) on a repository whose
 *   // entity class is tenant-owned (determined by an allowlist or a marker interface).
 * }
 * }</pre>
 *
 * <p>Phase 3 also introduces complementary rules derived from Phase 2's
 * actual refactor — subject to confirmation, these will likely cover:
 * <ul>
 *   <li>Non-empty {@code @TenantUnscoped} value (value length &gt; 0).</li>
 *   <li>Caller-package restrictions on the batch-snapshot and webhook-internal
 *       method renames Phase 2 introduces — final names will come from Phase 2, not this skeleton.</li>
 * </ul>
 *
 * <h2>Whitelist of accepted methods (non-bare)</h2>
 *
 * <p>The Phase 3 rule recognizes these as tenant-safe and exempts them:
 * <ul>
 *   <li>{@code *Repository.findByIdAndTenantId(UUID, UUID)}</li>
 *   <li>{@code *Repository.findByTenantIdAndId(UUID, UUID)} (existing convention in this codebase — see
 *       {@code ShelterRepository.findByTenantIdAndId})</li>
 * </ul>
 *
 * <p>If a legitimate call pattern falls outside both whitelist shapes (e.g. batch
 * callers needing platform-wide visibility), the correct remediation is the
 * {@code @TenantUnscoped("...")} escape hatch — NOT a bytecode-level exemption.
 * ArchUnit operates on the static call graph and cannot reason about dataflow
 * ("this UUID was later compared to TenantContext"), so the rule intentionally
 * declines to guess; the annotation is the explicit author-acknowledged exemption.
 *
 * <h2>Why disabled today</h2>
 *
 * <p>Removing {@code @Disabled} before Phase 2 lands would fail the build on
 * the 5 VULN-HIGH + 2 VULN-MED call sites the audit identified. Phase 3
 * activation (task 3.5) is the intentional cutover point.
 *
 * @see org.fabt.shared.security.TenantUnscoped
 */
@Disabled("cross-tenant-isolation-audit Phase 3: activate via task 3.5 after R1+R2 service fixes land")
class TenantGuardArchitectureTest {

    @Test
    void placeholder_rule_activated_in_phase_3() {
        // Intentional no-op. Phase 3 (task 3.5) replaces this class-level
        // @Disabled + placeholder method with the real @AnalyzeClasses +
        // @ArchTest fields per the Javadoc above.
    }
}
