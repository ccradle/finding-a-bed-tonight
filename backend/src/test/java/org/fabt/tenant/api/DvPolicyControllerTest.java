package org.fabt.tenant.api;

import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.shared.errors.ErrorCodes;
import org.fabt.shared.web.TenantContext;
import org.fabt.auth.domain.User;
import org.fabt.shelter.fixtures.TestShelterFixture;
import org.fabt.tenant.domain.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link DvPolicyController} covering dv-policy-tenant-flag
 * task §4.8 — the 11 scenarios in the spec under "PATCH dv-policy endpoint",
 * "Enable path", "Disable path", and "Audit on flag change and on rejected
 * disable", plus Marcus's cross-tenant-no-leak assertion and Riley's
 * failed-disable audit field-content asserts.
 *
 * <p>Per {@code feedback_isolated_test_data}: each test uses freshly-uuid'd
 * tenants via {@link TestAuthHelper}. Per {@code feedback_no_guessing}: the
 * controller's runtime behavior is verified, not just compile.
 */
@DisplayName("PATCH /api/v1/admin/tenants/{id}/dv-policy")
class DvPolicyControllerTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JdbcTemplate jdbc;

    private UUID primaryTenantId;
    private UUID secondaryTenantId;
    private HttpHeaders cocAdminHeaders;
    private HttpHeaders cocAdminWithoutDvAccessHeaders;
    private HttpHeaders coordinatorHeaders;
    private HttpHeaders outreachHeaders;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // Primary tenant + 3 user roles. The COC_ADMIN must have dvAccess=true
        // because the controller's disable-path count query reads {@code shelter}
        // through RLS — a dvAccess=false admin would have the DV shelters
        // hidden by RLS and the disable would wrongly succeed. The endpoint
        // therefore requires dvAccess=true (warroom round 2, 2026-05-02).
        Tenant primary = authHelper.setupTestTenant("dvpolicy-" + suffix);
        primaryTenantId = primary.getId();
        User cocAdminWithDv = authHelper.setupUserWithDvAccess(
                "dvpolicy-cocadmin-" + suffix + "@test.fabt.org",
                "DvPolicy CoC Admin",
                new String[]{"COC_ADMIN"});
        cocAdminHeaders = authHelper.headersForUser(cocAdminWithDv);

        // Separate fixture for the dvAccess=false negative-path test
        User cocAdminNoDv = authHelper.createUserInTenant(
                primaryTenantId,
                "dvpolicy-cocadmin-nodv-" + suffix + "@test.fabt.org",
                "DvPolicy CoC Admin (no DV)",
                new String[]{"COC_ADMIN"},
                false);
        cocAdminWithoutDvAccessHeaders = authHelper.headersForUser(cocAdminNoDv);

        authHelper.setupCoordinatorUser();
        authHelper.setupOutreachWorkerUser();
        coordinatorHeaders = authHelper.coordinatorHeaders();
        outreachHeaders = authHelper.outreachWorkerHeaders();

        // Secondary tenant for cross-tenant probe — has DV shelters so
        // we can verify the response does NOT leak the count when the
        // primary tenant's COC_ADMIN probes it.
        Tenant secondary = authHelper.setupSecondaryTenant("dvpolicy-secondary-" + suffix);
        secondaryTenantId = secondary.getId();
        TenantContext.runWithContext(secondaryTenantId, true, () -> {
            TestShelterFixture.insertShelter(jdbc, secondaryTenantId, "Secondary DV", true);
            TestShelterFixture.insertShelter(jdbc, secondaryTenantId, "Secondary DV 2", true);
            TestShelterFixture.insertShelter(jdbc, secondaryTenantId, "Secondary DV 3", true);
        });

        // Both tenants start with dv_policy_enabled cleared so transitions
        // are observable. The bootstrap path may prime the key; normalize.
        clearDvPolicyKey(primaryTenantId);
        clearDvPolicyKey(secondaryTenantId);
    }

    // ----- Enable path ---------------------------------------------------

    @Test
    @DisplayName("COC_ADMIN enables flag on own tenant — 200, persisted, audit emitted")
    void cocAdminEnablesFlag() {
        ResponseEntity<Map<String, Object>> response = patch(primaryTenantId, true, cocAdminHeaders);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("dvPolicyEnabled", true);
        assertThat(readDvPolicyKey(primaryTenantId)).isEqualTo("true");
        assertThat(latestAppliedAuditNewValue(primaryTenantId)).isEqualTo("true");
    }

    @Test
    @DisplayName("Idempotent re-enable when already true — 200, audit still emitted with old=new + value_changed false")
    void idempotentReEnable() {
        // First enable — real flip (false → true), value_changed=true
        patch(primaryTenantId, true, cocAdminHeaders);
        // Second enable — idempotent re-set (true → true), value_changed=false
        ResponseEntity<Map<String, Object>> response = patch(primaryTenantId, true, cocAdminHeaders);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readDvPolicyKey(primaryTenantId)).isEqualTo("true");

        // Warroom round 3 H3: every successful PATCH (real or idempotent)
        // emits an audit row — the controller surfaces operator INTENT in the
        // chain, not just state changes. Two PATCH calls must produce two
        // applied rows, and warroom round 3 M3's value_changed field must
        // distinguish them: first row (real flip) value_changed=true, second
        // (no-op re-confirm) value_changed=false. A regression that silently
        // suppressed the second emit would leak through unless we count.
        long appliedRows = TenantContext.callWithContext(primaryTenantId, true,
                () -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_events "
                                + "WHERE action = 'TENANT_CONFIG_UPDATED' "
                                + "AND details ->> 'config_key' = 'dv_policy_enabled' "
                                + "AND details ->> 'outcome' = 'applied'",
                        Long.class));
        assertThat(appliedRows)
                .as("Two PATCH calls must emit two applied audit rows — intent is the audit signal, not just state delta")
                .isEqualTo(2L);

        long valueChangedTrueRows = TenantContext.callWithContext(primaryTenantId, true,
                () -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_events "
                                + "WHERE action = 'TENANT_CONFIG_UPDATED' "
                                + "AND details ->> 'config_key' = 'dv_policy_enabled' "
                                + "AND details ->> 'outcome' = 'applied' "
                                + "AND (details ->> 'value_changed')::boolean = true",
                        Long.class));
        assertThat(valueChangedTrueRows)
                .as("First PATCH was a real flip — exactly one row should carry value_changed=true")
                .isEqualTo(1L);

        long valueChangedFalseRows = TenantContext.callWithContext(primaryTenantId, true,
                () -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_events "
                                + "WHERE action = 'TENANT_CONFIG_UPDATED' "
                                + "AND details ->> 'config_key' = 'dv_policy_enabled' "
                                + "AND details ->> 'outcome' = 'applied' "
                                + "AND (details ->> 'value_changed')::boolean = false",
                        Long.class));
        assertThat(valueChangedFalseRows)
                .as("Second PATCH was an idempotent re-set — exactly one row should carry value_changed=false")
                .isEqualTo(1L);
    }

    // ----- Disable path --------------------------------------------------

    @Test
    @DisplayName("Disable rejected when active DV shelters exist — 400 + structured code + count + audit row")
    void disableRejectedWhileDvSheltersExist() {
        // Setup: enable flag, create 2 active DV shelters
        patch(primaryTenantId, true, cocAdminHeaders);
        TenantContext.runWithContext(primaryTenantId, true, () -> {
            TestShelterFixture.insertShelter(jdbc, primaryTenantId, "Primary DV A", true);
            TestShelterFixture.insertShelter(jdbc, primaryTenantId, "Primary DV B", true);
        });

        ResponseEntity<Map<String, Object>> response = patch(primaryTenantId, false, cocAdminHeaders);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) response.getBody().get("context");
        assertThat(context)
                .as("Structured error code surfaces in response context for client-parseable UX")
                .containsEntry("errorCode", ErrorCodes.TENANT_DV_POLICY_CANNOT_DISABLE_WHILE_DV_SHELTERS_EXIST);
        assertThat(context).containsEntry("remaining_dv_shelter_count", 2);

        assertThat(readDvPolicyKey(primaryTenantId))
                .as("Flag value MUST NOT change on rejected disable")
                .isEqualTo("true");

        // Riley's failed-disable audit field-content assertion (warroom round 1)
        long rejectedAuditCount = countRejectedDisableAuditRows(primaryTenantId, 2);
        assertThat(rejectedAuditCount)
                .as("rejected-disable audit row MUST carry outcome, rejection_code, and remaining_dv_shelter_count")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Disable allowed after DV shelters deactivated — 200, audit applied")
    void disableAllowedAfterDeactivation() {
        // Setup: enable flag, create DV shelter, deactivate it
        patch(primaryTenantId, true, cocAdminHeaders);
        UUID dvShelterId = TenantContext.callWithContext(primaryTenantId, true, () -> {
            UUID id = TestShelterFixture.insertShelter(jdbc, primaryTenantId, "Primary DV", true);
            jdbc.update("UPDATE shelter SET active = false WHERE id = ?", id);
            return id;
        });
        assertThat(dvShelterId).isNotNull();

        ResponseEntity<Map<String, Object>> response = patch(primaryTenantId, false, cocAdminHeaders);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readDvPolicyKey(primaryTenantId)).isEqualTo("false");
    }

    // ----- Authorization -------------------------------------------------

    @Test
    @DisplayName("COORDINATOR forbidden — 403")
    void coordinatorForbidden() {
        ResponseEntity<Map<String, Object>> response = patch(primaryTenantId, true, coordinatorHeaders);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("OUTREACH_WORKER forbidden — 403")
    void outreachWorkerForbidden() {
        ResponseEntity<Map<String, Object>> response = patch(primaryTenantId, true, outreachHeaders);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("COC_ADMIN without dvAccess forbidden — 403 (warroom round 2)")
    void cocAdminWithoutDvAccessForbidden() {
        // Without this guard, the disable-path count query (which runs through
        // RLS as fabt_app) would return 0 because RLS hides DV shelters from
        // dvAccess=false callers — and the disable would wrongly succeed
        // while DV shelters actually exist.
        ResponseEntity<Map<String, Object>> response = patch(
                primaryTenantId, true, cocAdminWithoutDvAccessHeaders);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Unauthenticated — 401")
    void unauthenticated() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/admin/tenants/" + primaryTenantId + "/dv-policy",
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("dvPolicyEnabled", true)),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ----- Cross-tenant scoping (Marcus warroom round 1) -----------------

    @Test
    @DisplayName("Cross-tenant probe — 403, no body, no DV-shelter-count leak via response or timing")
    void crossTenantProbeDoesNotLeakInventory() {
        // primary's COC_ADMIN probes secondary's path. Secondary has 3 active
        // DV shelters set up in @BeforeEach.
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/tenants/" + secondaryTenantId + "/dv-policy",
                HttpMethod.PATCH,
                new HttpEntity<>("{\"dvPolicyEnabled\":false}", cocAdminHeaders),
                String.class);

        assertThat(response.getStatusCode())
                .as("Cross-tenant access MUST 403 — JWT-bound tenantId != path tenantId")
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Critical: the 403 response must NOT contain the secondary tenant's
        // DV-shelter count anywhere — not in body, not in headers. The
        // tenant-scoping check fires BEFORE the count query, so the response
        // carries no inventory-derived data.
        String body = response.getBody() == null ? "" : response.getBody();
        assertThat(body)
                .as("Response body must not contain '3' (the secondary's DV-shelter count)")
                .doesNotContain("\"3\"")
                .doesNotContain("remaining_dv_shelter_count");

        // Headers also clean
        assertThat(response.getHeaders().toString())
                .as("Response headers must not leak the count")
                .doesNotContain("remaining_dv_shelter_count");

        // §6.3 defense-in-depth (Marcus warroom 2): the controller MUST emit
        // a TENANT_CONFIG_UPDATED audit row with rejection_code
        // tenant.crossTenantAccess on the cross-tenant attempt. The audit
        // lands in the CALLER'S (primary) tenant audit chain — the
        // forensic signal lives where incident-response would look.
        Long crossTenantAuditCount = TenantContext.callWithContext(primaryTenantId, true,
                () -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_events "
                                + "WHERE action = 'TENANT_CONFIG_UPDATED' "
                                + "AND details ->> 'config_key' = 'dv_policy_enabled' "
                                + "AND details ->> 'outcome' = 'rejected' "
                                + "AND details ->> 'rejection_code' = ? "
                                + "AND details ->> 'target_tenant_id' = ?",
                        Long.class,
                        ErrorCodes.TENANT_CROSS_TENANT_ACCESS,
                        secondaryTenantId.toString()));
        assertThat(crossTenantAuditCount)
                .as("cross-tenant probe MUST emit a forensic audit row — defense-in-depth")
                .isEqualTo(1);
    }

    // ----- Validation ----------------------------------------------------

    @Test
    @DisplayName("Missing dvPolicyEnabled in body — 400 Bean Validation")
    void missingFieldRejected() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/admin/tenants/" + primaryTenantId + "/dv-policy",
                HttpMethod.PATCH,
                new HttpEntity<>("{}", cocAdminHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ----- helpers -------------------------------------------------------

    private ResponseEntity<Map<String, Object>> patch(UUID tenantId, boolean dvPolicyEnabled,
                                                       HttpHeaders headers) {
        return restTemplate.exchange(
                "/api/v1/admin/tenants/" + tenantId + "/dv-policy",
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("dvPolicyEnabled", dvPolicyEnabled), headers),
                new ParameterizedTypeReference<>() {});
    }

    private String readDvPolicyKey(UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT config ->> 'dv_policy_enabled' FROM tenant WHERE id = ?",
                String.class, tenantId);
    }

    private void clearDvPolicyKey(UUID tenantId) {
        jdbc.update(
                "UPDATE tenant SET config = config - 'dv_policy_enabled' WHERE id = ?",
                tenantId);
    }

    /**
     * Returns the {@code new_value} field of the most recent applied audit
     * row for the given tenant's dv_policy_enabled key. Returns null if no
     * applied row exists.
     *
     * <p>{@code audit_events} is RLS-protected (V68), so the query MUST run
     * inside {@link TenantContext#callWithContext} with the target tenant
     * bound. Otherwise RLS hides every row and the query returns null even
     * when the audit was actually emitted.
     */
    private String latestAppliedAuditNewValue(UUID tenantId) {
        return TenantContext.callWithContext(tenantId, true, () -> {
            try {
                return jdbc.queryForObject(
                        "SELECT details ->> 'new_value' FROM audit_events "
                                + "WHERE action = 'TENANT_CONFIG_UPDATED' "
                                + "AND details ->> 'config_key' = 'dv_policy_enabled' "
                                + "AND details ->> 'outcome' = 'applied' "
                                + "ORDER BY timestamp DESC LIMIT 1",
                        String.class);
            } catch (org.springframework.dao.EmptyResultDataAccessException e) {
                return null;
            }
        });
    }

    /**
     * Counts rejected-disable audit rows matching the dv-policy disable
     * structured-error code. Used by
     * {@link #disableRejectedWhileDvSheltersExist} to verify the rejected
     * audit row carries all four expected fields. Wraps query in
     * TenantContext for RLS as above.
     */
    private long countRejectedDisableAuditRows(UUID tenantId, int expectedRemaining) {
        return TenantContext.callWithContext(tenantId, true, () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events "
                        + "WHERE action = 'TENANT_CONFIG_UPDATED' "
                        + "AND details ->> 'config_key' = 'dv_policy_enabled' "
                        + "AND details ->> 'outcome' = 'rejected' "
                        + "AND details ->> 'rejection_code' = ? "
                        + "AND (details ->> 'remaining_dv_shelter_count')::int = ?",
                Long.class,
                ErrorCodes.TENANT_DV_POLICY_CANNOT_DISABLE_WHILE_DV_SHELTERS_EXIST,
                expectedRemaining));
    }
}
