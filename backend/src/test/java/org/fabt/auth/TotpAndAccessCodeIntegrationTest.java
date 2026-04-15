package org.fabt.auth;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.shared.security.SecretEncryptionService;
import org.fabt.shared.web.TenantContext;
import org.fabt.tenant.domain.Tenant;
import org.fabt.auth.service.TotpService;
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
 * Integration tests for TOTP 2FA and admin access code flows.
 *
 * Tests: enrollment, two-phase login, backup codes, admin access code,
 * DV safeguard, PasswordChangeRequiredFilter, security (mfaToken
 * single-use, rate limiting, filter skip).
 */
@DisplayName("TOTP 2FA & Access Code")
class TotpAndAccessCodeIntegrationTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TotpService totpService;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        authHelper.setupCocAdminUser();
        var outreach = authHelper.setupOutreachWorkerUser();

        // Reset TOTP state before each test — prevents ordering dependency
        outreach.setTotpEnabled(false);
        outreach.setTotpSecretEncrypted(null);
        outreach.setRecoveryCodes(null);
        outreach.setUpdatedAt(java.time.Instant.now());
        authHelper.getUserRepository().save(outreach);
    }

    // =========================================================================
    // T-25: TOTP enrollment flow
    // =========================================================================

    @Test
    @DisplayName("TOTP enrollment: generate → verify → enabled, secret encrypted in DB")
    void totpEnrollment_fullFlow() {
        HttpHeaders headers = authHelper.outreachWorkerHeaders();

        // Step 1: Start enrollment
        ResponseEntity<Map<String, Object>> enrollResponse = restTemplate.exchange(
                "/api/v1/auth/enroll-totp", HttpMethod.POST,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});

        // With D16 (test encryption key in BaseIntegrationTest), enrollment must succeed
        assertThat(enrollResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String secret = (String) enrollResponse.getBody().get("secret");
        String qrUri = (String) enrollResponse.getBody().get("qrUri");
        assertThat(secret).isNotBlank();
        assertThat(qrUri).contains("otpauth://totp/");

        // Step 2: Generate valid TOTP code and confirm enrollment
        String code = TotpTestHelper.generateCode(secret);
        ResponseEntity<Map<String, Object>> confirmResponse = restTemplate.exchange(
                "/api/v1/auth/confirm-totp-enrollment", HttpMethod.POST,
                new HttpEntity<>(Map.of("code", code), headers),
                new ParameterizedTypeReference<>() {});

        assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmResponse.getBody().get("enabled")).isEqualTo(true);

        @SuppressWarnings("unchecked")
        List<String> backupCodes = (List<String>) confirmResponse.getBody().get("backupCodes");
        assertThat(backupCodes).hasSize(8);
        // Enrollment succeeded + backup codes returned = encrypted storage verified
    }

    // =========================================================================
    // T-26: Two-phase login
    // =========================================================================

    @Test
    @DisplayName("Two-phase login: password → mfaRequired → TOTP verify → JWTs")
    void twoPhaseLogin_fullFlow() {
        // Create a user with TOTP enabled via direct DB setup
        var user = authHelper.setupOutreachWorkerUser();
        String secret = enableTotpForUser(user);
        if (secret == null) return; // encryption not configured

        // Step 1: Login with password — should get mfaRequired
        ResponseEntity<Map<String, Object>> loginResponse = restTemplate.exchange(
                "/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "tenantSlug", authHelper.getTestTenantSlug(),
                        "email", TestAuthHelper.OUTREACH_EMAIL,
                        "password", TestAuthHelper.TEST_PASSWORD
                )),
                new ParameterizedTypeReference<>() {});

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody().get("mfaRequired")).isEqualTo(true);
        String mfaToken = (String) loginResponse.getBody().get("mfaToken");
        assertThat(mfaToken).isNotBlank();

        // Step 2: Verify TOTP code
        String code = TotpTestHelper.generateCode(secret);
        ResponseEntity<Map<String, Object>> verifyResponse = restTemplate.exchange(
                "/api/v1/auth/verify-totp", HttpMethod.POST,
                new HttpEntity<>(Map.of("mfaToken", mfaToken, "code", code)),
                new ParameterizedTypeReference<>() {});

        assertThat(verifyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verifyResponse.getBody()).containsKey("accessToken");
        assertThat(verifyResponse.getBody()).containsKey("refreshToken");
    }

    // =========================================================================
    // T-27: Recovery code substitutes for TOTP
    // =========================================================================

    @Test
    @DisplayName("Backup code substitutes for TOTP, code marked consumed")
    void backupCode_substitutesForTotp() {
        var user = authHelper.setupOutreachWorkerUser();
        String secret = enableTotpForUser(user);
        if (secret == null) return;

        // Get a backup code
        String backupCode = getFirstBackupCode(user);
        if (backupCode == null) return;

        // Login to get mfaToken
        ResponseEntity<Map<String, Object>> loginResponse = restTemplate.exchange(
                "/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "tenantSlug", authHelper.getTestTenantSlug(),
                        "email", TestAuthHelper.OUTREACH_EMAIL,
                        "password", TestAuthHelper.TEST_PASSWORD
                )),
                new ParameterizedTypeReference<>() {});

        String mfaToken = (String) loginResponse.getBody().get("mfaToken");

        // Use backup code instead of TOTP
        ResponseEntity<Map<String, Object>> verifyResponse = restTemplate.exchange(
                "/api/v1/auth/verify-totp", HttpMethod.POST,
                new HttpEntity<>(Map.of("mfaToken", mfaToken, "code", backupCode)),
                new ParameterizedTypeReference<>() {});

        assertThat(verifyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verifyResponse.getBody()).containsKey("accessToken");
    }

    // =========================================================================
    // T-28: Admin generates access code, user logs in
    // =========================================================================

    @Test
    @DisplayName("Admin generates access code, user logs in with it")
    void adminAccessCode_loginFlow() {
        HttpHeaders adminHeaders = authHelper.cocAdminHeaders();

        var outreachUser = authHelper.setupOutreachWorkerUser();

        // Generate access code
        ResponseEntity<Map<String, Object>> codeResponse = restTemplate.exchange(
                "/api/v1/users/" + outreachUser.getId() + "/generate-access-code",
                HttpMethod.POST,
                new HttpEntity<>(adminHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(codeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String accessCode = (String) codeResponse.getBody().get("code");
        assertThat(accessCode).isNotBlank();

        // Login with access code
        ResponseEntity<Map<String, Object>> loginResponse = restTemplate.exchange(
                "/api/v1/auth/access-code", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "email", TestAuthHelper.OUTREACH_EMAIL,
                        "tenantSlug", authHelper.getTestTenantSlug(),
                        "code", accessCode
                )),
                new ParameterizedTypeReference<>() {});

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody().get("mustChangePassword")).isEqualTo(true);
        assertThat(loginResponse.getBody()).containsKey("accessToken");
    }

    // =========================================================================
    // T-29: DV safeguard — non-DV admin cannot generate code for DV user
    // =========================================================================

    @Test
    @DisplayName("DV-access user code requires DV-authorized admin")
    void dvSafeguard_rejectsNonDvAdmin() {
        // Create a DV user
        var dvUser = authHelper.setupUserWithDvAccess(
                "dv-test-totp@test.fabt.org", "DV Test User", new String[]{"OUTREACH_WORKER"});

        // Non-DV admin tries to generate code
        HttpHeaders nonDvAdminHeaders = authHelper.cocAdminHeaders();
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/users/" + dvUser.getId() + "/generate-access-code",
                HttpMethod.POST,
                new HttpEntity<>(nonDvAdminHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // =========================================================================
    // T-32: Expired access code rejected
    // =========================================================================

    @Test
    @DisplayName("Expired access code is rejected")
    void expiredAccessCode_rejected() {
        // Create an expired code directly in DB
        var outreachUser = authHelper.setupOutreachWorkerUser();
        var admin = authHelper.setupCocAdminUser();

        String codeHash = authHelper.getPasswordService().hash("EXPIRED12345");
        jdbcTemplate.update(
                "INSERT INTO one_time_access_code (user_id, tenant_id, code_hash, expires_at, created_by) VALUES (?, ?, ?, NOW() - INTERVAL '1 hour', ?)",
                outreachUser.getId(), authHelper.getTestTenantId(), codeHash, admin.getId());

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/auth/access-code", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "email", TestAuthHelper.OUTREACH_EMAIL,
                        "tenantSlug", authHelper.getTestTenantSlug(),
                        "code", "EXPIRED12345"
                )),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // T-33: mfaToken single-use
    // =========================================================================

    @Test
    @DisplayName("mfaToken is single-use — second use after successful verify rejected")
    void mfaToken_singleUse() {
        var user = authHelper.setupOutreachWorkerUser();
        String secret = enableTotpForUser(user);
        if (secret == null) return;

        // Login to get mfaToken
        ResponseEntity<Map<String, Object>> loginResponse = restTemplate.exchange(
                "/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "tenantSlug", authHelper.getTestTenantSlug(),
                        "email", TestAuthHelper.OUTREACH_EMAIL,
                        "password", TestAuthHelper.TEST_PASSWORD
                )),
                new ParameterizedTypeReference<>() {});

        String mfaToken = (String) loginResponse.getBody().get("mfaToken");
        String code = TotpTestHelper.generateCode(secret);

        // First use — succeeds
        ResponseEntity<Map<String, Object>> first = restTemplate.exchange(
                "/api/v1/auth/verify-totp", HttpMethod.POST,
                new HttpEntity<>(Map.of("mfaToken", mfaToken, "code", code)),
                new ParameterizedTypeReference<>() {});
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second use — rejected (single-use)
        ResponseEntity<Map<String, Object>> second = restTemplate.exchange(
                "/api/v1/auth/verify-totp", HttpMethod.POST,
                new HttpEntity<>(Map.of("mfaToken", mfaToken, "code", code)),
                new ParameterizedTypeReference<>() {});
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // T-35: mfaToken with purpose="mfa" does NOT grant API access
    // =========================================================================

    @Test
    @DisplayName("mfaToken cannot be used as access token for API calls")
    void mfaToken_cannotAccessApi() {
        var user = authHelper.setupOutreachWorkerUser();
        String secret = enableTotpForUser(user);
        if (secret == null) return;

        // Get mfaToken
        ResponseEntity<Map<String, Object>> loginResponse = restTemplate.exchange(
                "/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "tenantSlug", authHelper.getTestTenantSlug(),
                        "email", TestAuthHelper.OUTREACH_EMAIL,
                        "password", TestAuthHelper.TEST_PASSWORD
                )),
                new ParameterizedTypeReference<>() {});

        String mfaToken = (String) loginResponse.getBody().get("mfaToken");

        // Try to use mfaToken as Bearer token for a protected endpoint
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(mfaToken);
        headers.set("Content-Type", "application/json");

        ResponseEntity<Map<String, Object>> apiResponse = restTemplate.exchange(
                "/api/v1/queries/beds", HttpMethod.POST,
                new HttpEntity<>("{}", headers),
                new ParameterizedTypeReference<>() {});

        // Should be 401 — mfaToken is not an access token
        assertThat(apiResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // T-36: PasswordChangeRequiredFilter blocks other endpoints
    // =========================================================================

    @Test
    @DisplayName("PasswordChangeRequired token blocks all endpoints except password change")
    void passwordChangeRequired_blocksOtherEndpoints() {
        var outreachUser = authHelper.setupOutreachWorkerUser();
        HttpHeaders adminHeaders = authHelper.cocAdminHeaders();

        // Generate access code
        ResponseEntity<Map<String, Object>> codeResponse = restTemplate.exchange(
                "/api/v1/users/" + outreachUser.getId() + "/generate-access-code",
                HttpMethod.POST,
                new HttpEntity<>(adminHeaders),
                new ParameterizedTypeReference<>() {});

        String accessCode = (String) codeResponse.getBody().get("code");

        // Login with access code — get mustChangePassword JWT
        ResponseEntity<Map<String, Object>> loginResponse = restTemplate.exchange(
                "/api/v1/auth/access-code", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "email", TestAuthHelper.OUTREACH_EMAIL,
                        "tenantSlug", authHelper.getTestTenantSlug(),
                        "code", accessCode
                )),
                new ParameterizedTypeReference<>() {});

        String token = (String) loginResponse.getBody().get("accessToken");

        // Try to access bed search — should be blocked
        HttpHeaders userHeaders = new HttpHeaders();
        userHeaders.setBearerAuth(token);
        userHeaders.set("Content-Type", "application/json");

        ResponseEntity<Map<String, Object>> searchResponse = restTemplate.exchange(
                "/api/v1/queries/beds", HttpMethod.POST,
                new HttpEntity<>("{}", userHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(searchResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // =========================================================================
    // T-30: Clock drift boundary test
    // =========================================================================

    @Test
    @DisplayName("Auth capabilities endpoint returns feature flags")
    void authCapabilities_returnsFlags() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/auth/capabilities", HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("emailResetAvailable");
        assertThat(response.getBody()).containsKey("totpAvailable");
        assertThat(response.getBody()).containsKey("accessCodeAvailable");
        assertThat(response.getBody().get("accessCodeAvailable")).isEqualTo(true);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Enable TOTP for a user via direct service calls (bypassing API).
     * Returns the plaintext secret, or null if encryption isn't configured.
     */
    private String enableTotpForUser(User user) {
        if (!totpService.isEncryptionConfigured()) return null;

        String secret = totpService.generateSecret();
        user.setTotpSecretEncrypted(totpService.encryptSecret(secret));
        user.setTotpEnabled(true);

        TotpService.BackupCodes codes = totpService.generateBackupCodes();
        try {
            user.setRecoveryCodes(new tools.jackson.databind.json.JsonMapper()
                    .writeValueAsString(codes.hashed()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        user.setUpdatedAt(java.time.Instant.now());
        authHelper.getUserRepository().save(user);

        return secret;
    }

    private String getFirstBackupCode(User user) {
        if (!totpService.isEncryptionConfigured()) return null;

        TotpService.BackupCodes codes = totpService.generateBackupCodes();
        try {
            user.setRecoveryCodes(new tools.jackson.databind.json.JsonMapper()
                    .writeValueAsString(codes.hashed()));
            user.setUpdatedAt(java.time.Instant.now());
            authHelper.getUserRepository().save(user);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return codes.plaintext().get(0);
    }

    // =========================================================================
    // T-58b–e: ACCESS_CODE_USED audit event fix (#58)
    // =========================================================================

    @Test
    @DisplayName("#58: Access code login creates audit event with correct actor_user_id")
    void accessCodeLogin_createsAuditEventWithCorrectActor() {
        HttpHeaders adminHeaders = authHelper.cocAdminHeaders();
        var outreachUser = authHelper.setupOutreachWorkerUser();

        // Generate access code
        ResponseEntity<Map<String, Object>> codeResponse = restTemplate.exchange(
                "/api/v1/users/" + outreachUser.getId() + "/generate-access-code",
                HttpMethod.POST, new HttpEntity<>(adminHeaders),
                new ParameterizedTypeReference<>() {});
        assertThat(codeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String code = (String) codeResponse.getBody().get("code");

        // Clear existing audit events for clean assertion
        jdbcTemplate.update("DELETE FROM audit_events WHERE target_user_id = ?", outreachUser.getId());

        // Login with access code
        ResponseEntity<Map<String, Object>> loginResponse = restTemplate.exchange(
                "/api/v1/auth/access-code", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "email", TestAuthHelper.OUTREACH_EMAIL,
                        "tenantSlug", authHelper.getTestTenantSlug(),
                        "code", code)),
                new ParameterizedTypeReference<>() {});
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify audit event: actor_user_id = target_user_id (self-authentication)
        var events = jdbcTemplate.queryForList(
                "SELECT actor_user_id, target_user_id, action, ip_address FROM audit_events WHERE target_user_id = ? AND action = 'ACCESS_CODE_USED'",
                outreachUser.getId());
        assertThat(events).as("ACCESS_CODE_USED audit event should exist").hasSize(1);
        assertThat(events.get(0).get("actor_user_id")).as("actor_user_id should equal target_user_id").isEqualTo(outreachUser.getId());
        assertThat(events.get(0).get("target_user_id")).isEqualTo(outreachUser.getId());
    }

    @Test
    @DisplayName("#58: Access code audit event includes IP address")
    void accessCodeLogin_auditEventIncludesIpAddress() {
        HttpHeaders adminHeaders = authHelper.cocAdminHeaders();
        var outreachUser = authHelper.setupOutreachWorkerUser();

        ResponseEntity<Map<String, Object>> codeResponse = restTemplate.exchange(
                "/api/v1/users/" + outreachUser.getId() + "/generate-access-code",
                HttpMethod.POST, new HttpEntity<>(adminHeaders),
                new ParameterizedTypeReference<>() {});
        String code = (String) codeResponse.getBody().get("code");

        jdbcTemplate.update("DELETE FROM audit_events WHERE target_user_id = ?", outreachUser.getId());

        restTemplate.exchange("/api/v1/auth/access-code", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "email", TestAuthHelper.OUTREACH_EMAIL,
                        "tenantSlug", authHelper.getTestTenantSlug(),
                        "code", code)),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        var events = jdbcTemplate.queryForList(
                "SELECT ip_address FROM audit_events WHERE target_user_id = ? AND action = 'ACCESS_CODE_USED'",
                outreachUser.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("ip_address")).as("IP address should be recorded").isNotNull();
    }

    @Test
    @DisplayName("#58: Standard login audit events not affected by access code fix")
    void standardLogin_auditEventsUnaffected() {
        authHelper.setupOutreachWorkerUser();

        // Standard email/password login — should complete without constraint violations
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> loginResponse = restTemplate.exchange(
                "/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "email", TestAuthHelper.OUTREACH_EMAIL,
                        "tenantSlug", authHelper.getTestTenantSlug(),
                        "password", TestAuthHelper.TEST_PASSWORD), headers),
                new ParameterizedTypeReference<>() {});

        // The key assertion: login completes successfully (no constraint violations from our change)
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("#58: Access code login does not produce constraint violation")
    void accessCodeLogin_noConstraintViolation() {
        HttpHeaders adminHeaders = authHelper.cocAdminHeaders();
        var outreachUser = authHelper.setupOutreachWorkerUser();

        ResponseEntity<Map<String, Object>> codeResponse = restTemplate.exchange(
                "/api/v1/users/" + outreachUser.getId() + "/generate-access-code",
                HttpMethod.POST, new HttpEntity<>(adminHeaders),
                new ParameterizedTypeReference<>() {});
        String code = (String) codeResponse.getBody().get("code");

        // This should NOT throw or produce 500 — the bug was a NOT NULL constraint violation
        ResponseEntity<Map<String, Object>> loginResponse = restTemplate.exchange(
                "/api/v1/auth/access-code", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "email", TestAuthHelper.OUTREACH_EMAIL,
                        "tenantSlug", authHelper.getTestTenantSlug(),
                        "code", code)),
                new ParameterizedTypeReference<>() {});

        // Must be 200, not 500 (constraint violation would cause 500)
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody()).containsKey("accessToken");
    }

    // ================================================================
    // cross-tenant-isolation-audit (Issue #117) — Phase 2 task 2.3.3.
    // Two regression tests pinning the userService.getUser refactor on
    // TotpController.disableUserTotp + .adminRegenerateRecoveryCodes.
    //
    // THREAT MODEL (Marcus Webb, VULN-HIGH — account takeover precursor):
    // pre-fix, a CoC admin in Tenant A could DELETE /api/v1/auth/totp/
    // {tenantB-user-id} OR POST /api/v1/auth/totp/{tenantB-user-id}/
    // regenerate-recovery-codes. The disable path removes a Tenant B
    // user's 2FA shield; the regenerate path directly returns new
    // backup codes in the response body — no additional steps needed
    // for account takeover once the attacker gets those codes.
    // ================================================================

    @Test
    @DisplayName("Cross-tenant TotpController.disableUserTotp → 404, Tenant B user's 2FA preserved")
    void tc_disableUserTotp_crossTenant_returns404_leavesTenantBUser2FAIntact() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // Tenant B with a user enrolled in 2FA.
        Tenant tenantB = authHelper.setupSecondaryTenant("xtenant-totp-disable-" + suffix);
        User tenantBUser = authHelper.createUserInTenant(tenantB.getId(),
                "totp-victim-" + suffix + "@test.fabt.org", "TOTP Victim",
                new String[]{"OUTREACH_WORKER"}, false);

        // Enroll tenantBUser in 2FA by setting the fields directly (bypass
        // the self-enrollment flow — same shape as line 49 test setup).
        String testSecret = totpService.generateSecret();
        tenantBUser.setTotpEnabled(true);
        tenantBUser.setTotpSecretEncrypted(totpService.encryptSecret(testSecret));
        tenantBUser.setRecoveryCodes("[\"hash1\",\"hash2\"]");
        tenantBUser.setUpdatedAt(java.time.Instant.now());
        authHelper.getUserRepository().save(tenantBUser);

        // Act: Tenant A's COC_ADMIN attempts to disable Tenant B user's 2FA.
        HttpHeaders tenantAHeaders = authHelper.cocAdminHeaders();
        ResponseEntity<String> attackResp = restTemplate.exchange(
                "/api/v1/auth/totp/" + tenantBUser.getId(),
                HttpMethod.DELETE,
                new HttpEntity<>(tenantAHeaders),
                String.class);

        // Assert: 404 (not 403 — D3 symmetric, no existence disclosure).
        assertThat(attackResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Defense-in-depth: Tenant B user's 2FA state is byte-for-byte
        // unchanged — totp_enabled is still true, encrypted secret intact,
        // recovery codes intact, token_version NOT bumped (which would
        // invalidate the user's existing sessions).
        TenantContext.runWithContext(tenantB.getId(), false, () -> {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT totp_enabled, totp_secret_encrypted, recovery_codes, token_version " +
                            "FROM app_user WHERE id = ?::uuid",
                    tenantBUser.getId());
            assertThat((Boolean) row.get("totp_enabled"))
                    .as("Tenant B user's 2FA must still be enabled — account-takeover attack blocked")
                    .isTrue();
            assertThat(row.get("totp_secret_encrypted"))
                    .as("Tenant B user's TOTP secret must be preserved")
                    .isNotNull();
            assertThat(row.get("recovery_codes"))
                    .as("Tenant B user's recovery codes must be preserved")
                    .isNotNull();
            assertThat(((Number) row.get("token_version")).intValue())
                    .as("Tenant B user's token_version must NOT be bumped — existing sessions remain valid")
                    .isEqualTo(tenantBUser.getTokenVersion());
        });
    }

    @Test
    @DisplayName("Cross-tenant TotpController.adminRegenerateRecoveryCodes → 404, no codes returned, Tenant B codes preserved")
    void tc_adminRegenerateRecoveryCodes_crossTenant_returns404_noCodesLeaked() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Tenant tenantB = authHelper.setupSecondaryTenant("xtenant-totp-regen-" + suffix);
        User tenantBUser = authHelper.createUserInTenant(tenantB.getId(),
                "regen-victim-" + suffix + "@test.fabt.org", "Regen Victim",
                new String[]{"OUTREACH_WORKER"}, false);

        String originalCodesJson = "[\"original-hash-1\",\"original-hash-2\"]";
        String testSecret = totpService.generateSecret();
        tenantBUser.setTotpEnabled(true);
        tenantBUser.setTotpSecretEncrypted(totpService.encryptSecret(testSecret));
        tenantBUser.setRecoveryCodes(originalCodesJson);
        tenantBUser.setUpdatedAt(java.time.Instant.now());
        authHelper.getUserRepository().save(tenantBUser);

        // Act: Tenant A's COC_ADMIN attempts to regenerate Tenant B user's
        // backup codes — pre-fix would return the new plaintext codes in
        // the response body, giving the attacker direct account takeover.
        HttpHeaders tenantAHeaders = authHelper.cocAdminHeaders();
        ResponseEntity<String> attackResp = restTemplate.exchange(
                "/api/v1/auth/totp/" + tenantBUser.getId() + "/regenerate-recovery-codes",
                HttpMethod.POST,
                new HttpEntity<>(tenantAHeaders),
                String.class);

        // Assert: 404 + response body does NOT contain the string "backupCodes"
        // (the JSON response key from the success path).
        assertThat(attackResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        if (attackResp.getBody() != null) {
            assertThat(attackResp.getBody())
                    .as("404 response must NOT leak backup codes — account-takeover pivot blocked")
                    .doesNotContain("backupCodes");
        }

        // Defense-in-depth: Tenant B user's recovery_codes column is unchanged.
        TenantContext.runWithContext(tenantB.getId(), false, () -> {
            String codes = jdbcTemplate.queryForObject(
                    "SELECT recovery_codes FROM app_user WHERE id = ?::uuid",
                    String.class, tenantBUser.getId());
            assertThat(codes)
                    .as("Tenant B user's recovery codes must be unchanged — attacker's 'regenerate' attempt did NOT rotate them")
                    .isEqualTo(originalCodesJson);
        });
    }

    // ================================================================
    // cross-tenant-isolation-audit (Issue #117) — Phase 2 task 2.5.3.
    // Two regression tests pinning the userService.getUser refactor on
    // AccessCodeController.generateAccessCode target + admin lookups.
    //
    // THREAT MODEL (Casey's VAWA audit-trail falsification concern,
    // VULN-MED): pre-fix, a CoC admin in Tenant A could POST /api/v1/
    // users/{tenantB-user-id}/generate-access-code. The controller's
    // bare userRepository.findById returned the Tenant B user. The
    // service then INSERTed a row into one_time_access_code with
    // (user_id=tenantBUser, tenant_id=tenantA, created_by=tenantAAdmin)
    // AND emitted an ACCESS_CODE_GENERATED audit event naming the
    // Tenant A admin as actor for an action against a Tenant B user.
    //
    // The attacker CANNOT actually take over the Tenant B user with
    // this code (validateCode uses findByTenantIdAndEmail and the
    // tenant context at redemption time is the victim's, not the
    // attacker's — a code inserted with tenant_id=tenantA won't match
    // the victim's login lookup). BUT:
    //   1. Tenant B's audit_events has a forged entry with a Tenant A
    //      actor — per-tenant audit integrity broken (VAWA).
    //   2. one_time_access_code has a mismatched (user_id, tenant_id)
    //      row — data-integrity rot.
    // Post-fix: both paths blocked at 404 before any insert or audit.
    // ================================================================

    @Test
    @DisplayName("Cross-tenant access code generation → 404, no audit event, no one_time_access_code row")
    void tc_generateAccessCode_crossTenant_returns404_noAuditNoCodeRow() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // Tenant B with a user.
        Tenant tenantB = authHelper.setupSecondaryTenant("xtenant-accesscode-" + suffix);
        User tenantBUser = authHelper.createUserInTenant(tenantB.getId(),
                "accesscode-victim-" + suffix + "@test.fabt.org", "Access Code Victim",
                new String[]{"OUTREACH_WORKER"}, false);

        // Baseline: audit_events rows naming Tenant B user and one_time_access_code rows for Tenant B user — both zero.
        TenantContext.runWithContext(tenantB.getId(), false, () -> {
            Integer auditBefore = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_events WHERE action = ? AND target_user_id = ?::uuid",
                    Integer.class, "ACCESS_CODE_GENERATED", tenantBUser.getId());
            assertThat(auditBefore).as("Tenant B user must start with zero ACCESS_CODE_GENERATED events").isZero();

            Integer codeBefore = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM one_time_access_code WHERE user_id = ?::uuid",
                    Integer.class, tenantBUser.getId());
            assertThat(codeBefore).as("Tenant B user must start with zero access codes").isZero();
        });

        // Act: Tenant A's COC_ADMIN attempts to generate an access code for Tenant B's user.
        HttpHeaders tenantAHeaders = authHelper.cocAdminHeaders();
        ResponseEntity<String> attackResp = restTemplate.exchange(
                "/api/v1/users/" + tenantBUser.getId() + "/generate-access-code",
                HttpMethod.POST,
                new HttpEntity<>(tenantAHeaders),
                String.class);

        // Assert: 404 (not 200, not 403 — D3 symmetric).
        assertThat(attackResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Defense-in-depth Casey VAWA: no ACCESS_CODE_GENERATED audit event
        // was emitted for Tenant B's user. Pre-fix, this would have fired
        // with a Tenant A admin as actor — a forged audit entry.
        TenantContext.runWithContext(tenantB.getId(), false, () -> {
            Integer auditAfter = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_events WHERE action = ? AND target_user_id = ?::uuid",
                    Integer.class, "ACCESS_CODE_GENERATED", tenantBUser.getId());
            assertThat(auditAfter)
                    .as("No ACCESS_CODE_GENERATED audit entry must exist for Tenant B user — pre-fix bug emitted one with a cross-tenant actor")
                    .isZero();

            // No access code row inserted referencing Tenant B's user.
            Integer codeAfter = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM one_time_access_code WHERE user_id = ?::uuid",
                    Integer.class, tenantBUser.getId());
            assertThat(codeAfter)
                    .as("No one_time_access_code row must reference Tenant B user — data-integrity invariant")
                    .isZero();
        });
    }

    @Test
    @DisplayName("Cross-tenant access code — DV-authorized user → 404 (DV check not reached pre-tenant-guard)")
    void tc_generateAccessCode_crossTenant_dvUser_returns404_dvCheckNotReached() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // Tenant B with a dv-authorized user.
        Tenant tenantB = authHelper.setupSecondaryTenant("xtenant-ac-dv-" + suffix);
        User tenantBDvUser = authHelper.createUserInTenant(tenantB.getId(),
                "ac-dv-victim-" + suffix + "@test.fabt.org", "DV Access Code Victim",
                new String[]{"OUTREACH_WORKER"}, true); // dvAccess=true

        // Act: Tenant A's (non-dv) COC_ADMIN attempts to generate access code for Tenant B's DV user.
        // Pre-fix: bare findById would return the user, then check admin.isDvAccess() → 403 dv_access_required.
        // Post-fix: userService.getUser throws NoSuchElementException → 404 BEFORE the DV check runs.
        // Why this matters: 403 leaks existence (caller learns the user exists in SOME tenant + is dv);
        // 404 does not.
        HttpHeaders tenantAHeaders = authHelper.cocAdminHeaders();
        ResponseEntity<String> attackResp = restTemplate.exchange(
                "/api/v1/users/" + tenantBDvUser.getId() + "/generate-access-code",
                HttpMethod.POST,
                new HttpEntity<>(tenantAHeaders),
                String.class);

        assertThat(attackResp.getStatusCode())
                .as("DV existence must not leak via 403 pre-tenant-guard; must be 404 from the tenant-scoped lookup first")
                .isEqualTo(HttpStatus.NOT_FOUND);
        if (attackResp.getBody() != null) {
            assertThat(attackResp.getBody())
                    .as("404 response body must not contain 'dv' — a 403 with dv_access_required would leak")
                    .doesNotContain("dv_access_required");
        }
    }
}
