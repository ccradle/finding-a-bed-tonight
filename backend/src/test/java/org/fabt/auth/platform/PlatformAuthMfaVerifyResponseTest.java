package org.fabt.auth.platform;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.Application;
import org.fabt.BaseIntegrationTest;
import org.fabt.auth.service.PasswordService;
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
 * Integration tests for the {@code POST /api/v1/auth/platform/login/mfa-verify}
 * response-shape contract introduced in warroom round 6 A1.
 *
 * <p>The F11 PlatformMfaVerify SPA needs to render distinct copy for
 * "wrong code, N attempts remaining" vs "account locked, do not retry."
 * This test asserts the wire contract:
 *
 * <ul>
 *   <li>Wrong code, attempts remaining → 401 with body
 *       {@code {error: "invalid_mfa_code", attemptsRemaining: N}}</li>
 *   <li>Wrong code that triggers lockout → 401 with body
 *       {@code {error: "account_locked"}} (no attemptsRemaining field)</li>
 *   <li>Subsequent attempt while locked → 401 with
 *       {@code {error: "account_locked"}} consistently</li>
 *   <li>Successful verification → 200 with token + expiresInSeconds</li>
 * </ul>
 */
@DisplayName("PlatformAuthController POST /login/mfa-verify response shape")
@SpringBootTest(classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformAuthMfaVerifyResponseTest extends BaseIntegrationTest {

    private static final UUID BOOTSTRAP_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000fab");
    private static final String OPERATOR_EMAIL = "ops-mfa-verify-it@example.com";
    private static final String OPERATOR_PASSWORD = "InitialPassword!@#$1234";
    private static final int LOCKOUT_THRESHOLD = 5;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordService passwordService;

    @BeforeEach
    void restoreBootstrap() {
        jdbc.queryForObject("SELECT platform_user_reset_to_bootstrap(?::uuid)",
                Boolean.class, BOOTSTRAP_ID);
        jdbc.queryForObject(
                "SELECT platform_user_update_credentials(?::uuid, ?, NULL, NULL, ?)",
                Object.class, BOOTSTRAP_ID,
                passwordService.hash(OPERATOR_PASSWORD), false);
        jdbc.queryForObject("SELECT platform_user_set_email(?::uuid, ?)",
                Object.class, BOOTSTRAP_ID, OPERATOR_EMAIL);
    }

    @AfterEach
    void resetForNextSuite() {
        jdbc.queryForObject("SELECT platform_user_reset_to_bootstrap(?::uuid)",
                Boolean.class, BOOTSTRAP_ID);
    }

    @Test
    @DisplayName("wrong code with attempts remaining → 401 + invalid_mfa_code + attemptsRemaining=N")
    void wrongCodeReturnsAttemptsRemaining() {
        enroll();
        // Single login to mint an mfa-verify-scoped token; reused across
        // wrong-code attempts to avoid hitting the 5/15min platform-login
        // rate limit (application.yml `rate-limit-platform-login`).
        String verifyToken = mintMfaVerifyToken();

        ResponseEntity<Map> verifyResp = postMfaVerifyWithToken(verifyToken, "000000");

        assertThat(verifyResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        Map<String, Object> body = verifyResp.getBody();
        assertThat(body.get("error")).isEqualTo("invalid_mfa_code");
        // First failure → 1 attempt used → attemptsRemaining = LOCKOUT_THRESHOLD - 1 = 4
        assertThat(body.get("attemptsRemaining")).isEqualTo(LOCKOUT_THRESHOLD - 1);
    }

    @Test
    @DisplayName("Nth wrong code that triggers lockout → 401 + account_locked (no attemptsRemaining)")
    void lockoutTransitionReturnsAccountLocked() {
        enroll();
        String verifyToken = mintMfaVerifyToken();

        // Submit LOCKOUT_THRESHOLD wrong codes with the same verify token.
        // verifyMfa doesn't invalidate the scoped token on failure (it just
        // increments the per-account failure counter); the same token works
        // through the lockout transition. The Nth attempt triggers the
        // lockout state.
        ResponseEntity<Map> last = null;
        for (int i = 0; i < LOCKOUT_THRESHOLD; i++) {
            last = postMfaVerifyWithToken(verifyToken, "000000");
        }

        assertThat(last.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        Map<String, Object> body = last.getBody();
        assertThat(body.get("error")).isEqualTo("account_locked");
        // Locked-out body must NOT include attemptsRemaining (the SPA's
        // branch is purely on the error string in this case).
        assertThat(body).doesNotContainKey("attemptsRemaining");
    }

    @Test
    @DisplayName("attempt while already-locked → 401 + account_locked (idempotent)")
    void alreadyLockedReturnsAccountLocked() {
        enroll();
        String verifyToken = mintMfaVerifyToken();

        // Fire enough failures to trigger lockout
        for (int i = 0; i < LOCKOUT_THRESHOLD; i++) {
            postMfaVerifyWithToken(verifyToken, "000000");
        }
        // Now try ONE more attempt with the same verify token — should
        // still see account_locked (idempotent).
        ResponseEntity<Map> resp = postMfaVerifyWithToken(verifyToken, "000000");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody().get("error")).isEqualTo("account_locked");
    }

    @Test
    @DisplayName("successful verify → 200 + token, no error fields")
    void successfulVerifyReturnsToken() {
        EnrolledOperator op = enroll();

        // After enroll, the operator has a TOTP secret. Trigger a fresh
        // login → mfa-verify-scoped token, then verify with a valid code.
        ResponseEntity<Map> login = restTemplate.postForEntity(
                "/api/v1/auth/platform/login",
                Map.of("email", OPERATOR_EMAIL, "password", OPERATOR_PASSWORD),
                Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String verifyToken = (String) login.getBody().get("token");

        // Generate a fresh TOTP code (advance the time bucket by 1 to
        // avoid the replay-protection window from any prior use during
        // enrollment).
        String validCode = currentTotpCode(op.secret(), 1);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(verifyToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = restTemplate.exchange(
                "/api/v1/auth/platform/login/mfa-verify",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("code", validCode), headers),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = resp.getBody();
        assertThat(body.get("token")).isNotNull();
        assertThat(body.get("expiresInSeconds")).isNotNull();
        assertThat(body).doesNotContainKey("error");
        assertThat(body).doesNotContainKey("attemptsRemaining");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private EnrolledOperator enroll() {
        ResponseEntity<Map> login = restTemplate.postForEntity(
                "/api/v1/auth/platform/login",
                Map.of("email", OPERATOR_EMAIL, "password", OPERATOR_PASSWORD),
                Map.class);
        String setupToken = (String) login.getBody().get("token");

        HttpHeaders setupHeaders = new HttpHeaders();
        setupHeaders.setBearerAuth(setupToken);
        ResponseEntity<Map> setup = restTemplate.exchange(
                "/api/v1/auth/platform/mfa-setup",
                HttpMethod.POST,
                new HttpEntity<>(null, setupHeaders),
                Map.class);
        String secret = (String) setup.getBody().get("secret");
        @SuppressWarnings("unchecked")
        List<String> backupCodes = (List<String>) setup.getBody().get("backupCodes");

        HttpHeaders confirmHeaders = new HttpHeaders();
        confirmHeaders.setBearerAuth(setupToken);
        confirmHeaders.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.exchange(
                "/api/v1/auth/platform/mfa-confirm",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("code", currentTotpCode(secret, 0)), confirmHeaders),
                Map.class);

        return new EnrolledOperator(secret, backupCodes);
    }

    /**
     * Logs in and returns the mfa-verify-scoped token. Used once per test
     * to avoid the 5/15min platform-login per-IP rate limit.
     */
    private String mintMfaVerifyToken() {
        ResponseEntity<Map> login = restTemplate.postForEntity(
                "/api/v1/auth/platform/login",
                Map.of("email", OPERATOR_EMAIL, "password", OPERATOR_PASSWORD),
                Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) login.getBody().get("token");
    }

    private ResponseEntity<Map> postMfaVerifyWithToken(String verifyToken, String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(verifyToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                "/api/v1/auth/platform/login/mfa-verify",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("code", code), headers),
                Map.class);
    }

    private String currentTotpCode(String secret, long bucketOffset) {
        try {
            CodeGenerator gen = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
            long timeBucket = new SystemTimeProvider().getTime() / 30 + bucketOffset;
            return gen.generate(secret, timeBucket);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private record EnrolledOperator(String secret, List<String> backupCodes) {
    }
}
