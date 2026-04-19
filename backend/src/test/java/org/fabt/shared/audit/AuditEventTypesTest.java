package org.fabt.shared.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract-pinning test for {@link AuditEventTypes}. Locks every audit event
 * type string value against accidental refactor.
 *
 * <p><b>Why this test exists (Riley Cho's lens):</b> the constants in
 * {@link AuditEventTypes} are referenced from production code (v0.34.0
 * bed-hold-integrity + coc-admin-escalation Sessions 3-4) AND from external
 * systems that query the {@code audit_events} table by {@code action} value
 * (e.g. compliance report scripts, court-subpoena response queries, Grafana
 * audit dashboards). If a future refactor "shortens" one of these constants —
 * for example, renaming {@code BED_HOLDS_RECONCILED = "BED_HOLDS_RECONCILED"}
 * to {@code BED_HOLDS_RECONCILED = "BEDS_RECONCILED"} — every existing audit
 * query breaks silently. The constants would still be referenced by name in
 * Java code, but the stored database values would no longer match historical
 * rows.</p>
 *
 * <p>This test is the guardrail. If anyone changes a string value, this test
 * fails immediately and the offender is forced to either (a) preserve the
 * original value, or (b) write a data-migration script that updates historical
 * audit_events rows AND any external systems that consume the values.</p>
 *
 * <p><b>Live production evidence</b>: the v0.34.0 deploy wrote 3 audit rows
 * to Oracle with {@code action='BED_HOLDS_RECONCILED'} and
 * {@code correction_source='V45_backfill'}. A rename of this constant without
 * a data migration would orphan those 3 rows from any audit query filtering
 * by constant name.</p>
 *
 * <p>Original scope: Session 1 of coc-admin-escalation, after the tech-lead
 * round-table review of {@link AuditEventTypes}. Extended in v0.34.0 to cover
 * {@link AuditEventTypes#BED_HOLDS_RECONCILED}.</p>
 */
@DisplayName("AuditEventTypes — string value contract pinning")
class AuditEventTypesTest {

    // ---- bed-hold-integrity (v0.34.0) ----

    @Test
    @DisplayName("BED_HOLDS_RECONCILED has stable value")
    void bedHoldsReconciled() {
        assertThat(AuditEventTypes.BED_HOLDS_RECONCILED)
                .as("BED_HOLDS_RECONCILED audit action constant must be defined")
                .isNotNull()
                .isNotBlank()
                .isEqualTo("BED_HOLDS_RECONCILED");
    }

    // ---- coc-admin-escalation: DV referral admin actions ----

    @Test
    @DisplayName("DV_REFERRAL_REQUESTED has stable value")
    void dvReferralRequested() {
        assertThat(AuditEventTypes.DV_REFERRAL_REQUESTED).isEqualTo("DV_REFERRAL_REQUESTED");
    }

    @Test
    @DisplayName("DV_REFERRAL_CLAIMED has stable value")
    void dvReferralClaimed() {
        assertThat(AuditEventTypes.DV_REFERRAL_CLAIMED).isEqualTo("DV_REFERRAL_CLAIMED");
    }

    @Test
    @DisplayName("DV_REFERRAL_RELEASED has stable value")
    void dvReferralReleased() {
        assertThat(AuditEventTypes.DV_REFERRAL_RELEASED).isEqualTo("DV_REFERRAL_RELEASED");
    }

    @Test
    @DisplayName("DV_REFERRAL_REASSIGNED has stable value")
    void dvReferralReassigned() {
        assertThat(AuditEventTypes.DV_REFERRAL_REASSIGNED).isEqualTo("DV_REFERRAL_REASSIGNED");
    }

    @Test
    @DisplayName("DV_REFERRAL_ADMIN_ACCEPTED has stable value (distinct from coordinator action)")
    void dvReferralAdminAccepted() {
        assertThat(AuditEventTypes.DV_REFERRAL_ADMIN_ACCEPTED).isEqualTo("DV_REFERRAL_ADMIN_ACCEPTED");
        // Defense-in-depth: must NOT collide with the existing coordinator action type.
        assertThat(AuditEventTypes.DV_REFERRAL_ADMIN_ACCEPTED).isNotEqualTo("DV_REFERRAL_ACCEPTED");
    }

    @Test
    @DisplayName("DV_REFERRAL_ADMIN_REJECTED has stable value (distinct from coordinator action)")
    void dvReferralAdminRejected() {
        assertThat(AuditEventTypes.DV_REFERRAL_ADMIN_REJECTED).isEqualTo("DV_REFERRAL_ADMIN_REJECTED");
        assertThat(AuditEventTypes.DV_REFERRAL_ADMIN_REJECTED).isNotEqualTo("DV_REFERRAL_REJECTED");
    }

    @Test
    @DisplayName("ESCALATION_POLICY_UPDATED has stable value")
    void escalationPolicyUpdated() {
        assertThat(AuditEventTypes.ESCALATION_POLICY_UPDATED).isEqualTo("ESCALATION_POLICY_UPDATED");
    }

    // ---- multi-tenant-production-readiness Phase C (cache isolation) ----

    @Test
    @DisplayName("TENANT_CACHE_INVALIDATED has stable value")
    void tenantCacheInvalidated() {
        assertThat(AuditEventTypes.TENANT_CACHE_INVALIDATED)
                .isNotNull()
                .isNotBlank()
                .isEqualTo("TENANT_CACHE_INVALIDATED");
    }

    @Test
    @DisplayName("CROSS_TENANT_CACHE_READ has stable value (security-evidence audit)")
    void crossTenantCacheRead() {
        assertThat(AuditEventTypes.CROSS_TENANT_CACHE_READ)
                .isNotNull()
                .isNotBlank()
                .isEqualTo("CROSS_TENANT_CACHE_READ");
    }

    @Test
    @DisplayName("MALFORMED_CACHE_ENTRY has stable value")
    void malformedCacheEntry() {
        assertThat(AuditEventTypes.MALFORMED_CACHE_ENTRY)
                .isNotNull()
                .isNotBlank()
                .isEqualTo("MALFORMED_CACHE_ENTRY");
    }

    @Test
    @DisplayName("CROSS_TENANT_POLICY_READ has stable value (security-evidence audit — Phase C task 4.4)")
    void crossTenantPolicyRead() {
        assertThat(AuditEventTypes.CROSS_TENANT_POLICY_READ)
                .isNotNull()
                .isNotBlank()
                .isEqualTo("CROSS_TENANT_POLICY_READ");
    }
}
