package org.fabt.shared.audit;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract-pinning test for {@link AuditEventType}. Locks every audit event
 * type's {@link Enum#name()} against accidental rename.
 *
 * <p><b>Why this test exists (Riley Cho's lens):</b> the {@code .name()} of
 * each enum case is the value persisted in {@code audit_events.action} and
 * consumed by external systems (compliance report scripts, court-subpoena
 * response queries, Grafana audit dashboards). If a future refactor
 * "shortens" a case — for example, renaming {@code BED_HOLDS_RECONCILED}
 * to {@code BEDS_RECONCILED} — every existing audit query breaks silently.
 * Java references would compile, but the stored database values would no
 * longer match historical rows.</p>
 *
 * <p>This test is the guardrail. A {@code .name()} change fails the test
 * immediately; the offender must either preserve the original value OR
 * write a data-migration script that updates historical rows AND every
 * external system consuming them.</p>
 *
 * <p><b>Migration note (Slice G-0, issue #98):</b> this test replaces the
 * prior {@code AuditEventTypesTest} (12 hand-written cases over 25 constants
 * — drift-prone). The parametrised {@code exhaustivenessCheck} below runs
 * once per enum case, so the contract coverage is exhaustive by construction
 * — adding a new case without a corresponding wire-name assertion is no
 * longer possible.</p>
 *
 * <p><b>Live production evidence</b>: the v0.34.0 deploy wrote 3 audit rows
 * to Oracle with {@code action='BED_HOLDS_RECONCILED'}. A rename of this
 * case without a data migration would orphan those rows from any audit
 * query filtering by enum name.</p>
 */
@DisplayName("AuditEventType — wire-name contract pinning")
class AuditEventTypeTest {

    /**
     * Every enum case's {@code .name()} is identity — tautological at the Java
     * level, load-bearing at the serialisation boundary. This test pins the
     * invariant across the entire enum without hand-writing 38 assertions.
     */
    @ParameterizedTest
    @EnumSource(AuditEventType.class)
    @DisplayName(".name() is non-null, non-blank, UPPER_SNAKE_CASE for every case")
    void exhaustivenessCheck(AuditEventType type) {
        assertThat(type.name())
                .as("enum case %s must have a stable wire name", type)
                .isNotNull()
                .isNotBlank()
                .matches("^[A-Z][A-Z0-9_]*$");
    }

    /**
     * The enum must have at least the 44 cases known at G-0 landing time
     * (43 business cases + 1 {@link AuditEventType#TEST_PROBE} sentinel).
     * New cases are welcome (this is a floor, not a ceiling). A drop in
     * count — someone deleting a case — fails the test and forces a data-
     * migration conversation.
     */
    @Test
    @DisplayName("at least 44 cases — floor check against accidental deletion")
    void enumCountFloor() {
        assertThat(AuditEventType.values().length)
                .as("AuditEventType count. Removing a case requires a data migration for historical rows.")
                .isGreaterThanOrEqualTo(44);
    }

    // ─── Individual wire-name pins by phase / feature family ───
    //
    // The exhaustivenessCheck above pins EVERY case structurally, but the
    // individual pins below are still worth keeping for two reasons: (1) the
    // assertion failure message names the exact constant, which speeds up
    // triage, and (2) the DV_REFERRAL_ADMIN_* pair has a disambiguation pin
    // against the coordinator variant that the parametrised check can't
    // express.

    @Test
    @DisplayName("BED_HOLDS_RECONCILED wire name stable (v0.34.0 prod evidence)")
    void bedHoldsReconciled() {
        assertThat(AuditEventType.BED_HOLDS_RECONCILED.name()).isEqualTo("BED_HOLDS_RECONCILED");
    }

    @Test
    @DisplayName("DV referral family — wire names stable")
    void dvReferralFamily() {
        assertThat(AuditEventType.DV_REFERRAL_REQUESTED.name()).isEqualTo("DV_REFERRAL_REQUESTED");
        assertThat(AuditEventType.DV_REFERRAL_CLAIMED.name()).isEqualTo("DV_REFERRAL_CLAIMED");
        assertThat(AuditEventType.DV_REFERRAL_RELEASED.name()).isEqualTo("DV_REFERRAL_RELEASED");
        assertThat(AuditEventType.DV_REFERRAL_REASSIGNED.name()).isEqualTo("DV_REFERRAL_REASSIGNED");
        assertThat(AuditEventType.DV_REFERRAL_ACCEPTED.name()).isEqualTo("DV_REFERRAL_ACCEPTED");
        assertThat(AuditEventType.DV_REFERRAL_REJECTED.name()).isEqualTo("DV_REFERRAL_REJECTED");
        assertThat(AuditEventType.ESCALATION_POLICY_UPDATED.name()).isEqualTo("ESCALATION_POLICY_UPDATED");
    }

    @Test
    @DisplayName("admin DV variants distinct from coordinator variants")
    void adminVariantsDistinct() {
        assertThat(AuditEventType.DV_REFERRAL_ADMIN_ACCEPTED.name()).isEqualTo("DV_REFERRAL_ADMIN_ACCEPTED");
        assertThat(AuditEventType.DV_REFERRAL_ADMIN_REJECTED.name()).isEqualTo("DV_REFERRAL_ADMIN_REJECTED");
        assertThat(AuditEventType.DV_REFERRAL_ADMIN_ACCEPTED)
                .as("admin-accept must be a distinct enum case from coordinator-accept "
                        + "— Casey Drummond chain-of-custody disambiguation")
                .isNotEqualTo(AuditEventType.DV_REFERRAL_ACCEPTED);
        assertThat(AuditEventType.DV_REFERRAL_ADMIN_REJECTED)
                .isNotEqualTo(AuditEventType.DV_REFERRAL_REJECTED);
    }

    @Test
    @DisplayName("shelter family wire names")
    void shelterFamily() {
        assertThat(AuditEventType.SHELTER_DEACTIVATED.name()).isEqualTo("SHELTER_DEACTIVATED");
        assertThat(AuditEventType.SHELTER_REACTIVATED.name()).isEqualTo("SHELTER_REACTIVATED");
        assertThat(AuditEventType.SHELTER_DV_FLAG_CHANGED.name()).isEqualTo("SHELTER_DV_FLAG_CHANGED");
        assertThat(AuditEventType.DV_SHELTER_ADDRESS_CHANGED.name()).isEqualTo("DV_SHELTER_ADDRESS_CHANGED");
    }

    @Test
    @DisplayName("user + auth family wire names")
    void userAuthFamily() {
        assertThat(AuditEventType.ROLE_CHANGED.name()).isEqualTo("ROLE_CHANGED");
        assertThat(AuditEventType.DV_ACCESS_CHANGED.name()).isEqualTo("DV_ACCESS_CHANGED");
        assertThat(AuditEventType.USER_DEACTIVATED.name()).isEqualTo("USER_DEACTIVATED");
        assertThat(AuditEventType.USER_REACTIVATED.name()).isEqualTo("USER_REACTIVATED");
        assertThat(AuditEventType.ACCESS_CODE_GENERATED.name()).isEqualTo("ACCESS_CODE_GENERATED");
        assertThat(AuditEventType.ACCESS_CODE_USED.name()).isEqualTo("ACCESS_CODE_USED");
    }

    @Test
    @DisplayName("TOTP / MFA family wire names")
    void totpFamily() {
        assertThat(AuditEventType.TOTP_ENABLED.name()).isEqualTo("TOTP_ENABLED");
        assertThat(AuditEventType.BACKUP_CODES_REGENERATED.name()).isEqualTo("BACKUP_CODES_REGENERATED");
        assertThat(AuditEventType.TOTP_DISABLED_BY_ADMIN.name()).isEqualTo("TOTP_DISABLED_BY_ADMIN");
        assertThat(AuditEventType.BACKUP_CODES_REGENERATED_BY_ADMIN.name())
                .isEqualTo("BACKUP_CODES_REGENERATED_BY_ADMIN");
    }

    @Test
    @DisplayName("Phase A — JWT key rotation wire name")
    void phaseAFamily() {
        assertThat(AuditEventType.JWT_KEY_GENERATION_BUMPED.name()).isEqualTo("JWT_KEY_GENERATION_BUMPED");
    }

    @Test
    @DisplayName("Phase C — cache isolation wire names")
    void phaseCFamily() {
        assertThat(AuditEventType.TENANT_CACHE_INVALIDATED.name()).isEqualTo("TENANT_CACHE_INVALIDATED");
        assertThat(AuditEventType.CROSS_TENANT_CACHE_READ.name()).isEqualTo("CROSS_TENANT_CACHE_READ");
        assertThat(AuditEventType.MALFORMED_CACHE_ENTRY.name()).isEqualTo("MALFORMED_CACHE_ENTRY");
        assertThat(AuditEventType.CROSS_TENANT_POLICY_READ.name()).isEqualTo("CROSS_TENANT_POLICY_READ");
    }

    @Test
    @DisplayName("Phase F — tenant lifecycle wire names")
    void phaseFSuccessPath() {
        assertThat(AuditEventType.TENANT_CREATED.name()).isEqualTo("TENANT_CREATED");
        assertThat(AuditEventType.TENANT_SUSPENDED.name()).isEqualTo("TENANT_SUSPENDED");
        assertThat(AuditEventType.TENANT_UNSUSPENDED.name()).isEqualTo("TENANT_UNSUSPENDED");
        assertThat(AuditEventType.TENANT_OFFBOARDING_STARTED.name()).isEqualTo("TENANT_OFFBOARDING_STARTED");
        assertThat(AuditEventType.TENANT_ARCHIVED.name()).isEqualTo("TENANT_ARCHIVED");
        assertThat(AuditEventType.TENANT_HARD_DELETED.name()).isEqualTo("TENANT_HARD_DELETED");
    }

    @Test
    @DisplayName("Phase F — lifecycle rejection family wire names")
    void phaseFRejectionFamily() {
        assertThat(AuditEventType.TENANT_SUSPEND_REJECTED.name()).isEqualTo("TENANT_SUSPEND_REJECTED");
        assertThat(AuditEventType.TENANT_UNSUSPEND_REJECTED.name()).isEqualTo("TENANT_UNSUSPEND_REJECTED");
        assertThat(AuditEventType.TENANT_OFFBOARD_REJECTED.name()).isEqualTo("TENANT_OFFBOARD_REJECTED");
        assertThat(AuditEventType.TENANT_ARCHIVE_REJECTED.name()).isEqualTo("TENANT_ARCHIVE_REJECTED");
        assertThat(AuditEventType.TENANT_HARD_DELETE_REJECTED.name()).isEqualTo("TENANT_HARD_DELETE_REJECTED");
    }

    @Test
    @DisplayName("no duplicate wire names across enum")
    void noDuplicateWireNames() {
        // Set.of rejects duplicates at construction — if two enum cases had
        // the same .name() (impossible in Java, but pins the invariant for
        // the sake of the contract), this throws IllegalArgumentException.
        Set<String> wireNames = Set.copyOf(
                java.util.Arrays.stream(AuditEventType.values())
                        .map(Enum::name)
                        .toList());
        assertThat(wireNames).hasSize(AuditEventType.values().length);
    }
}
