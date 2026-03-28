package org.fabt.auth;

import java.time.Instant;
import java.util.Map;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.auth.repository.UserRepository;
import org.fabt.auth.service.PasswordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordChangeIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordService passwordService;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupOutreachWorkerUser();
    }

    @Test
    void changePassword_succeeds_with_correct_current_password() {
        User user = authHelper.setupOutreachWorkerUser();
        HttpHeaders headers = authHelper.headersForUser(user);

        String body = """
                {"currentPassword": "%s", "newPassword": "NewSecurePass12"}
                """.formatted(TestAuthHelper.TEST_PASSWORD);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/auth/password", HttpMethod.PUT,
                new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("message")).isEqualTo("Password changed. Please sign in again.");

        // Verify DB was updated
        User updated = userRepository.findById(user.getId()).orElseThrow();
        assertThat(passwordService.matches("NewSecurePass12", updated.getPasswordHash())).isTrue();
        assertThat(updated.getPasswordChangedAt()).isNotNull();

        // Restore original password for other tests
        updated.setPasswordHash(passwordService.hash(TestAuthHelper.TEST_PASSWORD));
        updated.setPasswordChangedAt(null);
        userRepository.save(updated);
    }

    @Test
    void changePassword_returns_401_for_wrong_current_password() {
        User user = authHelper.setupOutreachWorkerUser();
        HttpHeaders headers = authHelper.headersForUser(user);

        String body = """
                {"currentPassword": "WrongPassword99", "newPassword": "NewSecurePass12"}
                """;

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/auth/password", HttpMethod.PUT,
                new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("message")).isEqualTo("Current password is incorrect");
    }

    @Test
    void changePassword_returns_400_for_weak_new_password() {
        User user = authHelper.setupOutreachWorkerUser();
        HttpHeaders headers = authHelper.headersForUser(user);

        String body = """
                {"currentPassword": "%s", "newPassword": "short"}
                """.formatted(TestAuthHelper.TEST_PASSWORD);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/auth/password", HttpMethod.PUT,
                new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void changePassword_invalidates_old_tokens() throws InterruptedException {
        User user = authHelper.setupOutreachWorkerUser();
        HttpHeaders oldHeaders = authHelper.headersForUser(user);

        // Ensure old token's iat (epoch seconds) is strictly before password_changed_at.
        // JWT iat has second precision; password_changed_at has microsecond precision.
        // Without this pause, both can land in the same second, making the comparison ambiguous.
        Thread.sleep(1100);

        // Change the password
        String body = """
                {"currentPassword": "%s", "newPassword": "NewSecurePass12"}
                """.formatted(TestAuthHelper.TEST_PASSWORD);

        ResponseEntity<Map> changeResponse = restTemplate.exchange(
                "/api/v1/auth/password", HttpMethod.PUT,
                new HttpEntity<>(body, oldHeaders), Map.class);
        assertThat(changeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Old token should now be rejected on a protected endpoint
        ResponseEntity<String> protectedResponse = restTemplate.exchange(
                "/api/v1/shelters", HttpMethod.GET,
                new HttpEntity<>(oldHeaders), String.class);
        assertThat(protectedResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // New token from login should work
        String loginBody = """
                {"email": "%s", "password": "NewSecurePass12", "tenantSlug": "%s"}
                """.formatted(TestAuthHelper.OUTREACH_EMAIL, authHelper.getTestTenantSlug());
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> loginResponse = restTemplate.exchange(
                "/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(loginBody, loginHeaders), Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Restore original password
        User updated = userRepository.findById(user.getId()).orElseThrow();
        updated.setPasswordHash(passwordService.hash(TestAuthHelper.TEST_PASSWORD));
        updated.setPasswordChangedAt(null);
        userRepository.save(updated);
    }

    @Test
    void changePassword_returns_409_for_sso_only_user() {
        // Create a user with no password hash (SSO-only)
        User ssoUser = new User();
        ssoUser.setTenantId(authHelper.getTestTenantId());
        ssoUser.setEmail("sso-user-" + System.currentTimeMillis() + "@test.fabt.org");
        ssoUser.setDisplayName("SSO User");
        ssoUser.setPasswordHash(null);
        ssoUser.setRoles(new String[]{"OUTREACH_WORKER"});
        ssoUser.setDvAccess(false);
        ssoUser.setCreatedAt(Instant.now());
        ssoUser.setUpdatedAt(Instant.now());
        ssoUser = userRepository.save(ssoUser);

        HttpHeaders headers = authHelper.headersForUser(ssoUser);

        String body = """
                {"currentPassword": "anything", "newPassword": "NewSecurePass12"}
                """;

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/auth/password", HttpMethod.PUT,
                new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("message")).isEqualTo("Password is managed by your SSO provider");
    }

    @Test
    void changePassword_returns_401_without_authentication() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = """
                {"currentPassword": "anything", "newPassword": "NewSecurePass12"}
                """;

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/password", HttpMethod.PUT,
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
