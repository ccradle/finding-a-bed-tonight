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
 * Integration tests for {@code POST /api/v1/auth/platform/logout} (F11
 * task 2.3). Server-side no-op for v0.54 — returns 204; the client clears
 * sessionStorage. Future hook for Phase H+ token revocation.
 *
 * <p>Covers spec scenarios from
 * {@code openspec/changes/platform-operator-ui/specs/platform-operator-identity/spec.md}:
 * authenticated logout returns 204, missing JWT → 401, scoped token → 403,
 * no DB mutation.
 */
@DisplayName("PlatformAuthController POST /logout")
@SpringBootTest(classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformAuthControllerLogoutTest extends BaseIntegrationTest {

    private static final UUID BOOTSTRAP_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000fab");
    private static final String OPERATOR_EMAIL = "ops-logout-it@example.com";
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
    @DisplayName("authenticated logout returns 204 with empty body, no DB mutation")
    void logoutReturns204() {
        String accessToken = enrollAndGetAccessToken();

        // Capture last_login_at before logout — must NOT change (no DB mutation)
        Long lastLoginEpochBefore = jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (SELECT last_login_at FROM platform_user_get_me(?::uuid)))",
                Long.class, BOOTSTRAP_ID);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/platform/logout",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNullOrEmpty();

        Long lastLoginEpochAfter = jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (SELECT last_login_at FROM platform_user_get_me(?::uuid)))",
                Long.class, BOOTSTRAP_ID);
        assertThat(lastLoginEpochAfter).isEqualTo(lastLoginEpochBefore);
    }

    @Test
    @DisplayName("missing Authorization header → 401 invalid_platform_token")
    void logoutWithoutTokenReturns401() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/auth/platform/logout",
                HttpMethod.POST,
                HttpEntity.EMPTY,
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("error")).isEqualTo("invalid_platform_token");
    }

    @Test
    @DisplayName("MFA-setup-only token presented to /logout → 403")
    void logoutWithMfaSetupTokenReturns403() {
        ResponseEntity<Map> login = restTemplate.postForEntity(
                "/api/v1/auth/platform/login",
                Map.of("email", OPERATOR_EMAIL, "password", OPERATOR_PASSWORD),
                Map.class);
        String setupToken = (String) login.getBody().get("token");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(setupToken);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/auth/platform/logout",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private String enrollAndGetAccessToken() {
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

        String totpCode = currentTotpCode(secret);
        HttpHeaders confirmHeaders = new HttpHeaders();
        confirmHeaders.setBearerAuth(setupToken);
        confirmHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> confirm = restTemplate.exchange(
                "/api/v1/auth/platform/mfa-confirm",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("code", totpCode), confirmHeaders),
                Map.class);
        return (String) confirm.getBody().get("token");
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
}
