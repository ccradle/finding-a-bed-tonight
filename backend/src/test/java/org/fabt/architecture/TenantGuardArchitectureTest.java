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
 * <h2>Intended rule (Phase 3)</h2>
 *
 * <p>Using ArchUnit's fluent API:
 * <pre>{@code
 * @AnalyzeClasses(packages = "org.fabt", importOptions = ImportOption.DoNotIncludeTests.class)
 * public class TenantGuardArchitectureTest {
 *
 *   @ArchTest
 *   static final ArchRule no_bare_findById_on_tenant_repos =
 *       noClasses()
 *           .that().resideInAnyPackage("org.fabt..service..", "org.fabt..api..")
 *           .should().callMethod(TenantOwnedRepository.class, "findById", UUID.class)
 *           .orShould().callMethod(TenantOwnedRepository.class, "existsById", UUID.class)
 *           .andShould().notBeAnnotatedWith(TenantUnscoped.class)
 *           .as("Service and controller methods MUST route through findByIdAndTenantId or carry @TenantUnscoped(value=\"...\") with a non-empty justification. See openspec/changes/cross-tenant-isolation-audit (design D2).");
 *
 *   // Secondary rule: @TenantUnscoped value() MUST be non-empty.
 *   // Secondary rule: findByIdForBatch callers MUST reside in org.fabt.referral.batch..
 *   // Secondary rule: *Internal subscription methods MUST be called only from WebhookDeliveryService.
 * }
 * }</pre>
 *
 * <h2>Whitelist of accepted methods (non-bare)</h2>
 *
 * <p>The Phase 3 rule recognizes these as tenant-safe and exempts them:
 * <ul>
 *   <li>{@code *Repository.findByIdAndTenantId(UUID, UUID)}</li>
 *   <li>{@code *Repository.findByTenantIdAndId(UUID, UUID)} (existing convention)</li>
 *   <li>Repository methods returning {@code Optional} that are filtered by {@code tenantId} downstream in the same method (detected by bytecode analysis)</li>
 * </ul>
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
