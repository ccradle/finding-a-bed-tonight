package org.fabt.auth;

import java.util.Map;
import java.util.UUID;

import jakarta.mail.internet.MimeMessage;
import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.auth.service.PasswordResetService;
import org.fabt.auth.service.PasswordService;
import org.fabt.shared.web.TenantContext;
import org.fabt.tenant.domain.Tenant;
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

    @Autowired
    private PasswordService passwordService;

    private User testUser;

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

        // GreenMail mailboxes are purged in BaseIntegrationTest.@AfterEach — every
        // test starts with an empty inbox. Per GreenMail docs (canonical pattern,
        // see ExamplePurgeAllEmailsTest). The prior baseline-index pattern was
        // fragile: JUnit 5's default MethodOrderer is "deterministic but
        // intentionally non-obvious," so adding/removing methods could reshuffle
        // execution order and leak emails across tests.
    }

    /**
     * Wait for the next N message(s) to arrive, then return them. Race-safe
     * against Spring Boot 4's virtual-thread async delivery: GreenMail's
     * SMTP ack and the {@code getReceivedMessages()} index are asynchronously
     * coupled. Always call this before reading messages — never read
     * {@code getReceivedMessages()} directly without the wait.
     */
    private MimeMessage[] waitForMessages(int expectedCount) {
        boolean arrived = GREEN_MAIL.waitForIncomingEmail(2_000L, expectedCount);
        assertThat(arrived)
                .as("Expected %d email(s) to arrive within 2s", expectedCount)
                .isTrue();
        return GREEN_MAIL.getReceivedMessages();
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
        MimeMessage[] received = waitForMessages(1);
        assertThat(received).hasSize(1);
        MimeMessage email = received[0];
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
        // requestReset returns synchronously after timing padding; if no email
        // was sent, none will arrive later. Direct read is race-free here.
        assertThat(GREEN_MAIL.getReceivedMessages()).isEmpty();
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
        assertThat(GREEN_MAIL.getReceivedMessages()).isEmpty();

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
    void enumerationTiming_consistentResponseTimes() throws Exception {
        // Run 3 samples each to reduce GC/scheduling noise (Marcus: CI flakiness mitigation)
        long[] validTimes = new long[3];
        long[] invalidTimes = new long[3];

        for (int i = 0; i < 3; i++) {
            // Clean up tokens from previous iteration
            jdbcTemplate.update("DELETE FROM password_reset_token WHERE user_id = ?", testUser.getId());

            long start = System.nanoTime();
            passwordResetService.requestReset("resettest@test.fabt.org", authHelper.getTestTenantSlug());
            validTimes[i] = (System.nanoTime() - start) / 1_000_000;
            // Purge between iterations so each requestReset starts with an
            // empty inbox — keeps GreenMail from accumulating across the loop.
            GREEN_MAIL.purgeEmailFromAllMailboxes();

            start = System.nanoTime();
            passwordResetService.requestReset("nobody@test.fabt.org", authHelper.getTestTenantSlug());
            invalidTimes[i] = (System.nanoTime() - start) / 1_000_000;
            GREEN_MAIL.purgeEmailFromAllMailboxes();
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
    void concurrentReset_previousTokenInvalidated() throws Exception {
        // First request
        passwordResetService.requestReset("resettest@test.fabt.org", authHelper.getTestTenantSlug());
        String firstToken = extractTokenFromGreenMail();
        // Purge so the second request's email is the only one in the inbox —
        // the token-extraction helper reads "the latest message" and we need
        // to force isolation between the two requests.
        GREEN_MAIL.purgeEmailFromAllMailboxes();

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
        MimeMessage[] messages = waitForMessages(1);
        assertThat(messages.length).as("Expected at least 1 email in GreenMail").isGreaterThan(0);
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

    // ================================================================
    // Phase 2 closeout warroom (2026-04-15) — Marcus Webb action item:
    // pin the FK justification on PasswordResetService.requestReset /
    // resetPassword. The annotations claim password_reset_token is
    // tenant-scoped via app_user FK and that the request-time tenant
    // lookup (findByTenantIdAndEmail) prevents cross-tenant token
    // issuance. This test exercises the email-collision case (same
    // email in tenants A and B) to prove:
    //  (a) requesting reset for tenant B does NOT touch tenant A's
    //      user's tokens or password
    //  (b) resetting with tenant A's token does NOT touch tenant B's
    //      user's password
    // ================================================================

    @Test
    @DisplayName("Cross-tenant password reset via email collision → tokens and password resets stay tenant-isolated")
    void tc_resetPassword_emailCollidesAcrossTenants_resetIsolated() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String collidingEmail = "pwreset-collide-" + suffix + "@test.fabt.org";

        // Tenant A user — testUser already exists at "resettest@..." so create
        // a fresh user at the colliding email to keep noise out of GreenMail.
        UUID tenantAId = authHelper.getTestTenantId();
        User tenantAUser = authHelper.createUserInTenant(tenantAId,
                collidingEmail, "Tenant A Reset Collider",
                new String[]{"OUTREACH_WORKER"}, false);
        String tenantAOriginalHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM app_user WHERE id = ?",
                String.class, tenantAUser.getId());

        // Tenant B with the SAME EMAIL.
        Tenant tenantB = authHelper.setupSecondaryTenant("xtenant-pwreset-collide-" + suffix);
        User tenantBUser = authHelper.createUserInTenant(tenantB.getId(),
                collidingEmail, "Tenant B Reset Collider",
                new String[]{"OUTREACH_WORKER"}, false);
        String tenantBOriginalHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM app_user WHERE id = ?",
                String.class, tenantBUser.getId());
        String tenantBSlug = jdbcTemplate.queryForObject(
                "SELECT slug FROM tenant WHERE id = ?", String.class, tenantB.getId());

        // Step 1: request reset for the colliding email, scoped to tenant B.
        // Inbox is empty per BaseIntegrationTest's @AfterEach purge.
        passwordResetService.requestReset(collidingEmail, tenantBSlug);

        // Step 2: assert the reset token landed under tenant B's user, NOT tenant A's.
        Long tokenCountTenantA = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM password_reset_token WHERE user_id = ?",
                Long.class, tenantAUser.getId());
        Long tokenCountTenantB = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM password_reset_token WHERE user_id = ?",
                Long.class, tenantBUser.getId());
        assertThat(tokenCountTenantA)
                .as("Reset request for tenant B must NOT issue a token for tenant A's same-email user")
                .isZero();
        assertThat(tokenCountTenantB)
                .as("Reset request for tenant B must issue exactly one token for tenant B's user")
                .isEqualTo(1L);

        // Step 3: extract the token from the email sent to tenant B.
        try {
            MimeMessage[] all = waitForMessages(1);
            assertThat(all)
                    .as("Exactly one reset email must have been sent to tenant B")
                    .hasSize(1);
            String body = all[0].getContent().toString();
            String token = extractTokenFromEmail(body);

            // Step 4: complete the reset using tenant B's token.
            boolean success = passwordResetService.resetPassword(token, "NewSecurePass12!");
            assertThat(success).as("Tenant B's reset with tenant B's token must succeed").isTrue();
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract reset token from email", e);
        }

        // Step 5: assert tenant A's password hash is unchanged. The cross-
        // tenant attack vector — tenant B's reset bleeding into tenant A's
        // same-email user — does NOT happen.
        TenantContext.runWithContext(tenantAId, false, () -> {
            String tenantAFinalHash = jdbcTemplate.queryForObject(
                    "SELECT password_hash FROM app_user WHERE id = ?",
                    String.class, tenantAUser.getId());
            assertThat(tenantAFinalHash)
                    .as("Tenant A's same-email user MUST have unchanged password — cross-tenant reset bleed prevented")
                    .isEqualTo(tenantAOriginalHash);
        });

        // Step 6: assert tenant B's password actually changed (positive control).
        TenantContext.runWithContext(tenantB.getId(), false, () -> {
            String tenantBFinalHash = jdbcTemplate.queryForObject(
                    "SELECT password_hash FROM app_user WHERE id = ?",
                    String.class, tenantBUser.getId());
            assertThat(tenantBFinalHash)
                    .as("Tenant B's user password MUST have changed (positive control — proves the reset path runs)")
                    .isNotEqualTo(tenantBOriginalHash);
            assertThat(passwordService.matches("NewSecurePass12!", tenantBFinalHash))
                    .as("Tenant B's new password must match what was set")
                    .isTrue();
        });
    }
}
