package org.fabt.auth;

import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class RoleBasedAccessTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        authHelper.setupCocAdminUser();
        authHelper.setupCoordinatorUser();
        authHelper.setupOutreachWorkerUser();
    }

    @Test
    void test_platformAdmin_canCreateTenant() {
        HttpHeaders headers = authHelper.adminHeaders();
        String slug = "rbac-admin-" + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {"name": "RBAC Admin Test", "slug": "%s"}
                """.formatted(slug);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/tenants",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void test_cocAdmin_canAccessProtectedEndpoints() {
        HttpHeaders headers = authHelper.cocAdminHeaders();

        // COC_ADMIN can access authenticated endpoints like listing tenants
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/tenants",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        // COC_ADMIN is authenticated, so they can access protected endpoints
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void test_coordinator_canAccessProtectedEndpoints() {
        HttpHeaders headers = authHelper.coordinatorHeaders();

        // COORDINATOR can access authenticated endpoints like listing tenants
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/tenants",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void test_outreachWorker_canAccessProtectedEndpoints() {
        HttpHeaders headers = authHelper.outreachWorkerHeaders();

        // OUTREACH_WORKER can access authenticated endpoints
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/tenants",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void test_unauthenticated_canAccessHealthEndpoint() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/actuator/health",
                HttpMethod.GET,
                null,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void test_unauthenticated_cannotAccessProtectedEndpoint() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users",
                HttpMethod.GET,
                null,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void test_unauthenticated_canAccessAuthEndpoint() {
        // Auth login endpoint is public (permitAll)
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");

        String body = """
                {"email": "fake@test.org", "password": "fake", "tenantSlug": "nonexistent"}
                """;

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        // Should get 401 from auth logic (not from Spring Security blocking access)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void test_unauthenticated_cannotAccessApiKeys() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/api-keys",
                HttpMethod.GET,
                null,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void test_unauthenticated_cannotCreateUser() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");

        String body = """
                {"email": "test@test.org", "displayName": "Test", "roles": ["COORDINATOR"]}
                """;

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
