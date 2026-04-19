package org.fabt.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Phase C task 4.4 ArchUnit guard for
 * {@code EscalationPolicyService.findByIdForBatch(UUID)}.
 *
 * <p>Per design-c D-C-2 and spec {@code escalation-policy-service-cache-split}:
 * {@code findByIdForBatch} is {@code @TenantUnscoped} by design because the
 * escalation batch job resolves policies across tenants in a single pass
 * with no {@code TenantContext} bound. That method MUST be reserved for the
 * scheduled batch job — request paths that need a policy by id MUST use
 * {@code findByTenantAndId(UUID, UUID)} which enforces on-read tenant
 * verification and emits {@code CROSS_TENANT_POLICY_READ} audit rows on
 * reject.</p>
 *
 * <p>The rule restricts callers by <b>package</b>: only classes in
 * {@code org.fabt.referral.batch..} may call {@code findByIdForBatch}. The
 * Phase C spec's original phrasing was "callable only from {@code @Scheduled}
 * methods", but Spring Batch chains {@code @Scheduled} → Job → Step → Tasklet
 * and the actual invocation happens inside a helper method in
 * {@code ReferralEscalationJobConfig}, not directly on the {@code @Scheduled}
 * method. Static call-graph reachability across that chain is not something
 * ArchUnit does natively and a custom {@code ArchCondition} for it would be
 * disproportionate work for a single-digit rule. Package-based restriction is
 * a superset that achieves the same intent: a future non-batch class ever
 * landing in {@code org.fabt.referral.batch..} would need warroom sign-off
 * at code-review (same gate). (D-4.4-2 warroom resolution.)</p>
 *
 * <p>Today's only legitimate caller is
 * {@code org.fabt.referral.batch.ReferralEscalationJobConfig} (line ~260).</p>
 */
@DisplayName("ArchUnit Phase C 4.4 — findByIdForBatch callable only from org.fabt.referral.batch")
class EscalationPolicyBatchOnlyArchitectureTest {

    private static final String BATCH_PACKAGE = "org.fabt.referral.batch..";

    @Test
    @DisplayName("findByIdForBatch callable only from org.fabt.referral.batch..")
    void findByIdForBatch_isBatchOnly() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.fabt..");

        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage(BATCH_PACKAGE)
                .should().callMethod(
                        "org.fabt.notification.service.EscalationPolicyService",
                        "findByIdForBatch",
                        "java.util.UUID")
                .because("findByIdForBatch is @TenantUnscoped — reserved for the "
                        + "scheduled escalation batch job (ReferralEscalationJobConfig). "
                        + "Request paths MUST use findByTenantAndId(UUID, UUID) which "
                        + "enforces tenant scoping on the returned policy and emits "
                        + "CROSS_TENANT_POLICY_READ audit rows on cross-tenant reach. "
                        + "Package-based restriction approximates the spec's "
                        + "@Scheduled-caller intent because Spring Batch's chain is "
                        + "not statically reachable from ArchUnit. Design-c D-C-2.");

        rule.check(classes);
    }
}
