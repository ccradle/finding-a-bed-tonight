package org.fabt.auth;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2ProviderTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        authHelper.setupCocAdminUser();
    }

    @Test
    void test_createProvider_asPlatformAdmin_returnsCreated() {
        HttpHeaders headers = authHelper.adminHeaders();
        UUID tenantId = authHelper.getTestTenantId();

        String body = """
                {"providerName": "google", "clientId": "google-client-id", "clientSecret": "google-secret", "issuerUri": "https://accounts.google.com"}
                """;

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/tenants/" + tenantId + "/oauth2-providers",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("google");
        // Response should NOT contain the client secret
        assertThat(response.getBody()).doesNotContain("google-secret");
    }

    @Test
    void test_createProvider_asCocAdmin_returnsCreated() {
        HttpHeaders headers = authHelper.cocAdminHeaders();
        UUID tenantId = authHelper.getTestTenantId();

        String body = """
                {"providerName": "microsoft", "clientId": "ms-client-id", "clientSecret": "ms-secret", "issuerUri": "https://login.microsoftonline.com/common/v2.0"}
                """;

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/tenants/" + tenantId + "/oauth2-providers",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void test_listProviders_returnsAll() {
        HttpHeaders headers = authHelper.adminHeaders();
        UUID tenantId = authHelper.getTestTenantId();

        // Create a provider first
        String body = """
                {"providerName": "github", "clientId": "gh-client-id", "clientSecret": "gh-secret", "issuerUri": "https://github.com/login/oauth"}
                """;
        restTemplate.exchange(
                "/api/v1/tenants/" + tenantId + "/oauth2-providers",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        // List providers
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/tenants/" + tenantId + "/oauth2-providers",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("github");
    }

    @Test
    void test_publicProviderList_noAuthRequired() {
        HttpHeaders headers = authHelper.adminHeaders();
        UUID tenantId = authHelper.getTestTenantId();
        String slug = authHelper.getTestTenantSlug();

        // Create a provider
        String body = """
                {"providerName": "google-public", "clientId": "pub-client-id", "clientSecret": "pub-secret", "issuerUri": "https://accounts.google.com"}
                """;
        restTemplate.exchange(
                "/api/v1/tenants/" + tenantId + "/oauth2-providers",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        // Access public endpoint WITHOUT authentication
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/tenants/" + slug + "/oauth2-providers/public",
                HttpMethod.GET,
                null,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("google-public");
        // Should NOT contain secrets or client IDs
        assertThat(response.getBody()).doesNotContain("pub-client-id");
        assertThat(response.getBody()).doesNotContain("pub-secret");
    }

    @Test
    void test_publicProviderList_unknownTenant_returnsEmptyList() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/tenants/nonexistent-slug/oauth2-providers/public",
                HttpMethod.GET,
                null,
                String.class
        );

        // Should return 200 with empty list (not 404 — don't leak tenant existence)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("[]");
    }

    @Test
    void test_coordinatorCannotManageProviders() {
        authHelper.setupCoordinatorUser();
        HttpHeaders headers = authHelper.coordinatorHeaders();
        UUID tenantId = authHelper.getTestTenantId();

        String body = """
                {"providerName": "blocked", "clientId": "x", "clientSecret": "x", "issuerUri": "https://x.com"}
                """;

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/tenants/" + tenantId + "/oauth2-providers",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
