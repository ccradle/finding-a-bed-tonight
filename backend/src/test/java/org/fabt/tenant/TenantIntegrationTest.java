package org.fabt.tenant;

import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.tenant.api.TenantResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class TenantIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
    }

    @Test
    void test_createTenant_success() {
        HttpHeaders headers = authHelper.adminHeaders();
        String body = """
                {"name": "New CoC Tenant", "slug": "new-coc-tenant"}
                """;

        ResponseEntity<TenantResponse> response = restTemplate.exchange(
                "/api/v1/tenants",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                TenantResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().name()).isEqualTo("New CoC Tenant");
        assertThat(response.getBody().slug()).isEqualTo("new-coc-tenant");
        assertThat(response.getBody().id()).isNotNull();
        assertThat(response.getBody().createdAt()).isNotNull();
    }

    @Test
    void test_createTenant_duplicateSlug() {
        HttpHeaders headers = authHelper.adminHeaders();

        // Create first tenant with a unique slug for this test
        String uniqueSlug = "dup-slug-" + UUID.randomUUID().toString().substring(0, 8);
        String body1 = """
                {"name": "First Tenant", "slug": "%s"}
                """.formatted(uniqueSlug);

        ResponseEntity<TenantResponse> first = restTemplate.exchange(
                "/api/v1/tenants",
                HttpMethod.POST,
                new HttpEntity<>(body1, headers),
                TenantResponse.class
        );
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Attempt to create second tenant with the same slug
        String body2 = """
                {"name": "Second Tenant", "slug": "%s"}
                """.formatted(uniqueSlug);

        ResponseEntity<String> second = restTemplate.exchange(
                "/api/v1/tenants",
                HttpMethod.POST,
                new HttpEntity<>(body2, headers),
                String.class
        );

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void test_getTenant_byId() {
        HttpHeaders headers = authHelper.adminHeaders();

        // Create a tenant to retrieve
        String slug = "get-test-" + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {"name": "Retrievable Tenant", "slug": "%s"}
                """.formatted(slug);

        ResponseEntity<TenantResponse> created = restTemplate.exchange(
                "/api/v1/tenants",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                TenantResponse.class
        );
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID tenantId = created.getBody().id();

        // GET by ID
        ResponseEntity<TenantResponse> response = restTemplate.exchange(
                "/api/v1/tenants/" + tenantId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                TenantResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(tenantId);
        assertThat(response.getBody().name()).isEqualTo("Retrievable Tenant");
        assertThat(response.getBody().slug()).isEqualTo(slug);
    }

    @Test
    void test_getTenant_notFound() {
        HttpHeaders headers = authHelper.adminHeaders();
        UUID fakeId = UUID.randomUUID();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/tenants/" + fakeId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void test_updateTenant_selfTenant_ok() {
        // Admin PUT on their OWN tenant must succeed. Per D15, PLATFORM_ADMIN is
        // tenant-scoped (see openspec multi-tenant-production-readiness Phase M
        // platform-admin-tenant-scoping-v0.48 + Phase D TenantPathGuard D11).
        HttpHeaders headers = authHelper.adminHeaders();
        UUID ownTenantId = authHelper.getTestTenantId();

        // Read the original name so we can restore it (setupTestTenant is idempotent
        // across tests via findBySlug — we must not leave a mutated name behind).
        ResponseEntity<TenantResponse> before = restTemplate.exchange(
                "/api/v1/tenants/" + ownTenantId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                TenantResponse.class);
        assertThat(before.getStatusCode()).isEqualTo(HttpStatus.OK);
        String originalName = before.getBody().name();

        try {
            ResponseEntity<TenantResponse> response = restTemplate.exchange(
                    "/api/v1/tenants/" + ownTenantId,
                    HttpMethod.PUT,
                    new HttpEntity<>("{\"name\": \"Updated Name\"}", headers),
                    TenantResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().name()).isEqualTo("Updated Name");
            assertThat(response.getBody().id()).isEqualTo(ownTenantId);
        } finally {
            // Restore so other tests see the setup-time name
            restTemplate.exchange(
                    "/api/v1/tenants/" + ownTenantId,
                    HttpMethod.PUT,
                    new HttpEntity<>("{\"name\": \"" + originalName + "\"}", headers),
                    TenantResponse.class);
        }
    }

    @Test
    void test_updateTenant_otherTenant_rejectedBy_D11_guard() {
        // D11 URL-path-sink: an admin in tenant A creates tenant B (bootstrap
        // path is still open under D15), but then cannot rename B via
        // PUT /api/v1/tenants/{B} — the TenantPathGuard rejects with 404
        // (symmetric with D3 existence-leak posture). Phase F will revisit
        // with a dedicated platform-operator role.
        HttpHeaders headers = authHelper.adminHeaders();

        String slug = "update-rejected-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<TenantResponse> created = restTemplate.exchange(
                "/api/v1/tenants",
                HttpMethod.POST,
                new HttpEntity<>("{\"name\": \"Foreign Name\", \"slug\": \"" + slug + "\"}", headers),
                TenantResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID foreignTenantId = created.getBody().id();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/tenants/" + foreignTenantId,
                HttpMethod.PUT,
                new HttpEntity<>("{\"name\": \"Attacker Name\"}", headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void test_tenantConfig_getDefaults() {
        HttpHeaders headers = authHelper.adminHeaders();
        UUID tenantId = authHelper.getTestTenantId();

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/tenants/" + tenantId + "/config",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        // Default config includes default_locale=en (always present)
        // api_key_auth_enabled may have been modified by another test, so just verify the key exists
        assertThat(response.getBody()).containsKey("api_key_auth_enabled");
        assertThat(response.getBody()).containsKey("default_locale");
        assertThat(response.getBody().get("default_locale")).isEqualTo("en");
    }

    @Test
    @SuppressWarnings("unchecked")
    void test_tenantConfig_update() {
        HttpHeaders headers = authHelper.adminHeaders();
        UUID tenantId = authHelper.getTestTenantId();

        String configBody = """
                {"api_key_auth_enabled": false, "custom_setting": "my_value"}
                """;

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/tenants/" + tenantId + "/config",
                HttpMethod.PUT,
                new HttpEntity<>(configBody, headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        // Updated values override defaults
        assertThat(response.getBody().get("api_key_auth_enabled")).isEqualTo(false);
        assertThat(response.getBody().get("custom_setting")).isEqualTo("my_value");
        // Default values still present for keys not overridden
        assertThat(response.getBody().get("default_locale")).isEqualTo("en");
    }

    @Test
    void test_tenantIsolation_separateTenants() {
        HttpHeaders headers = authHelper.adminHeaders();

        // Create two separate tenants
        String slugA = "tenant-a-" + UUID.randomUUID().toString().substring(0, 8);
        String slugB = "tenant-b-" + UUID.randomUUID().toString().substring(0, 8);

        ResponseEntity<TenantResponse> tenantA = restTemplate.exchange(
                "/api/v1/tenants",
                HttpMethod.POST,
                new HttpEntity<>("""
                        {"name": "Tenant A", "slug": "%s"}
                        """.formatted(slugA), headers),
                TenantResponse.class
        );

        ResponseEntity<TenantResponse> tenantB = restTemplate.exchange(
                "/api/v1/tenants",
                HttpMethod.POST,
                new HttpEntity<>("""
                        {"name": "Tenant B", "slug": "%s"}
                        """.formatted(slugB), headers),
                TenantResponse.class
        );

        assertThat(tenantA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(tenantB.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Verify each tenant has its own ID and they are different
        assertThat(tenantA.getBody().id()).isNotEqualTo(tenantB.getBody().id());
        assertThat(tenantA.getBody().slug()).isNotEqualTo(tenantB.getBody().slug());

        // Verify each tenant can be retrieved independently
        ResponseEntity<TenantResponse> fetchA = restTemplate.exchange(
                "/api/v1/tenants/" + tenantA.getBody().id(),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                TenantResponse.class
        );
        assertThat(fetchA.getBody().slug()).isEqualTo(slugA);

        ResponseEntity<TenantResponse> fetchB = restTemplate.exchange(
                "/api/v1/tenants/" + tenantB.getBody().id(),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                TenantResponse.class
        );
        assertThat(fetchB.getBody().slug()).isEqualTo(slugB);
    }
}
