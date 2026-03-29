package org.fabt.auth;

import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.api.UserResponse;
import org.fabt.auth.domain.User;
import org.fabt.auth.repository.UserRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for user management: edit, deactivate, reactivate,
 * token versioning, and audit trail.
 */
class UserManagementIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
    }

    @Test
    @DisplayName("T-16: Edit user roles increments tokenVersion")
    void editUserRoles_incrementsTokenVersion() {
        // Create a user to edit
        User outreach = authHelper.setupOutreachWorkerUser();
        int originalVersion = outreach.getTokenVersion();

        // Edit roles via API
        String body = """
                {"roles": ["COORDINATOR"], "dvAccess": false}
                """;
        HttpHeaders headers = authHelper.adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<UserResponse> response = restTemplate.exchange(
                "/api/v1/users/" + outreach.getId(),
                HttpMethod.PUT,
                new HttpEntity<>(body, headers),
                UserResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().roles()).containsExactly("COORDINATOR");

        // Verify tokenVersion incremented in DB
        User updated = userRepository.findById(outreach.getId()).orElseThrow();
        assertThat(updated.getTokenVersion()).isGreaterThan(originalVersion);
    }

    @Test
    @DisplayName("T-17: Deactivate user, login rejected with 401")
    void deactivateUser_loginRejected() {
        // Create a dedicated user for this test
        User testUser = authHelper.setupUserWithDvAccess(
                "deactivate-test@test.fabt.org", "Deactivate Test", new String[]{"OUTREACH_WORKER"});

        // Deactivate via API
        String statusBody = """
                {"status": "DEACTIVATED"}
                """;
        HttpHeaders adminHeaders = authHelper.adminHeaders();
        adminHeaders.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<UserResponse> deactivateResponse = restTemplate.exchange(
                "/api/v1/users/" + testUser.getId() + "/status",
                HttpMethod.PATCH,
                new HttpEntity<>(statusBody, adminHeaders),
                UserResponse.class);

        assertThat(deactivateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deactivateResponse.getBody().status()).isEqualTo("DEACTIVATED");

        // Attempt login — should be rejected
        String loginBody = """
                {"email": "deactivate-test@test.fabt.org", "password": "%s", "tenantSlug": "%s"}
                """.formatted(TestAuthHelper.TEST_PASSWORD, authHelper.getTestTenantSlug());

        ResponseEntity<String> loginResponse = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new HttpEntity<>(loginBody, jsonHeaders()),
                String.class);

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(loginResponse.getBody()).contains("deactivated");
    }

    @Test
    @DisplayName("T-18: Deactivated user's JWT rejected via stale tokenVersion")
    void deactivatedUser_jwtRejected() {
        User testUser = authHelper.setupUserWithDvAccess(
                "jwt-reject-test@test.fabt.org", "JWT Reject Test", new String[]{"OUTREACH_WORKER"});

        // Get a valid token before deactivation
        HttpHeaders userHeaders = authHelper.headersForUser(testUser);

        // Verify the token works
        ResponseEntity<String> beforeResponse = restTemplate.exchange(
                "/api/v1/queries/beds",
                HttpMethod.POST,
                new HttpEntity<>("{}", userHeaders),
                String.class);
        assertThat(beforeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Deactivate the user
        String statusBody = """
                {"status": "DEACTIVATED"}
                """;
        HttpHeaders adminHeaders = authHelper.adminHeaders();
        adminHeaders.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.exchange(
                "/api/v1/users/" + testUser.getId() + "/status",
                HttpMethod.PATCH,
                new HttpEntity<>(statusBody, adminHeaders),
                UserResponse.class);

        // The old token should now be rejected (tokenVersion mismatch)
        ResponseEntity<String> afterResponse = restTemplate.exchange(
                "/api/v1/queries/beds",
                HttpMethod.POST,
                new HttpEntity<>("{}", userHeaders),
                String.class);
        assertThat(afterResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("T-19: Audit events persisted on role change and deactivation")
    void auditEvents_persistedOnChanges() {
        User testUser = authHelper.setupUserWithDvAccess(
                "audit-test@test.fabt.org", "Audit Test", new String[]{"OUTREACH_WORKER"});

        HttpHeaders adminHeaders = authHelper.adminHeaders();
        adminHeaders.setContentType(MediaType.APPLICATION_JSON);

        // Change roles
        restTemplate.exchange(
                "/api/v1/users/" + testUser.getId(),
                HttpMethod.PUT,
                new HttpEntity<>("{\"roles\": [\"COORDINATOR\"]}", adminHeaders),
                UserResponse.class);

        // Deactivate
        restTemplate.exchange(
                "/api/v1/users/" + testUser.getId() + "/status",
                HttpMethod.PATCH,
                new HttpEntity<>("{\"status\": \"DEACTIVATED\"}", adminHeaders),
                UserResponse.class);

        // Query audit events
        ResponseEntity<String> auditResponse = restTemplate.exchange(
                "/api/v1/audit-events?targetUserId=" + testUser.getId(),
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                String.class);

        assertThat(auditResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(auditResponse.getBody()).contains("ROLE_CHANGED");
        assertThat(auditResponse.getBody()).contains("USER_DEACTIVATED");
    }

    @Test
    @DisplayName("T-20: Reactivate user, login works again")
    void reactivateUser_loginWorks() {
        User testUser = authHelper.setupUserWithDvAccess(
                "reactivate-test@test.fabt.org", "Reactivate Test", new String[]{"OUTREACH_WORKER"});

        HttpHeaders adminHeaders = authHelper.adminHeaders();
        adminHeaders.setContentType(MediaType.APPLICATION_JSON);

        // Deactivate
        restTemplate.exchange(
                "/api/v1/users/" + testUser.getId() + "/status",
                HttpMethod.PATCH,
                new HttpEntity<>("{\"status\": \"DEACTIVATED\"}", adminHeaders),
                UserResponse.class);

        // Verify login fails
        String loginBody = """
                {"email": "reactivate-test@test.fabt.org", "password": "%s", "tenantSlug": "%s"}
                """.formatted(TestAuthHelper.TEST_PASSWORD, authHelper.getTestTenantSlug());

        ResponseEntity<String> failedLogin = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new HttpEntity<>(loginBody, jsonHeaders()),
                String.class);
        assertThat(failedLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Reactivate
        restTemplate.exchange(
                "/api/v1/users/" + testUser.getId() + "/status",
                HttpMethod.PATCH,
                new HttpEntity<>("{\"status\": \"ACTIVE\"}", adminHeaders),
                UserResponse.class);

        // Login should work now
        ResponseEntity<String> successLogin = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new HttpEntity<>(loginBody, jsonHeaders()),
                String.class);
        assertThat(successLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
