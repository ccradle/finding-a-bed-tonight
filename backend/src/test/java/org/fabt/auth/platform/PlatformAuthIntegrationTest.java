package org.fabt.auth.platform;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.Application;
import org.fabt.BaseIntegrationTest;
import org.fabt.auth.service.PasswordService;
import org.fabt.auth.service.TotpService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.time.SystemTimeProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the platform-operator auth flow
 * (G-4.2 task 3.12). Spins up the full Spring Boot context against the
 * Testcontainers postgres, activates the V87 bootstrap row to a known
 * email + password, and exercises the four endpoints + iss-routed
 * SecurityConfig dispatch via {@link org.springframework.boot.test.web.client.TestRestTemplate}.
 *
 * <p>Bootstrap activation in this test uses the V88 SECURITY DEFINER
 * functions {@code platform_user_set_email} +
 * {@code platform_user_update_credentials} — same primitives the future
 * Phase H+ admin tooling will use. fabt_app cannot direct-UPDATE
 * {@code platform_user} so the function path is the only test ingress.
 *
 * <p>Test isolation: each test re-activates the bootstrap row from the
 * V87 state (NULL email, NULL password_hash, account_locked=true,
 * mfa_enabled=false). {@code @AfterEach}-style cleanup happens via the
 * {@code restoreBootstrap} helper called at the start of each method
 * (idempotent — re-applies known state regardless of prior test).
 */
