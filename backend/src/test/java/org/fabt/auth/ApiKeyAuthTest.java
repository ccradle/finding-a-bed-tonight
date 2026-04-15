package org.fabt.auth;

import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.api.ApiKeyCreateResponse;
import org.fabt.auth.service.ApiKeyService;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "fabt.api-key.rate-limit=1000"
)
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
        ApiKeyService.ApiKeyCreateResult result = TenantContext.callWithContext(tenantId, false,
                () -> apiKeyService.create(null, "Test Key"));

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
        ApiKeyService.ApiKeyCreateResult result = TenantContext.callWithContext(tenantId, false,
                () -> apiKeyService.create(null, "Deactivate Me"));

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

        // Deactivate the key (D11: service pulls tenantId from TenantContext)
        TenantContext.runWithContext(tenantId, false, () -> apiKeyService.deactivate(result.id()));

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
        ApiKeyService.ApiKeyCreateResult result = TenantContext.callWithContext(tenantId, false,
                () -> apiKeyService.create(null, "Org Level Key"));

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

        ApiKeyService.ApiKeyCreateResult result = TenantContext.callWithContext(tenantId, false,
                () -> apiKeyService.create(shelterId, "Shelter Scoped Key"));

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
        ApiKeyService.ApiKeyCreateResult original = TenantContext.callWithContext(tenantId, false,
                () -> apiKeyService.create(null, "Rotate Me"));

        // Rotate the key (D11: service pulls tenantId from TenantContext)
        ApiKeyService.ApiKeyCreateResult rotated = TenantContext.callWithContext(tenantId, false,
                () -> apiKeyService.rotate(original.id()));

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
        ApiKeyService.ApiKeyCreateResult original = TenantContext.callWithContext(tenantId, false,
                () -> apiKeyService.create(null, "Revoke During Grace"));

        // Rotate — creates grace period (D11)
        ApiKeyService.ApiKeyCreateResult rotated = TenantContext.callWithContext(tenantId, false,
                () -> apiKeyService.rotate(original.id()));

        // Revoke — should kill both current and old key (D11)
        TenantContext.runWithContext(tenantId, false, () -> apiKeyService.deactivate(original.id()));

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
        ApiKeyService.ApiKeyCreateResult original = TenantContext.callWithContext(tenantId, false,
                () -> apiKeyService.create(null, "Expire Grace"));

        // Rotate
        TenantContext.runWithContext(tenantId, false, () -> apiKeyService.rotate(original.id()));

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
        ApiKeyService.ApiKeyCreateResult result = TenantContext.callWithContext(tenantId, false,
                () -> apiKeyService.create(null, "Entropy Check"));
        assertThat(result.plaintextKey()).hasSize(64); // 32 bytes = 64 hex chars

        ApiKeyService.ApiKeyCreateResult rotated = TenantContext.callWithContext(tenantId, false,
                () -> apiKeyService.rotate(result.id()));
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
        ApiKeyService.ApiKeyCreateResult key = TenantContext.callWithContext(tenantId, false,
                () -> apiKeyService.create(null, "Non-admin Test"));

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
        ApiKeyService.ApiKeyCreateResult key = TenantContext.callWithContext(tenantId, false,
                () -> apiKeyService.create(null, "Double Revoke"));

        // Revoke once
        TenantContext.runWithContext(tenantId, false, () -> apiKeyService.deactivate(key.id()));

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

    // ------------------------------------------------------------------
    // cross-tenant-isolation-audit (Issue #117) — Phase 2 task 2.2.5.
    // Two regression tests pinning the findByIdAndTenantId / findByIdOrThrow
    // refactor on ApiKeyService.rotate + .deactivate. Mirrors the v0.39
    // DvReferralIntegrationTest.tc_*_crossTenant_returns404 pattern.
    //
    // THREAT MODEL (Marcus Webb, VULN-HIGH — availability + DoS):
    // pre-fix, a CoC admin in Tenant A could POST /api/v1/api-keys/
    // {tenantB-key-id}/rotate OR DELETE /api/v1/api-keys/{tenantB-key-id} —
    // invalidating Tenant B's API key integrations without their knowledge.
    // Cross-tenant denial-of-service against SaaS-style webhook / API
    // automation. Unlike the OAuth2 provider case, there's no auth-hijack
    // pivot — but silent DoS of another CoC's integrations is its own
    // class of problem.
    // ------------------------------------------------------------------

    @Test
    void tc_rotate_crossTenant_returns404_leavesTenantBKeyUnchanged() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // Set up Tenant B with its own admin + API key.
        org.fabt.tenant.domain.Tenant tenantB =
                authHelper.setupSecondaryTenant("xtenant-apikey-rotate-" + suffix);
        ApiKeyService.ApiKeyCreateResult tenantBKey = TenantContext.callWithContext(
                tenantB.getId(), false,
                () -> apiKeyService.create(null, "Tenant B legitimate key"));
        String originalKeyHash = jdbcTemplate.queryForObject(
                "SELECT key_hash FROM api_key WHERE id = ?::uuid",
                String.class, tenantBKey.id());

        // Act: Tenant A's COC_ADMIN attempts to rotate Tenant B's API key —
        // would silently invalidate Tenant B's integrations pre-fix.
        HttpHeaders tenantAHeaders = authHelper.cocAdminHeaders();
        ResponseEntity<String> attackResp = restTemplate.exchange(
                "/api/v1/api-keys/" + tenantBKey.id() + "/rotate",
                HttpMethod.POST,
                new HttpEntity<>(tenantAHeaders),
                String.class);

        // Assert: 404 (not 403 — D3 symmetric).
        assertThat(attackResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Defense-in-depth: Tenant B's key_hash is unchanged (no rotation
        // happened) AND old_key_hash was not set (no grace-period artifact
        // from the failed attempt).
        TenantContext.runWithContext(tenantB.getId(), false, () -> {
            java.util.Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT key_hash, old_key_hash, active FROM api_key WHERE id = ?::uuid",
                    tenantBKey.id());
            assertThat(row.get("key_hash"))
                    .as("Tenant B's key_hash must be unchanged — rotation did NOT happen")
                    .isEqualTo(originalKeyHash);
            assertThat(row.get("old_key_hash"))
                    .as("Tenant B's old_key_hash must remain null — no grace-period artifact")
                    .isNull();
            assertThat((Boolean) row.get("active"))
                    .as("Tenant B's key must still be active")
                    .isTrue();
        });
    }

    @Test
    void tc_deactivate_crossTenant_returns404_leavesTenantBKeyActive() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        org.fabt.tenant.domain.Tenant tenantB =
                authHelper.setupSecondaryTenant("xtenant-apikey-deactivate-" + suffix);
        ApiKeyService.ApiKeyCreateResult tenantBKey = TenantContext.callWithContext(
                tenantB.getId(), false,
                () -> apiKeyService.create(null, "Tenant B key to protect"));

        // Act: Tenant A's COC_ADMIN attempts to deactivate Tenant B's API key.
        HttpHeaders tenantAHeaders = authHelper.cocAdminHeaders();
        ResponseEntity<String> attackResp = restTemplate.exchange(
                "/api/v1/api-keys/" + tenantBKey.id(),
                HttpMethod.DELETE,
                new HttpEntity<>(tenantAHeaders),
                String.class);

        assertThat(attackResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Defense-in-depth: Tenant B's key is still active.
        TenantContext.runWithContext(tenantB.getId(), false, () -> {
            Boolean active = jdbcTemplate.queryForObject(
                    "SELECT active FROM api_key WHERE id = ?::uuid",
                    Boolean.class, tenantBKey.id());
            assertThat(active)
                    .as("Tenant B's API key must still be active after cross-tenant DELETE attempt")
                    .isTrue();
        });
    }
}
