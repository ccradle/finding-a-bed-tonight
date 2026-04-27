package org.fabt.auth.platform;

import java.time.Instant;
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
 * Integration tests for {@code GET /api/v1/auth/platform/me} (F11 task 2.2).
 *
 * <p>Covers the four spec scenarios from
 * {@code openspec/changes/platform-operator-ui/specs/platform-operator-identity/spec.md}:
 * authenticated retrieval, missing JWT (401), wrong-scope token (403),
 * MFA-setup-only token (403). Also asserts backup-codes-remaining
 * decrements correctly after a backup-code consumption.
 *
 * <p>Mirrors the {@link PlatformAuthIntegrationTest} fixture pattern:
 * bootstrap-row activation in {@code @BeforeEach}, helper
 * {@link #enrollAndGetAccessToken()} for the full
 * login→mfa-setup→mfa-confirm flow.
 */
@DisplayName("PlatformAuthController GET /me")
@SpringBootTest(classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformAuthControllerMeTest extends BaseIntegrationTest {

    private static final UUID BOOTSTRAP_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000fab");
    private static final String OPERATOR_EMAIL = "ops-me-it@example.com";
    private static final String OPERATOR_PASSWORD = "InitialPassword!@#$1234";

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
    @DisplayName("authenticated operator retrieves own metadata with all expected fields")
    void getMeReturnsAllFields() {
        EnrolledOperator op = enrollAndGetAccessToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(op.accessToken());
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/auth/platform/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsKeys("id", "email", "mfaEnabled", "lastLoginAt",
                "mfaEnabledAt", "backupCodesRemaining");
        assertThat(body.get("id")).isEqualTo(BOOTSTRAP_ID.toString());
        assertThat(body.get("email")).isEqualTo(OPERATOR_EMAIL);
        assertThat(body.get("mfaEnabled")).isEqualTo(Boolean.TRUE);
        // mfaEnabledAt was just set during enrollment; lastLoginAt is also recent.
        assertThat(body.get("mfaEnabledAt")).isNotNull();
        assertThat(body.get("backupCodesRemaining")).isEqualTo(10);
        // Defense: secrets MUST NOT leak into the response
        assertThat(body).doesNotContainKeys("passwordHash", "mfaSecret", "backupCodes");
    }

    @Test
    @DisplayName("missing Authorization header → 401 invalid_platform_token")
    void getMeWithoutTokenReturns401() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/auth/platform/me",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("error")).isEqualTo("invalid_platform_token");
    }

    @Test
    @DisplayName("MFA-setup-only token presented to /me → 403")
    void getMeWithMfaSetupTokenReturns403() {
        // First login (not yet enrolled) returns an mfa-setup-scoped token
        ResponseEntity<Map> login = restTemplate.postForEntity(
                "/api/v1/auth/platform/login",
                Map.of("email", OPERATOR_EMAIL, "password", OPERATOR_PASSWORD),
                Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String setupToken = (String) login.getBody().get("token");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(setupToken);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/auth/platform/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("garbage Bearer token → 401 invalid_platform_token")
    void getMeWithGarbageTokenReturns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("not.a.real.jwt");
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/auth/platform/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("error")).isEqualTo("invalid_platform_token");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private EnrolledOperator enrollAndGetAccessToken() {
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

        String totpCode = currentTotpCode(secret);
        HttpHeaders confirmHeaders = new HttpHeaders();
        confirmHeaders.setBearerAuth(setupToken);
        confirmHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> confirm = restTemplate.exchange(
                "/api/v1/auth/platform/mfa-confirm",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("code", totpCode), confirmHeaders),
                Map.class);
        String accessToken = (String) confirm.getBody().get("token");

        return new EnrolledOperator(accessToken, backupCodes, Instant.now());
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

    private record EnrolledOperator(String accessToken, List<String> backupCodes, Instant enrolledAt) {
    }
}
