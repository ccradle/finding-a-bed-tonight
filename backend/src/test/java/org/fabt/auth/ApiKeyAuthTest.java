package org.fabt.auth;

import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.api.ApiKeyCreateResponse;
import org.fabt.auth.service.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyAuthTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private ApiKeyService apiKeyService;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
    }

    @Test
    void test_apiKeyAuth_validKey() {
        // Create an API key via the service directly (org-level, no shelter)
        UUID tenantId = authHelper.getTestTenantId();
        ApiKeyService.ApiKeyCreateResult result = apiKeyService.create(tenantId, null, "Test Key");

        // Use the API key to access a protected endpoint
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", result.plaintextKey());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/tenants",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void test_apiKeyAuth_invalidKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "definitely-not-a-real-api-key-abcdef1234567890");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/tenants",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void test_apiKeyAuth_deactivatedKey() {
        // Create an API key
        UUID tenantId = authHelper.getTestTenantId();
        ApiKeyService.ApiKeyCreateResult result = apiKeyService.create(tenantId, null, "Deactivate Me");

        // Verify it works first
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", result.plaintextKey());

        ResponseEntity<String> firstResponse = restTemplate.exchange(
                "/api/v1/tenants",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Deactivate the key
        apiKeyService.deactivate(result.id());

        // Now it should fail
        ResponseEntity<String> secondResponse = restTemplate.exchange(
                "/api/v1/tenants",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void test_apiKeyAuth_orgLevel_hasCocAdminRole() {
        // Create an org-level API key (no shelterId) - should get COC_ADMIN role
        UUID tenantId = authHelper.getTestTenantId();
        ApiKeyService.ApiKeyCreateResult result = apiKeyService.create(tenantId, null, "Org Level Key");

        // Use the API key to create another API key (requires authentication)
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", result.plaintextKey());
        headers.set("Content-Type", "application/json");

        // Should be able to list API keys (authenticated endpoint)
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/api-keys",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void test_apiKeyAuth_shelterScoped_hasCoordinatorRole() {
        // Create a shelter-scoped API key - should get COORDINATOR role
        UUID tenantId = authHelper.getTestTenantId();

        // We need a shelter ID for scoped keys. Since shelter module isn't built yet,
        // we insert a shelter directly via SQL in the DB. But the FK constraint
        // from api_key.shelter_id -> shelter.id requires a real shelter row.
        // Use JdbcTemplate to insert one.
        UUID shelterId = insertTestShelter(tenantId, "API Key Test Shelter");

        ApiKeyService.ApiKeyCreateResult result = apiKeyService.create(tenantId, shelterId, "Shelter Scoped Key");

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", result.plaintextKey());

        // Should be able to access authenticated endpoints
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/tenants",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void test_apiKeyAuth_keyRotation() {
        // Create an API key
        UUID tenantId = authHelper.getTestTenantId();
        ApiKeyService.ApiKeyCreateResult original = apiKeyService.create(tenantId, null, "Rotate Me");

        // Rotate the key
        ApiKeyService.ApiKeyCreateResult rotated = apiKeyService.rotate(original.id());

        // Old key should no longer work
        HttpHeaders oldHeaders = new HttpHeaders();
        oldHeaders.set("X-API-Key", original.plaintextKey());

        ResponseEntity<String> oldKeyResponse = restTemplate.exchange(
                "/api/v1/tenants",
                HttpMethod.GET,
                new HttpEntity<>(oldHeaders),
                String.class
        );
        assertThat(oldKeyResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // New key should work
        HttpHeaders newHeaders = new HttpHeaders();
        newHeaders.set("X-API-Key", rotated.plaintextKey());

        ResponseEntity<String> newKeyResponse = restTemplate.exchange(
                "/api/v1/tenants",
                HttpMethod.GET,
                new HttpEntity<>(newHeaders),
                String.class
        );
        assertThat(newKeyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /**
     * Inserts a test shelter directly into the database, bypassing the shelter module
     * which doesn't exist yet. Uses JdbcTemplate to execute raw SQL.
     */
    private UUID insertTestShelter(UUID tenantId, String name) {
        UUID shelterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO shelter (id, tenant_id, name, dv_shelter, created_at, updated_at) VALUES (?, ?, ?, false, NOW(), NOW())",
                shelterId, tenantId, name
        );
        return shelterId;
    }
}
