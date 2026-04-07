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
        // Create an org-level API key (implicit COC_ADMIN role)
        UUID tenantId = authHelper.getTestTenantId();
        ApiKeyService.ApiKeyCreateResult result = apiKeyService.create(tenantId, null, "Test Key");

        // Use the API key to access a COC_ADMIN endpoint
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", result.plaintextKey());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users",
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
                "/api/v1/users",
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
                "/api/v1/users",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Deactivate the key
        apiKeyService.deactivate(result.id());

        // Now it should fail
        ResponseEntity<String> secondResponse = restTemplate.exchange(
                "/api/v1/users",
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

        // COORDINATOR role should NOT have access to user management (COC_ADMIN only)
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void test_apiKeyAuth_keyRotation_bothKeysWorkDuringGracePeriod() {
        // Create an API key
        UUID tenantId = authHelper.getTestTenantId();
        ApiKeyService.ApiKeyCreateResult original = apiKeyService.create(tenantId, null, "Rotate Me");

        // Rotate the key
        ApiKeyService.ApiKeyCreateResult rotated = apiKeyService.rotate(original.id());

        // OLD key should STILL work during grace period (24h default)
        HttpHeaders oldHeaders = new HttpHeaders();
        oldHeaders.set("X-API-Key", original.plaintextKey());

        ResponseEntity<String> oldKeyResponse = restTemplate.exchange(
                "/api/v1/users", HttpMethod.GET,
                new HttpEntity<>(oldHeaders), String.class);
        assertThat(oldKeyResponse.getStatusCode())
                .as("Old key should authenticate during grace period")
                .isEqualTo(HttpStatus.OK);

        // New key should also work
        HttpHeaders newHeaders = new HttpHeaders();
        newHeaders.set("X-API-Key", rotated.plaintextKey());

        ResponseEntity<String> newKeyResponse = restTemplate.exchange(
                "/api/v1/users", HttpMethod.GET,
                new HttpEntity<>(newHeaders), String.class);
        assertThat(newKeyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // T-5f: Revoke during active grace period — old key no longer authenticates
    @Test
    void test_apiKeyAuth_revokeDuringGracePeriod_bothKeysFail() {
        UUID tenantId = authHelper.getTestTenantId();
        ApiKeyService.ApiKeyCreateResult original = apiKeyService.create(tenantId, null, "Revoke During Grace");

        // Rotate — creates grace period
        ApiKeyService.ApiKeyCreateResult rotated = apiKeyService.rotate(original.id());

        // Revoke — should kill both current and old key
        apiKeyService.deactivate(original.id());

        // Old key must fail
        HttpHeaders oldHeaders = new HttpHeaders();
        oldHeaders.set("X-API-Key", original.plaintextKey());
        ResponseEntity<String> oldResp = restTemplate.exchange(
                "/api/v1/users", HttpMethod.GET,
                new HttpEntity<>(oldHeaders), String.class);
        assertThat(oldResp.getStatusCode())
                .as("Old key must fail after revoke during grace period")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // New key must also fail (key is deactivated)
        HttpHeaders newHeaders = new HttpHeaders();
        newHeaders.set("X-API-Key", rotated.plaintextKey());
        ResponseEntity<String> newResp = restTemplate.exchange(
                "/api/v1/users", HttpMethod.GET,
                new HttpEntity<>(newHeaders), String.class);
        assertThat(newResp.getStatusCode())
                .as("New key must fail after revoke during grace period")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // T-5g: Expired grace period old key rejected even without cleanup
    @Test
    void test_apiKeyAuth_expiredGracePeriod_oldKeyRejected() {
        UUID tenantId = authHelper.getTestTenantId();
        ApiKeyService.ApiKeyCreateResult original = apiKeyService.create(tenantId, null, "Expire Grace");

        // Rotate
        apiKeyService.rotate(original.id());

        // Manually expire the grace period in DB (simulate clock advance)
        jdbcTemplate.update(
                "UPDATE api_key SET old_key_expires_at = NOW() - INTERVAL '1 hour' WHERE id = ?",
                original.id());

        // Old key should now fail (grace expired, even though cleanup hasn't run)
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", original.plaintextKey());
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/users", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode())
                .as("Old key must fail after grace period expires (SQL-level check)")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // T-5h: Rotated key is 64 hex chars (256 bits)
    @Test
    void test_apiKeyAuth_keyEntropy_256bits() {
        UUID tenantId = authHelper.getTestTenantId();
        ApiKeyService.ApiKeyCreateResult result = apiKeyService.create(tenantId, null, "Entropy Check");
        assertThat(result.plaintextKey()).hasSize(64); // 32 bytes = 64 hex chars

        ApiKeyService.ApiKeyCreateResult rotated = apiKeyService.rotate(result.id());
        assertThat(rotated.plaintextKey()).hasSize(64);
    }

    // T-22a: Revoke non-existent key → 404
    @Test
    void test_apiKeyAuth_revokeNonExistent_returns404() {
        HttpHeaders headers = authHelper.cocAdminHeaders();
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/api-keys/" + UUID.randomUUID(),
                HttpMethod.DELETE,
                new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // T-22b: Non-admin revoke → 403
    @Test
    void test_apiKeyAuth_nonAdminRevoke_returns403() {
        UUID tenantId = authHelper.getTestTenantId();
        ApiKeyService.ApiKeyCreateResult key = apiKeyService.create(tenantId, null, "Non-admin Test");

        // Outreach worker should NOT be able to revoke
        authHelper.setupOutreachWorkerUser();
        HttpHeaders headers = authHelper.outreachWorkerHeaders();
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/api-keys/" + key.id(),
                HttpMethod.DELETE,
                new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // T-22c: Revoke already-revoked key → idempotent (204)
    @Test
    void test_apiKeyAuth_revokeAlreadyRevoked_isIdempotent() {
        UUID tenantId = authHelper.getTestTenantId();
        ApiKeyService.ApiKeyCreateResult key = apiKeyService.create(tenantId, null, "Double Revoke");

        // Revoke once
        apiKeyService.deactivate(key.id());

        // Revoke again via API — should NOT throw
        HttpHeaders headers = authHelper.cocAdminHeaders();
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/api-keys/" + key.id(),
                HttpMethod.DELETE,
                new HttpEntity<>(headers), String.class);
        // 204 (NoContent) from controller — deactivate is idempotent (sets active=false again)
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // T-23: Rotate key — both old and new work during grace, verify via API
    // (Covered by test_apiKeyAuth_keyRotation_bothKeysWorkDuringGracePeriod above)

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
