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
    void test_updateTenant() {
        HttpHeaders headers = authHelper.adminHeaders();

        // Create a tenant to update
        String slug = "update-test-" + UUID.randomUUID().toString().substring(0, 8);
        String createBody = """
                {"name": "Original Name", "slug": "%s"}
                """.formatted(slug);

        ResponseEntity<TenantResponse> created = restTemplate.exchange(
                "/api/v1/tenants",
                HttpMethod.POST,
                new HttpEntity<>(createBody, headers),
                TenantResponse.class
        );
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID tenantId = created.getBody().id();

        // Update the tenant
        String updateBody = """
                {"name": "Updated Name"}
                """;

        ResponseEntity<TenantResponse> response = restTemplate.exchange(
                "/api/v1/tenants/" + tenantId,
                HttpMethod.PUT,
                new HttpEntity<>(updateBody, headers),
                TenantResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().name()).isEqualTo("Updated Name");
        assertThat(response.getBody().slug()).isEqualTo(slug);
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
