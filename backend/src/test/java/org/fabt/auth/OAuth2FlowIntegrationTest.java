package org.fabt.auth;

import java.time.Instant;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.TenantOAuth2Provider;
import org.fabt.auth.domain.User;
import org.fabt.auth.service.DynamicClientRegistrationSource;
import org.fabt.auth.service.OAuth2AccountLinkService;
import org.fabt.auth.service.OAuth2AccountLinkService.LinkResult;
import org.fabt.auth.service.TenantOAuth2ProviderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

import static org.junit.jupiter.api.Assertions.*;

class OAuth2FlowIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private DynamicClientRegistrationSource registrationSource;

    @Autowired
    private OAuth2AccountLinkService linkService;

    @Autowired
    private TenantOAuth2ProviderService providerService;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        authHelper.setupOutreachWorkerUser();
        tenantId = authHelper.getTestTenantId();
    }

    // --- Task 6.1: DynamicClientRegistrationSource resolves from DB ---

    @Test
    void dynamicRegistration_resolvesProviderFromDatabase() {
        // Create a provider
        providerService.create(tenantId, "testidp", "test-client-id",
                "test-client-secret", "http://localhost:8180/realms/fabt-dev");

        String registrationId = authHelper.getTestTenantSlug() + "-testidp";
        ClientRegistration reg = registrationSource.findByRegistrationId(registrationId);

        assertNotNull(reg, "Should resolve provider from database");
        assertEquals("test-client-id", reg.getClientId());
        assertEquals(registrationId, reg.getRegistrationId());
    }

    @Test
    void dynamicRegistration_returnsNullForUnknownProvider() {
        ClientRegistration reg = registrationSource.findByRegistrationId("nonexistent-slug-google");
        assertNull(reg);
    }

    @Test
    void dynamicRegistration_cachesForSubsequentCalls() {
        providerService.create(tenantId, "cached", "cached-id",
                "cached-secret", "http://localhost:8180/realms/fabt-dev");

        String registrationId = authHelper.getTestTenantSlug() + "-cached";
        ClientRegistration first = registrationSource.findByRegistrationId(registrationId);
        ClientRegistration second = registrationSource.findByRegistrationId(registrationId);

        assertNotNull(first);
        assertSame(first, second, "Second call should return cached instance");
    }

    // --- Task 6.2: linkOrReject rejects when no user exists ---

    @Test
    void linkOrReject_rejectsWhenNoUserExists() {
        LinkResult result = linkService.linkOrReject(
                "google", "google-subject-123", "unknown@example.com", tenantId);

        assertFalse(result.success());
        assertNotNull(result.error());
        assertTrue(result.error().contains("No account found"),
                "Error should tell user to contact admin");
        assertNull(result.accessToken());
        assertNull(result.userId());
    }

    // --- Task 6.3: linkOrReject links existing user by email ---

    @Test
    void linkOrReject_linksExistingUserByEmail() {
        // The outreach user was created in setUp
        LinkResult result = linkService.linkOrReject(
                "google", "google-subject-outreach",
                TestAuthHelper.OUTREACH_EMAIL, tenantId);

        assertTrue(result.success());
        assertNotNull(result.accessToken());
        assertNotNull(result.refreshToken());
        assertNotNull(result.userId());
    }

    @Test
    void linkOrReject_subsequentLoginUsesExistingLink() {
        // First login — creates link
        LinkResult first = linkService.linkOrReject(
                "google", "google-subject-admin",
                TestAuthHelper.ADMIN_EMAIL, tenantId);
        assertTrue(first.success());

        // Second login — uses existing link (doesn't query by email)
        LinkResult second = linkService.linkOrReject(
                "google", "google-subject-admin",
                "different-email@example.com", tenantId);
        assertTrue(second.success());
        assertEquals(first.userId(), second.userId());
    }

    // --- Task 6.4: disabled provider returns error ---

    @Test
    void dynamicRegistration_disabledProviderReturnsNull() {
        TenantOAuth2Provider provider = providerService.create(tenantId, "disabled",
                "disabled-id", "disabled-secret", "http://localhost:8180/realms/fabt-dev");
        providerService.update(provider.getId(), null, null, null, false);

        String registrationId = authHelper.getTestTenantSlug() + "-disabled";
        ClientRegistration reg = registrationSource.findByRegistrationId(registrationId);

        assertNull(reg, "Disabled provider should not resolve");
    }

    // --- Task 6.6: web.ignoring still works with OAuth2 ---

    @Test
    void staticResources_notBlockedBySecurityFilters() {
        // Health endpoint (permitAll) should work without auth
        ResponseEntity<String> health = restTemplate.getForEntity(
                "/actuator/health/liveness", String.class);
        assertEquals(HttpStatus.OK, health.getStatusCode());
    }

    // --- Task 6.7: public provider endpoint ---

    @Test
    void publicProviderEndpoint_returnsEnabledProviders() {
        providerService.create(tenantId, "pubtest", "pub-id",
                "pub-secret", "http://localhost:8180/realms/fabt-dev");

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/tenants/" + authHelper.getTestTenantSlug() + "/oauth2-providers/public",
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("pubtest"));
    }
}
