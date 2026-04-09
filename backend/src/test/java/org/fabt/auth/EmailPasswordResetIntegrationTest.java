package org.fabt.auth;

import java.util.Map;
import java.util.UUID;

import jakarta.mail.internet.MimeMessage;
import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.auth.service.PasswordResetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for email-based password reset.
 * Uses GreenMail embedded SMTP (configured in BaseIntegrationTest).
 */
class EmailPasswordResetIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordResetService passwordResetService;

    private User testUser;
    /** Baseline message count — tests check messages received AFTER this point */
    private int greenMailBaseline;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        // setupUserWithDvAccess is used as a generic factory (accepts custom email/name/roles).
        // dvAccess is overridden to false — this user represents a standard outreach worker,
        // NOT a DV user. DV-specific tests create their own users with dvAccess=true.
        testUser = authHelper.setupUserWithDvAccess(
                "resettest@test.fabt.org", "Reset Test User",
                new String[]{"OUTREACH_WORKER"});
        testUser.setDvAccess(false);
        authHelper.getUserRepository().save(testUser);

        // Clean up prior reset tokens
        jdbcTemplate.update("DELETE FROM password_reset_token WHERE user_id = ?", testUser.getId());
        // GreenMail is shared across all test classes (started once in BaseIntegrationTest).
        // We don't reset it — instead track messages relative to baseline.
        // Pattern: withPerMethodLifecycle(false) equivalent via static initializer.
        // Source: https://tech.asimio.net/2023/10/13/GreenMail-Jsoup-Spring-Boot-2-Integration-Tests-Emails.html
        greenMailBaseline = GREEN_MAIL.getReceivedMessages().length;
    }

    /** Get messages received SINCE the @BeforeEach baseline */
    private MimeMessage[] newMessages() {
        MimeMessage[] all = GREEN_MAIL.getReceivedMessages();
        int newCount = all.length - greenMailBaseline;
        if (newCount <= 0) return new MimeMessage[0];
        MimeMessage[] recent = new MimeMessage[newCount];
        System.arraycopy(all, greenMailBaseline, recent, 0, newCount);
        return recent;
    }

    // =========================================================================
    // ER-11: Happy path
    // =========================================================================

    @Test
    @DisplayName("ER-11: Happy path — request reset, validate token, password changed, tokenVersion incremented")
    void happyPath_resetChangesPasswordAndInvalidatesJwts() throws Exception {
        // Get a valid JWT before reset
        HttpHeaders beforeHeaders = authHelper.headersForUser(testUser);
        int originalTokenVersion = testUser.getTokenVersion();

        // Request reset
        passwordResetService.requestReset("resettest@test.fabt.org", authHelper.getTestTenantSlug());

        // ER-33: Verify GreenMail received email
        assertThat(newMessages()).hasSize(1);
        MimeMessage email = newMessages()[0];
        assertThat(email.getSubject()).isEqualTo("Password Reset Request");
        assertThat(email.getAllRecipients()[0].toString()).isEqualTo("resettest@test.fabt.org");
        String body = email.getContent().toString();
        assertThat(body).contains("/login/reset-password?token=");
        assertThat(body).doesNotContain("Finding A Bed Tonight");
        assertThat(body).doesNotContain("shelter");
        assertThat(body).doesNotContain("bed");

        // Extract token from email body
        String token = extractTokenFromEmail(body);

        // ER-21: Verify token stored as SHA-256 hex format (not plaintext, not BCrypt)
        // Behavior verification only — SHA-256 algorithm correctness tested in unit test
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT token_hash FROM password_reset_token WHERE user_id = ?",
                String.class, testUser.getId());
        assertThat(storedHash).matches("[a-f0-9]{64}");   // SHA-256 hex: exactly 64 lowercase hex chars
        assertThat(storedHash).doesNotStartWith("$2");      // NOT BCrypt
        assertThat(storedHash).hasSize(64);                  // NOT plaintext (URL-safe base64 is ~43 chars)
        assertThat(storedHash).isNotEqualTo(token);          // NOT the raw token

        // Reset password
        boolean success = passwordResetService.resetPassword(token, "NewSecurePass12!");
        assertThat(success).isTrue();

        // Verify tokenVersion incremented
        User updated = authHelper.getUserRepository().findById(testUser.getId()).orElseThrow();
        assertThat(updated.getTokenVersion()).isGreaterThan(originalTokenVersion);

        // Old JWT should be rejected (tokenVersion mismatch)
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/queries/beds", HttpMethod.POST,
                new HttpEntity<>("{}", beforeHeaders), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // ER-12: Expired token
    // =========================================================================

    @Test
    @DisplayName("ER-12: Expired token (31 min) rejected")
    void expiredToken_rejected() {
        passwordResetService.requestReset("resettest@test.fabt.org", authHelper.getTestTenantSlug());
        String token = extractTokenFromGreenMail();

        // Backdate the token to 31 minutes ago
        jdbcTemplate.update(
                "UPDATE password_reset_token SET expires_at = NOW() - INTERVAL '31 minutes' WHERE user_id = ?",
                testUser.getId());

        boolean success = passwordResetService.resetPassword(token, "NewSecurePass12!");
        assertThat(success).isFalse();
    }

    // =========================================================================
    // ER-13: Used token
    // =========================================================================

    @Test
    @DisplayName("ER-13: Used token (already consumed) rejected")
    void usedToken_rejected() {
        passwordResetService.requestReset("resettest@test.fabt.org", authHelper.getTestTenantSlug());
        String token = extractTokenFromGreenMail();

        // First use succeeds
        assertThat(passwordResetService.resetPassword(token, "NewSecurePass12!")).isTrue();

        // Second use fails (single-use)
        assertThat(passwordResetService.resetPassword(token, "AnotherPass1234!")).isFalse();
    }

    // =========================================================================
    // ER-14: Invalid token
    // =========================================================================

    @Test
    @DisplayName("ER-14: Invalid/random token rejected")
    void invalidToken_rejected() {
        boolean success = passwordResetService.resetPassword(
                "not-a-real-token-at-all-" + UUID.randomUUID(), "NewSecurePass12!");
        assertThat(success).isFalse();
    }

    // =========================================================================
    // ER-15: Non-existent email (enumeration prevention)
    // =========================================================================

    @Test
    @DisplayName("ER-15: Non-existent email returns 200, no token, no email")
    void nonExistentEmail_silentSuccess() {
        String body = """
                {"email": "nobody@test.fabt.org", "tenantSlug": "%s"}
                """.formatted(authHelper.getTestTenantSlug());

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/forgot-password",
                new HttpEntity<>(body, jsonHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("If the email exists");
        assertThat(newMessages()).isEmpty();
    }

    // =========================================================================
    // ER-16: DV user blocked
    // =========================================================================

    @Test
    @DisplayName("ER-16: DV user — 200 same message, no token, no email (via HTTP, consistent with ER-15)")
    void dvUser_silentlyBlocked() {
        // Create a DV user (dvAccess=true by default from setupUserWithDvAccess)
        User dvUser = authHelper.setupUserWithDvAccess(
                "dv-resettest@test.fabt.org", "DV Reset User",
                new String[]{"OUTREACH_WORKER"});

        // Test via HTTP endpoint — same pattern as ER-15 for consistency
        String body = """
                {"email": "dv-resettest@test.fabt.org", "tenantSlug": "%s"}
                """.formatted(authHelper.getTestTenantSlug());

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/forgot-password",
                new HttpEntity<>(body, jsonHeaders()), String.class);

        // Same 200 response as non-existent email — no enumeration
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("If the email exists");

        // ER-34: No email sent
        assertThat(newMessages()).isEmpty();

        // No token in DB
        int tokenCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM password_reset_token WHERE user_id = ?",
                Integer.class, dvUser.getId());
        assertThat(tokenCount).isEqualTo(0);
    }

    // =========================================================================
    // ER-17: TOTP intact after reset
    // =========================================================================

    @Test
    @DisplayName("ER-17: TOTP user — password reset does not disable 2FA")
    void totpUser_mfaIntactAfterReset() {
        // Enable TOTP for the user. Set both flag AND a dummy encrypted secret so the
        // test remains valid if future code adds validation (totpEnabled requires secret).
        // TotpService enrollment flow is tested separately in TotpAndAccessCodeIntegrationTest.
        testUser.setTotpEnabled(true);
        testUser.setTotpSecretEncrypted("test-dummy-encrypted-secret");
        authHelper.getUserRepository().save(testUser);

        passwordResetService.requestReset("resettest@test.fabt.org", authHelper.getTestTenantSlug());
        String token = extractTokenFromGreenMail();

        passwordResetService.resetPassword(token, "NewSecurePass12!");

        // Verify TOTP is still enabled
        User updated = authHelper.getUserRepository().findById(testUser.getId()).orElseThrow();
        assertThat(updated.isTotpEnabled()).isTrue();

        // Login with new password should require MFA
        String loginBody = """
                {"email": "resettest@test.fabt.org", "password": "NewSecurePass12!", "tenantSlug": "%s"}
                """.formatted(authHelper.getTestTenantSlug());

        ResponseEntity<String> loginResponse = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new HttpEntity<>(loginBody, jsonHeaders()), String.class);

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody()).contains("mfaRequired");
        assertThat(loginResponse.getBody()).contains("true");
    }

    // =========================================================================
    // ER-18: SMTP not configured (tested via controller — SMTP guard)
    // =========================================================================

    // Note: SMTP IS configured in tests (GreenMail). The SMTP-not-configured path
    // is verified by the AuthController stub which checks mailHost before calling
    // the service. Testing this would require a separate Spring context without
    // spring.mail.host — impractical. The logic is trivial (early return + 200).

    // =========================================================================
    // ER-19 + ER-20: tokenVersion increment on PasswordController
    // =========================================================================

    @Test
    @DisplayName("ER-19: Self-service password change increments tokenVersion")
    void selfServicePasswordChange_incrementsTokenVersion() {
        // Dedicated user — not affected by happy path password changes
        User pwChangeUser = authHelper.setupUserWithDvAccess(
                "pwchange-test@test.fabt.org", "PW Change Test",
                new String[]{"OUTREACH_WORKER"});
        pwChangeUser.setDvAccess(false);
        authHelper.getUserRepository().save(pwChangeUser);
        int originalVersion = pwChangeUser.getTokenVersion();

        HttpHeaders headers = authHelper.headersForUser(pwChangeUser);
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = """
                {"currentPassword": "%s", "newPassword": "NewSelfServe12!"}
                """.formatted(TestAuthHelper.TEST_PASSWORD);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/password", HttpMethod.PUT,
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        User updated = authHelper.getUserRepository().findById(pwChangeUser.getId()).orElseThrow();
        assertThat(updated.getTokenVersion()).isGreaterThan(originalVersion);
    }

    @Test
    @DisplayName("ER-20: Admin password reset increments tokenVersion")
    void adminPasswordReset_incrementsTokenVersion() {
        // Dedicated user — not affected by other password changes
        User resetUser = authHelper.setupUserWithDvAccess(
                "adminreset-test@test.fabt.org", "Admin Reset Test",
                new String[]{"OUTREACH_WORKER"});
        resetUser.setDvAccess(false);
        authHelper.getUserRepository().save(resetUser);
        int originalVersion = resetUser.getTokenVersion();

        HttpHeaders adminHeaders = authHelper.adminHeaders();
        adminHeaders.setContentType(MediaType.APPLICATION_JSON);

        String body = """
                {"newPassword": "AdminReset123!"}
                """;

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/" + resetUser.getId() + "/reset-password",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        User updated = authHelper.getUserRepository().findById(resetUser.getId()).orElseThrow();
        assertThat(updated.getTokenVersion()).isGreaterThan(originalVersion);
    }

    // =========================================================================
    // ER-35: Enumeration timing
    // =========================================================================

    @Test
    @DisplayName("ER-35: Valid vs invalid email response times within 150ms")
    void enumerationTiming_consistentResponseTimes() {
        // Run 3 samples each to reduce GC/scheduling noise (Marcus: CI flakiness mitigation)
        long[] validTimes = new long[3];
        long[] invalidTimes = new long[3];

        for (int i = 0; i < 3; i++) {
            // Clean up tokens from previous iteration
            jdbcTemplate.update("DELETE FROM password_reset_token WHERE user_id = ?", testUser.getId());

            long start = System.nanoTime();
            passwordResetService.requestReset("resettest@test.fabt.org", authHelper.getTestTenantSlug());
            validTimes[i] = (System.nanoTime() - start) / 1_000_000;
            greenMailBaseline = GREEN_MAIL.getReceivedMessages().length;

            start = System.nanoTime();
            passwordResetService.requestReset("nobody@test.fabt.org", authHelper.getTestTenantSlug());
            invalidTimes[i] = (System.nanoTime() - start) / 1_000_000;
        }

        // Use median (index 1 after sort) to reduce outlier impact
        java.util.Arrays.sort(validTimes);
        java.util.Arrays.sort(invalidTimes);
        long validMedian = validTimes[1];
        long invalidMedian = invalidTimes[1];

        // Both should be >= 250ms (timing floor) and within 150ms of each other
        assertThat(validMedian).isGreaterThanOrEqualTo(240); // allow 10ms jitter
        assertThat(invalidMedian).isGreaterThanOrEqualTo(240);
        assertThat(Math.abs(validMedian - invalidMedian))
                .as("Timing difference between valid and invalid email should be < 150ms (median of 3 samples)")
                .isLessThan(150);
    }

    // =========================================================================
    // ER-36: Concurrent reset — new token invalidates previous
    // =========================================================================

    @Test
    @DisplayName("ER-36: New reset request invalidates previous unused token")
    void concurrentReset_previousTokenInvalidated() {
        // First request
        passwordResetService.requestReset("resettest@test.fabt.org", authHelper.getTestTenantSlug());
        String firstToken = extractTokenFromGreenMail();
        greenMailBaseline = GREEN_MAIL.getReceivedMessages().length;

        // Second request
        passwordResetService.requestReset("resettest@test.fabt.org", authHelper.getTestTenantSlug());
        String secondToken = extractTokenFromGreenMail();

        // First token should be invalidated (marked used)
        assertThat(passwordResetService.resetPassword(firstToken, "NewSecurePass12!")).isFalse();

        // Second token should work
        assertThat(passwordResetService.resetPassword(secondToken, "NewSecurePass12!")).isTrue();
    }

    // =========================================================================
    // ER-37: Password too short
    // =========================================================================

    @Test
    @DisplayName("ER-37: Reset with password < 12 chars returns 400")
    void shortPassword_rejected() {
        String body = """
                {"token": "some-token", "newPassword": "short"}
                """;

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/reset-password",
                new HttpEntity<>(body, jsonHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String extractTokenFromGreenMail() {
        MimeMessage[] messages = newMessages();
        assertThat(messages.length).as("Expected at least 1 new email in GreenMail").isGreaterThan(0);
        try {
            String body = messages[messages.length - 1].getContent().toString();
            return extractTokenFromEmail(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract token from GreenMail", e);
        }
    }

    private String extractTokenFromEmail(String emailBody) {
        int tokenStart = emailBody.indexOf("token=") + 6;
        int tokenEnd = emailBody.indexOf("\n", tokenStart);
        if (tokenEnd < 0) tokenEnd = emailBody.length();
        return emailBody.substring(tokenStart, tokenEnd).trim();
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