@DisplayName("PlatformAuth — end-to-end via TestRestTemplate")
@SpringBootTest(classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformAuthIntegrationTest extends BaseIntegrationTest {

    private static final UUID BOOTSTRAP_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000fab");
    private static final String OPERATOR_EMAIL = "ops-it@example.com";
    private static final String OPERATOR_PASSWORD = "InitialPassword!@#$1234";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordService passwordService;
    @Autowired private TotpService totpService;

    @BeforeEach
    void restoreBootstrap() {
        // First fully reset the bootstrap row (clears creds, MFA, lockout
        // fields, backup codes — drops any leftover state from a previous
        // test in this class OR another platform-test class).
        jdbc.queryForObject("SELECT platform_user_reset_to_bootstrap(?::uuid)",
                Boolean.class, BOOTSTRAP_ID);
        // Then activate to (email, password, unlocked, mfa_enabled=false)
        // — the pre-state every test in this class assumes.
        jdbc.queryForObject(
                "SELECT platform_user_update_credentials(?::uuid, ?, NULL, NULL, ?)",
                Object.class, BOOTSTRAP_ID,
                passwordService.hash(OPERATOR_PASSWORD),
                false);                     // account_locked = false
        jdbc.queryForObject("SELECT platform_user_set_email(?::uuid, ?)",
                Object.class, BOOTSTRAP_ID, OPERATOR_EMAIL);
    }

    @AfterEach
    void resetForNextSuite() {
        // Fully reset on exit so V87/V88 schema-invariant tests in the
        // shared Spring context see bootstrap state regardless of run
        // order.
        jdbc.queryForObject("SELECT platform_user_reset_to_bootstrap(?::uuid)",
                Boolean.class, BOOTSTRAP_ID);
    }

    /**
     * Helper: end-to-end MFA enrollment for the bootstrap operator.
     * Returns the plaintext TOTP secret + the issued access token.
     * Used by tests that need a post-MFA-enrolled state.
     */
    private EnrolledOperator enroll() {
        // Step 1: login → mfa-setup-required scoped token
        ResponseEntity<Map> login = restTemplate.postForEntity(
                "/api/v1/auth/platform/login",
                Map.of("email", OPERATOR_EMAIL, "password", OPERATOR_PASSWORD),
                Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> loginBody = login.getBody();
        assertThat(loginBody.get("scope")).isEqualTo(PlatformJwtService.SCOPE_MFA_SETUP);
        String setupToken = (String) loginBody.get("token");

        // Step 2: mfa-setup with the scoped token → secret + 10 codes
        HttpHeaders setupHeaders = new HttpHeaders();
        setupHeaders.setBearerAuth(setupToken);
        ResponseEntity<Map> setup = restTemplate.exchange(
                "/api/v1/auth/platform/mfa-setup",
                HttpMethod.POST,
                new HttpEntity<>(null, setupHeaders),
                Map.class);
        assertThat(setup.getStatusCode()).isEqualTo(HttpStatus.OK);
        String secret = (String) setup.getBody().get("secret");
        assertThat(secret).isNotBlank();
        @SuppressWarnings("unchecked")
        List<String> backupCodes = (List<String>) setup.getBody().get("backupCodes");
        assertThat(backupCodes).hasSize(10);

        // Step 3: mfa-confirm with a valid TOTP code
        String totpCode = currentTotpCode(secret);
        HttpHeaders confirmHeaders = new HttpHeaders();
        confirmHeaders.setBearerAuth(setupToken);
        confirmHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> confirm = restTemplate.exchange(
                "/api/v1/auth/platform/mfa-confirm",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("code", totpCode), confirmHeaders),
                Map.class);
        assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.OK);
        String accessToken = (String) confirm.getBody().get("token");
        assertThat(accessToken).isNotBlank();

        return new EnrolledOperator(secret, accessToken, backupCodes);
    }

    private record EnrolledOperator(String secret, String accessToken, List<String> backupCodes) {
    }

    private String currentTotpCode(String secret) {
        try {
            CodeGenerator gen = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
            long timeBucket = new SystemTimeProvider().getTime() / 30;
            return gen.generate(secret, timeBucket);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------
    // Login outcomes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("login with bad password returns 401 invalid_credentials (no email-vs-password leak)")
    void loginBadPasswordReturns401() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/auth/platform/login",
                Map.of("email", OPERATOR_EMAIL, "password", "wrong"),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("error")).isEqualTo("invalid_credentials");
    }

    @Test
    @DisplayName("login with correct password and not-enrolled returns mfa-setup scoped token")
    void loginNotEnrolledReturnsSetupToken() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/auth/platform/login",
                Map.of("email", OPERATOR_EMAIL, "password", OPERATOR_PASSWORD),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("scope"))
                .isEqualTo(PlatformJwtService.SCOPE_MFA_SETUP);
        assertThat(response.getBody().get("token")).isNotNull();
        assertThat(response.getBody().get("expiresInSeconds")).isEqualTo(600);
    }

    // ------------------------------------------------------------------
    // Forced-MFA-on-first-login flow
    // ------------------------------------------------------------------

    @Test
    @DisplayName("full enrollment flow: login → mfa-setup → mfa-confirm → access token issued")
    void fullEnrollmentFlow() {
        EnrolledOperator op = enroll();
        assertThat(op.accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("scope mismatch: mfa-setup token presented to mfa-verify endpoint → 401")
    void scopeServerValidated() {
        // Get an mfa-setup token (operator not yet enrolled)
        ResponseEntity<Map> login = restTemplate.postForEntity(
                "/api/v1/auth/platform/login",
                Map.of("email", OPERATOR_EMAIL, "password", OPERATOR_PASSWORD),
                Map.class);
        String setupToken = (String) login.getBody().get("token");

        // Present that mfa-setup token to /login/mfa-verify (wrong scope).
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(setupToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/auth/platform/login/mfa-verify",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("code", "123456"), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("error")).isEqualTo("invalid_platform_token");
        // M6: response must NOT carry "message" detail (warroom)
        assertThat(response.getBody()).doesNotContainKey("message");
    }

    @Test
    @DisplayName("login after enrollment returns mfa-verify scoped token (5-min TTL)")
    void loginAfterEnrollmentReturnsVerifyToken() {
        enroll(); // operator now mfa_enabled=true

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/auth/platform/login",
                Map.of("email", OPERATOR_EMAIL, "password", OPERATOR_PASSWORD),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("scope"))
                .isEqualTo(PlatformJwtService.SCOPE_MFA_VERIFY);
        assertThat(response.getBody().get("expiresInSeconds")).isEqualTo(300);
    }

    @Test
    @DisplayName("subsequent login + valid TOTP via /login/mfa-verify issues full access token")
    void subsequentLoginViaMfaVerifyIssuesAccessToken() {
        EnrolledOperator op = enroll();

        // Fresh login
        ResponseEntity<Map> login = restTemplate.postForEntity(
                "/api/v1/auth/platform/login",
                Map.of("email", OPERATOR_EMAIL, "password", OPERATOR_PASSWORD),
                Map.class);
        String verifyToken = (String) login.getBody().get("token");

        // Wait a TOTP step so we don't replay the enrollment-confirm code
        // (M1 replay protection). 30-sec step + ±1; sleep 31s to land in a
        // new TOTP window.
        try {
            Thread.sleep(31_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String fresh = currentTotpCode(op.secret());

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(verifyToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> verify = restTemplate.exchange(
                "/api/v1/auth/platform/login/mfa-verify",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("code", fresh), headers),
                Map.class);

        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verify.getBody().get("token")).isNotNull();
    }

    @Test
    @DisplayName("backup code consumes a row and issues access token")
    void backupCodeWorks() {
        EnrolledOperator op = enroll();

        // Login → mfa-verify token
        ResponseEntity<Map> login = restTemplate.postForEntity(
                "/api/v1/auth/platform/login",
                Map.of("email", OPERATOR_EMAIL, "password", OPERATOR_PASSWORD),
                Map.class);
        String verifyToken = (String) login.getBody().get("token");

        // Use the first backup code
        String backupCode = op.backupCodes().get(0);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(verifyToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> verify = restTemplate.exchange(
                "/api/v1/auth/platform/login/mfa-verify",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("code", backupCode), headers),
                Map.class);

        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verify.getBody().get("token")).isNotNull();
    }
}
