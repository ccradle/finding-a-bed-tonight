package org.fabt.tenant.api;

import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.shared.errors.ErrorCodes;
import org.fabt.shared.web.TenantContext;
import org.fabt.auth.domain.User;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ContactEmailController} covering
 * info-email-contact task §3.4 — the 8 scenarios in the spec under
 * "Backend integration tests".
 *
 * <p>Per {@code feedback_isolated_test_data}: each test uses freshly-uuid'd
 * tenants via {@link TestAuthHelper}. The default {@code dv_policy_enabled=true}
 * posture from {@code TestAuthHelper.setupTestTenant} is selectively cleared
 * for happy-path tests that exercise the non-DV write surface, and kept
 * enabled for the DV-policy guard tests.
 */
@DisplayName("PATCH /api/v1/admin/tenants/{id}/contact-email")
class ContactEmailControllerTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JdbcTemplate jdbc;

    private UUID primaryTenantId;
    private UUID secondaryTenantId;
    private HttpHeaders cocAdminHeaders;
    private HttpHeaders coordinatorHeaders;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Tenant primary = authHelper.setupTestTenant("contactemail-" + suffix);
        primaryTenantId = primary.getId();
        User cocAdmin = authHelper.setupUserWithDvAccess(
                "contactemail-cocadmin-" + suffix + "@test.fabt.org",
                "ContactEmail CoC Admin",
                new String[]{"COC_ADMIN"});
        cocAdminHeaders = authHelper.headersForUser(cocAdmin);

        authHelper.setupCoordinatorUser();
        coordinatorHeaders = authHelper.coordinatorHeaders();

        Tenant secondary = authHelper.setupSecondaryTenant("contactemail-secondary-" + suffix);
        secondaryTenantId = secondary.getId();
    }

    // ----- Happy path -------------------------------------------------------

    @Test
    @DisplayName("Valid email on non-DV tenant — 200, persisted, audit emitted")
    void happyPathValidEmail() {
        // Default test-tenant posture has dv_policy_enabled=true; clear it so
        // the DV-policy guard does not block the happy-path PATCH. Tests that
        // verify the guard live below.
        clearDvPolicyKey(primaryTenantId);

        ResponseEntity<Map<String, Object>> response =
                patch(primaryTenantId, Map.of("email", "info@example.com"), cocAdminHeaders);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("contactEmail", "info@example.com");
        assertThat(readContactEmailKey(primaryTenantId)).isEqualTo("info@example.com");
        assertThat(latestAppliedAuditNewValue(primaryTenantId)).isEqualTo("info@example.com");
    }

    // ----- Bean Validation rejections --------------------------------------

    @Test
    @DisplayName("Malformed email — 400 (Bean Validation @Email)")
    void malformedEmailRejected() {
        clearDvPolicyKey(primaryTenantId);

        ResponseEntity<Map<String, Object>> response =
                patch(primaryTenantId, Map.of("email", "not-a-valid-email"), cocAdminHeaders);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(readContactEmailKey(primaryTenantId))
                .as("Persisted state must NOT change on validation failure")
                .isNull();
    }

    @Test
    @DisplayName("Email > 254 chars — 400 (Bean Validation @Size)")
    void oversizeEmailRejected() {
        clearDvPolicyKey(primaryTenantId);

        // 255-char local part keeps @-format valid for @Email but fails @Size.
        // (255 > 254 cap.) Constructing as repeated 'a' makes the failure
        // unambiguously about length, not format.
        String oversize = "a".repeat(245) + "@example.com"; // 245 + 12 = 257 chars
        assertThat(oversize.length()).isGreaterThan(254);

        ResponseEntity<Map<String, Object>> response =
                patch(primaryTenantId, Map.of("email", oversize), cocAdminHeaders);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(readContactEmailKey(primaryTenantId)).isNull();
    }

    // ----- Clearing path ----------------------------------------------------

    @Test
    @DisplayName("Empty string — 200, key cleared from JSONB")
    void emptyStringClears() {
        clearDvPolicyKey(primaryTenantId);
        // Seed an existing value first so the clear has something to remove.
        patch(primaryTenantId, Map.of("email", "old@example.com"), cocAdminHeaders);
        assertThat(readContactEmailKey(primaryTenantId)).isEqualTo("old@example.com");

        ResponseEntity<Map<String, Object>> response =
                patch(primaryTenantId, Map.of("email", ""), cocAdminHeaders);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("contactEmail")).isNull();
        assertThat(readContactEmailKey(primaryTenantId))
                .as("contact.email key must be removed from JSONB after empty-string PATCH")
                .isNull();
        // contact subtree should also be removed when it becomes empty (no
        // sibling keys today). A future test that adds contact.phone would
        // break this assertion intentionally — the absence indicates clean
        // persistence on a single-key subtree.
        assertThat(readContactKey(primaryTenantId))
                .as("contact subtree should be removed when its only key was email")
                .isNull();
    }

    // ----- DV-policy guard --------------------------------------------------

    @Test
    @DisplayName("DV-policy=true + non-empty email — 400 with tenant.contactEmail.dvPolicyForbidden + audit emitted")
    void dvPolicyForbidsNonEmpty() {
        // setupTestTenant defaults dv_policy_enabled=true — exactly the state
        // we want for this test. Do NOT clear.
        assertThat(readDvPolicyKey(primaryTenantId)).isEqualTo("true");

        ResponseEntity<Map<String, Object>> response =
                patch(primaryTenantId, Map.of("email", "info@example.com"), cocAdminHeaders);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) response.getBody().get("context");
        assertThat(context)
                .as("Structured error code surfaces in response context for client-parseable UX")
                .containsEntry("errorCode", ErrorCodes.TENANT_CONTACT_EMAIL_DV_POLICY_FORBIDDEN);
        assertThat(context).containsEntry("dv_policy_enabled", true);

        assertThat(readContactEmailKey(primaryTenantId))
                .as("Persisted state MUST NOT change on DV-policy rejection")
                .isNull();

        long rejectedAuditCount = TenantContext.callWithContext(primaryTenantId, true,
                () -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_events "
                                + "WHERE action = 'TENANT_CONFIG_UPDATED' "
                                + "AND details ->> 'config_key' = 'contact.email' "
                                + "AND details ->> 'outcome' = 'rejected' "
                                + "AND details ->> 'rejection_code' = ?",
                        Long.class,
                        ErrorCodes.TENANT_CONTACT_EMAIL_DV_POLICY_FORBIDDEN));
        assertThat(rejectedAuditCount)
                .as("DV-policy rejection MUST emit a forensic audit row")
                .isEqualTo(1);

        // Warroom round 2 M2-NEW (Marcus): the rejected audit row's old_value
        // and new_value MUST be equal. The controller passes (oldValue, oldValue)
        // to the audit emit because no state change occurred. A regression
        // that flipped this to (oldValue, requestedEmail) would silently leak
        // the rejected-but-attempted email value into the audit chain — bad
        // for forensic-noise even though contact emails are intentionally
        // public. Defensive assertion keeps the audit row honest.
        long sameValueRejectedRows = TenantContext.callWithContext(primaryTenantId, true,
                () -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_events "
                                + "WHERE action = 'TENANT_CONFIG_UPDATED' "
                                + "AND details ->> 'config_key' = 'contact.email' "
                                + "AND details ->> 'outcome' = 'rejected' "
                                + "AND details ->> 'rejection_code' = ? "
                                + "AND ("
                                + "  (details ->> 'old_value' IS NULL AND details ->> 'new_value' IS NULL) "
                                + "  OR details ->> 'old_value' = details ->> 'new_value'"
                                + ") "
                                + "AND (details ->> 'value_changed')::boolean = false",
                        Long.class,
                        ErrorCodes.TENANT_CONTACT_EMAIL_DV_POLICY_FORBIDDEN));
        assertThat(sameValueRejectedRows)
                .as("Rejected audit row MUST carry old_value == new_value AND value_changed=false — "
                        + "no state change occurred, the audit must reflect that")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("DV-policy=true + empty-string PATCH — 200 (clearing always allowed for inheritance)")
    void dvPolicyAllowsEmptyClear() {
        // First, seed a value via the UPDATE trick (we cannot legitimately
        // PATCH a non-empty value while the flag is true — that's the whole
        // point of this guard). We're testing that an operator who somehow
        // ended up with a stale override (e.g. set BEFORE the flag was
        // enabled) can always clear it back to platform-default inheritance.
        seedContactEmailDirectly(primaryTenantId, "stale@example.com");
        assertThat(readContactEmailKey(primaryTenantId)).isEqualTo("stale@example.com");
        assertThat(readDvPolicyKey(primaryTenantId)).isEqualTo("true");

        ResponseEntity<Map<String, Object>> response =
                patch(primaryTenantId, Map.of("email", ""), cocAdminHeaders);

        assertThat(response.getStatusCode())
                .as("Empty-string PATCH MUST succeed even when DV-policy is enabled")
                .isEqualTo(HttpStatus.OK);
        assertThat(readContactEmailKey(primaryTenantId))
                .as("Stale override must be cleared")
                .isNull();
    }

    // ----- Authorization ----------------------------------------------------

    @Test
    @DisplayName("Cross-tenant probe — 403 + audit row + no body leak of secondary tenant's email")
    void crossTenantProbe() {
        // Warroom round 1 H2 (Marcus): seed secondary with a discoverable email
        // value so the no-leak assertion has a concrete needle to look for.
        // Without a seeded value, the assertion is vacuous — and the moment a
        // future setUp() change adds an email to secondary for an unrelated
        // reason, this test silently stops protecting against body-leak.
        String secondarySecret = "secondary-secret@example.com";
        seedContactEmailDirectly(secondaryTenantId, secondarySecret);
        // primary's COC_ADMIN probes secondary's path.
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/tenants/" + secondaryTenantId + "/contact-email",
                HttpMethod.PATCH,
                new HttpEntity<>("{\"email\":\"probe@example.com\"}", jsonHeaders(cocAdminHeaders)),
                String.class);

        assertThat(response.getStatusCode())
                .as("Cross-tenant access MUST 403 — JWT-bound tenantId != path tenantId")
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Body and headers MUST NOT contain the secondary's existing email —
        // the tenant-scope guard fires BEFORE the tenant lookup, so the
        // response carries no inventory-derived data.
        String body = response.getBody() == null ? "" : response.getBody();
        assertThat(body)
                .as("Response body must not leak the secondary tenant's existing contact email")
                .doesNotContain(secondarySecret);
        assertThat(response.getHeaders().toString())
                .as("Response headers must not leak the secondary tenant's existing contact email")
                .doesNotContain(secondarySecret);

        // Defense-in-depth audit row lands in the CALLER'S (primary) tenant.
        Long crossTenantAuditCount = TenantContext.callWithContext(primaryTenantId, true,
                () -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_events "
                                + "WHERE action = 'TENANT_CONFIG_UPDATED' "
                                + "AND details ->> 'config_key' = 'contact.email' "
                                + "AND details ->> 'outcome' = 'rejected' "
                                + "AND details ->> 'rejection_code' = ? "
                                + "AND details ->> 'target_tenant_id' = ?",
                        Long.class,
                        ErrorCodes.TENANT_CROSS_TENANT_ACCESS,
                        secondaryTenantId.toString()));
        assertThat(crossTenantAuditCount)
                .as("Cross-tenant probe MUST emit a forensic audit row — defense-in-depth")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Unauthenticated — 401")
    void unauthenticated() {
        // Warroom round 1 H3 (Marcus): JWT filter must reject anonymous
        // requests before they reach the controller. Mirrors
        // DvPolicyControllerTest.unauthenticated.
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/admin/tenants/" + primaryTenantId + "/contact-email",
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("email", "info@example.com")),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("COORDINATOR role — 403")
    void coordinatorForbidden() {
        clearDvPolicyKey(primaryTenantId);

        ResponseEntity<Map<String, Object>> response =
                patch(primaryTenantId, Map.of("email", "info@example.com"), coordinatorHeaders);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("OUTREACH_WORKER role — 403")
    void outreachWorkerForbidden() {
        // Warroom round 1 M2 (Riley): parity with DvPolicyControllerTest.
        // @PreAuthorize("hasRole('COC_ADMIN')") must reject every non-admin
        // role, not only COORDINATOR.
        clearDvPolicyKey(primaryTenantId);
        authHelper.setupOutreachWorkerUser();
        HttpHeaders outreachHeaders = authHelper.outreachWorkerHeaders();

        ResponseEntity<Map<String, Object>> response =
                patch(primaryTenantId, Map.of("email", "info@example.com"), outreachHeaders);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ----- Audit-emission semantics ----------------------------------------

    @Test
    @DisplayName("Idempotent re-set: two PATCHes emit two applied rows; value_changed=true then false")
    void idempotentReSetEmitsAuditWithValueChangedFlag() {
        // Warroom round 1 H1 (Riley): the value_changed audit field
        // distinguishes real flips from idempotent re-sets and is meaningful
        // to audit-replay tooling. Without an explicit assertion, a regression
        // that inverts the comparison goes silent. Mirrors
        // DvPolicyControllerTest.idempotentReEnable.
        clearDvPolicyKey(primaryTenantId);

        // First PATCH — real flip (null → "info@example.com"), value_changed=true.
        patch(primaryTenantId, Map.of("email", "info@example.com"), cocAdminHeaders);
        // Second PATCH — idempotent re-set (same → same), value_changed=false.
        patch(primaryTenantId, Map.of("email", "info@example.com"), cocAdminHeaders);

        long appliedRows = TenantContext.callWithContext(primaryTenantId, true,
                () -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_events "
                                + "WHERE action = 'TENANT_CONFIG_UPDATED' "
                                + "AND details ->> 'config_key' = 'contact.email' "
                                + "AND details ->> 'outcome' = 'applied'",
                        Long.class));
        assertThat(appliedRows)
                .as("Two PATCHes MUST emit two applied audit rows — intent is the audit signal")
                .isEqualTo(2L);

        long valueChangedTrueRows = TenantContext.callWithContext(primaryTenantId, true,
                () -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_events "
                                + "WHERE action = 'TENANT_CONFIG_UPDATED' "
                                + "AND details ->> 'config_key' = 'contact.email' "
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
                                + "AND details ->> 'config_key' = 'contact.email' "
                                + "AND details ->> 'outcome' = 'applied' "
                                + "AND (details ->> 'value_changed')::boolean = false",
                        Long.class));
        assertThat(valueChangedFalseRows)
                .as("Second PATCH was an idempotent re-set — exactly one row should carry value_changed=false")
                .isEqualTo(1L);
    }

    // ----- Edge cases ------------------------------------------------------

    @Test
    @DisplayName("Body with absent email field ({}) — 200, behaves like empty-string clear")
    void absentEmailFieldBehavesLikeClear() {
        // Warroom round 1 M3 (Riley): document the absent-field semantic.
        // Bean Validation has no @NotNull on the email field, so {} deserializes
        // to email=null, validation passes, controller normalizes null → ""
        // and treats as clear. This is the least-surprise behavior — both
        // {"email": ""} and {} mean "revert to platform-default inheritance".
        clearDvPolicyKey(primaryTenantId);
        // Seed a value first so the clear has something to remove.
        patch(primaryTenantId, Map.of("email", "old@example.com"), cocAdminHeaders);
        assertThat(readContactEmailKey(primaryTenantId)).isEqualTo("old@example.com");

        // Send {} — Map.of() with no entries serializes to "{}".
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/admin/tenants/" + primaryTenantId + "/contact-email",
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of(), jsonHeaders(cocAdminHeaders)),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("contactEmail")).isNull();
        assertThat(readContactEmailKey(primaryTenantId)).isNull();
    }

    // ----- helpers ----------------------------------------------------------

    private ResponseEntity<Map<String, Object>> patch(UUID tenantId, Map<String, Object> body,
                                                      HttpHeaders headers) {
        return restTemplate.exchange(
                "/api/v1/admin/tenants/" + tenantId + "/contact-email",
                HttpMethod.PATCH,
                new HttpEntity<>(body, jsonHeaders(headers)),
                new ParameterizedTypeReference<>() {});
    }

    private static HttpHeaders jsonHeaders(HttpHeaders source) {
        HttpHeaders copy = new HttpHeaders();
        copy.addAll(source);
        copy.setContentType(MediaType.APPLICATION_JSON);
        return copy;
    }

    private String readContactEmailKey(UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT config -> 'contact' ->> 'email' FROM tenant WHERE id = ?",
                String.class, tenantId);
    }

    private String readContactKey(UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT config ->> 'contact' FROM tenant WHERE id = ?",
                String.class, tenantId);
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
     * Direct JDBC write of {@code contact.email} bypassing the controller.
     * Used by {@link #dvPolicyAllowsEmptyClear} to seed a stale override that
     * could not legitimately be created via the API once the DV-policy flag
     * is enabled — modeling the "operator set the value before enabling DV
     * policy, then enabled the flag, then needs to clear" recovery path.
     *
     * <p>Uses top-level JSONB concatenation ({@code ||}) instead of
     * {@code jsonb_set(..., create_missing := true)} because the latter does
     * NOT create missing INTERMEDIATE path elements: if {@code contact} does
     * not yet exist, {@code jsonb_set(config, '{contact,email}', ...)} is a
     * no-op. {@code ||} replaces the {@code contact} key wholesale, which
     * is the right semantic for a single-key seed.
     */
    private void seedContactEmailDirectly(UUID tenantId, String email) {
        jdbc.update(
                "UPDATE tenant SET config = config || jsonb_build_object('contact', jsonb_build_object('email', ?::text)) WHERE id = ?",
                email, tenantId);
    }

    /**
     * Returns the {@code new_value} field of the most recent applied audit
     * row. {@code audit_events} is RLS-protected so the query MUST run inside
     * {@link TenantContext#callWithContext}.
     */
    private String latestAppliedAuditNewValue(UUID tenantId) {
        return TenantContext.callWithContext(tenantId, true, () -> {
            try {
                return jdbc.queryForObject(
                        "SELECT details ->> 'new_value' FROM audit_events "
                                + "WHERE action = 'TENANT_CONFIG_UPDATED' "
                                + "AND details ->> 'config_key' = 'contact.email' "
                                + "AND details ->> 'outcome' = 'applied' "
                                + "ORDER BY timestamp DESC LIMIT 1",
                        String.class);
            } catch (org.springframework.dao.EmptyResultDataAccessException e) {
                return null;
            }
        });
    }
}
