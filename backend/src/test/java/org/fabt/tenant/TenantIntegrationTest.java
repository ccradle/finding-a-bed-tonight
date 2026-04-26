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

    /**
     * G-4.4: /api/v1/tenants/* is @PlatformAdminOnly + PLATFORM_OPERATOR.
     * Each test gets a fresh platform-operator JWT via the bootstrap fixture.
     */
    private HttpHeaders platformHeaders(String purpose) {
        return authHelper.platformOperatorHeaders("Tenant IT - " + purpose);
    }

    @Test
    void test_createTenant_success() {
        HttpHeaders headers = platformHeaders("create tenant happy-path");
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
        HttpHeaders headers = platformHeaders("create tenant duplicate-slug rejection");

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
        HttpHeaders headers = platformHeaders("get tenant by id happy-path");

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
        HttpHeaders headers = platformHeaders("get tenant not-found shape");
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
        // G-4.4: PUT /tenants/{id} is now @PlatformAdminOnly. The D15
        // "platform-admin is tenant-scoped" model is superseded by the
        // platform-operator role: PLATFORM_OPERATOR can manage any tenant
        // (the cross-tenant invariant is now enforced by role separation
        // and audit chain, not by TenantPathGuard).
        HttpHeaders headers = platformHeaders("update tenant happy-path");
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
    void test_updateTenant_otherTenant_okForPlatformOperator() {
        // G-4.4: PLATFORM_OPERATOR can manage any tenant by design. The D11
        // URL-path-sink concern is now structurally mitigated:
        //   - PLATFORM_OPERATOR (the only role that can hit this endpoint) is
        //     intentionally cross-tenant — a "foreign" tenant simply isn't
        //     foreign to the operator.
        //   - tenant-scoped JWTs (COC_ADMIN/etc.) cannot reach the endpoint at
        //     all — Spring Security 403 on the @PreAuthorize gate.
        // The audit chain (PAL row + audit_event) captures the exact target
        // tenantId for compliance review, replacing the per-request guard.
        HttpHeaders headers = platformHeaders("update foreign tenant - platform-operator legitimate cross-tenant action");

        String slug = "update-rejected-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<TenantResponse> created = restTemplate.exchange(
                "/api/v1/tenants",
                HttpMethod.POST,
                new HttpEntity<>("{\"name\": \"Foreign Name\", \"slug\": \"" + slug + "\"}", headers),
                TenantResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID foreignTenantId = created.getBody().id();

        ResponseEntity<TenantResponse> response = restTemplate.exchange(
                "/api/v1/tenants/" + foreignTenantId,
                HttpMethod.PUT,
                new HttpEntity<>("{\"name\": \"Renamed By Operator\"}", headers),
                TenantResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().name()).isEqualTo("Renamed By Operator");
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
        HttpHeaders headers = platformHeaders("isolation: create + verify two tenants");

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
