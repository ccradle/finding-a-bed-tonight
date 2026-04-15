package org.fabt.auth;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.tenant.domain.Tenant;
import org.fabt.auth.domain.User;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2ProviderTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    // ------------------------------------------------------------------
    // cross-tenant-isolation-audit (Issue #117) — Phase 2 task 2.1.5.
    // Two regression tests pinning the findByIdAndTenantId / findByIdOrThrow
    // refactor on TenantOAuth2ProviderService.update + .delete. Mirrors the
    // v0.39 DvReferralIntegrationTest.tc_*_crossTenant_returns404 pattern.
    //
    // THREAT MODEL (Marcus Webb, VULN-HIGH): pre-fix, a CoC admin in Tenant A
    // could PUT /api/v1/tenants/{anything}/oauth2-providers/{tenantB-provider-id}
    // with attacker-controlled issuerUri — effectively redirecting every OIDC
    // login for Tenant B users through an attacker-owned provider. Post-fix,
    // the service pulls tenantId from TenantContext (caller's JWT) and the
    // repository-level findByIdAndTenantId returns empty → 404, leaving
    // Tenant B's config untouched.
    // ------------------------------------------------------------------

    @Test
    void tc_update_crossTenant_returns404_leavesTenantBConfigUnchanged() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // Set up Tenant B with its own admin + OAuth2 provider configured.
        Tenant tenantB = authHelper.setupSecondaryTenant("xtenant-oauth-update-" + suffix);
        User adminB = authHelper.createUserInTenant(tenantB.getId(),
                "admin-b-oauth-update-" + suffix + "@test.fabt.org", "Tenant B Admin",
                new String[]{"PLATFORM_ADMIN", "COC_ADMIN"}, false);
        HttpHeaders adminBHeaders = authHelper.headersForUser(adminB);

        String createBody = """
                {"providerName": "azure-b", "clientId": "legitimate-client-b", "clientSecret": "legitimate-secret-b", "issuerUri": "https://login.microsoftonline.com/b/v2.0"}
                """;
        ResponseEntity<String> createResp = restTemplate.exchange(
                "/api/v1/tenants/" + tenantB.getId() + "/oauth2-providers",
                HttpMethod.POST,
                new HttpEntity<>(createBody, adminBHeaders),
                String.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID tenantBProviderId = UUID.fromString(
                createResp.getBody().replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));

        // Act: Tenant A's COC_ADMIN attempts to overwrite Tenant B's provider
        // with an attacker-controlled issuerUri — the core VULN-HIGH scenario.
        HttpHeaders tenantAHeaders = authHelper.adminHeaders();
        UUID tenantAId = authHelper.getTestTenantId();
        String maliciousUpdate = """
                {"clientId": "attacker-client", "clientSecret": "attacker-secret", "issuerUri": "https://attacker.example.com/oidc", "enabled": true}
                """;
        ResponseEntity<String> attackResp = restTemplate.exchange(
                // Path carries Tenant A in URL; Tenant B's providerId — the cross-tenant id probe.
                "/api/v1/tenants/" + tenantAId + "/oauth2-providers/" + tenantBProviderId,
                HttpMethod.PUT,
                new HttpEntity<>(maliciousUpdate, tenantAHeaders),
                String.class);

        // Assert: 404 (not 403 — 403 would confirm id exists in another tenant).
        assertThat(attackResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Defense-in-depth: verify Tenant B's provider row is byte-for-byte unchanged.
        TenantContext.runWithContext(tenantB.getId(), false, () -> {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT client_id, client_secret_encrypted, issuer_uri, enabled " +
                            "FROM tenant_oauth2_provider WHERE id = ?::uuid",
                    tenantBProviderId);
            assertThat(row.get("client_id"))
                    .as("Tenant B's clientId must be unchanged after cross-tenant PUT attempt")
                    .isEqualTo("legitimate-client-b");
            assertThat(row.get("client_secret_encrypted"))
                    .as("Tenant B's clientSecret must be unchanged after cross-tenant PUT attempt")
                    .isEqualTo("legitimate-secret-b");
            assertThat(row.get("issuer_uri"))
                    .as("Tenant B's issuerUri must be unchanged — attacker-controlled OIDC hijack blocked")
                    .isEqualTo("https://login.microsoftonline.com/b/v2.0");
            assertThat((Boolean) row.get("enabled"))
                    .as("Tenant B's enabled flag must be unchanged")
                    .isTrue();
        });
    }

    @Test
    void tc_delete_crossTenant_returns404_leavesTenantBProviderInPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Tenant tenantB = authHelper.setupSecondaryTenant("xtenant-oauth-delete-" + suffix);
        User adminB = authHelper.createUserInTenant(tenantB.getId(),
                "admin-b-oauth-delete-" + suffix + "@test.fabt.org", "Tenant B Admin",
                new String[]{"PLATFORM_ADMIN", "COC_ADMIN"}, false);
        HttpHeaders adminBHeaders = authHelper.headersForUser(adminB);

        String createBody = """
                {"providerName": "okta-b", "clientId": "okta-client-b", "clientSecret": "okta-secret-b", "issuerUri": "https://b.okta.com"}
                """;
        ResponseEntity<String> createResp = restTemplate.exchange(
                "/api/v1/tenants/" + tenantB.getId() + "/oauth2-providers",
                HttpMethod.POST,
                new HttpEntity<>(createBody, adminBHeaders),
                String.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID tenantBProviderId = UUID.fromString(
                createResp.getBody().replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));

        // Act: Tenant A's COC_ADMIN attempts to delete Tenant B's provider
        // (pre-fix: bare existsById(id) + deleteById(id) — the existsById was
        // the same defect shape the ArchUnit rule in Phase 3 forbids).
        HttpHeaders tenantAHeaders = authHelper.adminHeaders();
        UUID tenantAId = authHelper.getTestTenantId();
        ResponseEntity<String> attackResp = restTemplate.exchange(
                "/api/v1/tenants/" + tenantAId + "/oauth2-providers/" + tenantBProviderId,
                HttpMethod.DELETE,
                new HttpEntity<>(tenantAHeaders),
                String.class);

        assertThat(attackResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Defense-in-depth: Tenant B's provider row still exists.
        TenantContext.runWithContext(tenantB.getId(), false, () -> {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tenant_oauth2_provider WHERE id = ?::uuid",
                    Integer.class, tenantBProviderId);
            assertThat(count)
                    .as("Tenant B's provider row must remain after cross-tenant DELETE attempt")
                    .isEqualTo(1);
        });
    }

    // ------------------------------------------------------------------
    // Phase 2.1 addendum (task 2.1.7) — URL-path-sink class per design D11.
    // Pre-fix, a CoC admin in Tenant A sending POST /api/v1/tenants/{tenantB}/
    // oauth2-providers with a malicious body would create a provider row UNDER
    // TENANT B with attacker-controlled issuerUri — the stealthiest of the
    // three OAuth2 vulnerabilities since there's no pre-existing row to diff
    // against. Post-fix, the controller validates URL {tenantId} matches
    // TenantContext.getTenantId() and returns 404 on mismatch before the
    // service is invoked; the service itself sources tenantId from
    // TenantContext so even a bypass of the controller guard cannot reach
    // cross-tenant.
    // ------------------------------------------------------------------

    @Test
    void tc_create_crossTenant_urlPath_returns404_noRowInsertedForTenantB() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // Set up Tenant B (empty — no OAuth2 providers configured).
        Tenant tenantB = authHelper.setupSecondaryTenant("xtenant-oauth-create-" + suffix);

        // Confirm Tenant B has zero providers before the attack.
        TenantContext.runWithContext(tenantB.getId(), false, () -> {
            Integer before = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tenant_oauth2_provider WHERE tenant_id = ?::uuid",
                    Integer.class, tenantB.getId());
            assertThat(before).as("Tenant B must start with zero providers").isZero();
        });

        // Act: Tenant A's COC_ADMIN attempts to create a provider UNDER TENANT B
        // with an attacker-controlled issuerUri — the worst shape of URL-path-sink.
        HttpHeaders tenantAHeaders = authHelper.adminHeaders();
        String maliciousCreate = """
                {"providerName": "attacker-injected", "clientId": "attacker-client", "clientSecret": "attacker-secret", "issuerUri": "https://attacker.example.com/oidc"}
                """;
        ResponseEntity<String> attackResp = restTemplate.exchange(
                "/api/v1/tenants/" + tenantB.getId() + "/oauth2-providers",
                HttpMethod.POST,
                new HttpEntity<>(maliciousCreate, tenantAHeaders),
                String.class);

        // Assert: 404 (not 403, not 201 — D3 symmetric). The URL path's
        // tenantId doesn't match the caller's JWT tenant; the controller
        // short-circuits with NoSuchElementException → 404 before the
        // service is invoked.
        assertThat(attackResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Defense-in-depth: verify NO row was inserted into Tenant B's
        // tenant_oauth2_provider. The stealthy OIDC-hijack scenario is
        // specifically "attacker creates a new row" — a successful 404
        // without this assertion could mask a downstream insertion.
        TenantContext.runWithContext(tenantB.getId(), false, () -> {
            Integer tenantBRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tenant_oauth2_provider WHERE tenant_id = ?::uuid",
                    Integer.class, tenantB.getId());
            assertThat(tenantBRows)
                    .as("Tenant B's provider row count must remain zero — no attacker injection")
                    .isZero();
        });

        // Defense-in-depth: also verify NO row was silently created in Tenant A
        // (the caller's own tenant). The fix should REJECT the request with
        // 404, not redirect the write. A silent redirect would leave an
        // attacker-named provider in the caller's tenant — confusing for
        // audit but not a cross-tenant leak; still not what we want.
        UUID tenantAId = authHelper.getTestTenantId();
        TenantContext.runWithContext(tenantAId, false, () -> {
            Integer tenantARows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tenant_oauth2_provider WHERE tenant_id = ?::uuid AND provider_name = ?",
                    Integer.class, tenantAId, "attacker-injected");
            assertThat(tenantARows)
                    .as("Caller's own tenant must have no row created from the attack attempt — 404 means rejected, not redirected")
                    .isZero();
        });
    }
}
