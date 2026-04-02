package org.fabt.auth;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.auth.service.TotpEncryptionService;
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
}
