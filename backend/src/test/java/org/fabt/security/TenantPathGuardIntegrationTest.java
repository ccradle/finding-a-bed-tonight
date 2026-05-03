package org.fabt.security;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.auth.domain.User;
import org.fabt.auth.repository.UserRepository;
import org.fabt.auth.service.JwtService;
import org.fabt.auth.service.PasswordService;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase D task 5.9 — verifies the TenantPathGuard URL-path-sink enforcement
 * (design D11) rejects cross-tenant write attempts with HTTP 404 across
 * every affected controller.
 *
 * <p>Threat model: a PLATFORM_ADMIN authenticated against Tenant A sends a
 * request whose URL path carries Tenant B's UUID (e.g.
 * {@code PUT /api/v1/tenants/<B-uuid>/config}). Without the guard, services
 * would act on Tenant B's row using Tenant A's credentials — a cross-tenant
 * write via URL manipulation. The guard throws {@code NoSuchElementException}
 * before the service is invoked, and the GlobalExceptionHandler renders it
 * as {@code 404 Not Found}. A 403 would signal "exists but forbidden";
 * 404 preserves the existence-leak posture symmetric with D3 reads.
 *
 * <p>This is the primary regression guard for Phase D. Every PUT/GET/POST/
 * DELETE under {@code /api/v1/tenants/{tenantId}/...} must be covered here.
 */
class TenantPathGuardIntegrationTest extends BaseIntegrationTest {

    @Autowired private TenantService tenantService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordService passwordService;
    @Autowired private JwtService jwtService;

    private Tenant tenantA;
    private Tenant tenantB;
    private String tenantAToken;
    private String tenantBToken;

    @BeforeEach
    void setUp() {
        String suffixA = UUID.randomUUID().toString().substring(0, 8);
        String suffixB = UUID.randomUUID().toString().substring(0, 8);
        tenantA = tenantService.findBySlug("pg-a-" + suffixA)
                .orElseGet(() -> tenantService.create("PathGuard Tenant A", "pg-a-" + suffixA));
        tenantB = tenantService.findBySlug("pg-b-" + suffixB)
                .orElseGet(() -> tenantService.create("PathGuard Tenant B", "pg-b-" + suffixB));

        User adminA = createAdmin(tenantA.getId(), "admin-a-" + suffixA + "@test.fabt.org");
        User adminB = createAdmin(tenantB.getId(), "admin-b-" + suffixB + "@test.fabt.org");
        tenantAToken = jwtService.generateAccessToken(adminA);
        tenantBToken = jwtService.generateAccessToken(adminB);
    }

    @Test
    @DisplayName("PUT /tenants/{B}/config from Tenant A → 404 (D11 URL-path-sink)")
    void crossTenantUpdateConfig_returns404() {
        ResponseEntity<String> response = put(
                "/api/v1/tenants/" + tenantB.getId() + "/config",
                "{\"foo\":\"bar\"}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /tenants/{B}/config from Tenant A → 404")
    void crossTenantGetConfig_returns404() {
        ResponseEntity<String> response = get("/api/v1/tenants/" + tenantB.getId() + "/config");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("PUT /tenants/{B} from Tenant A → 403 (G-4.4: now @PlatformAdminOnly)")
    void crossTenantUpdateName_returnsForbidden() {
        // G-4.4: PUT /tenants/{id} migrated to @PlatformAdminOnly, so a tenant-
        // scoped JWT (even with COC_ADMIN/PLATFORM_ADMIN-legacy) cannot reach
        // it — security rejects with 403 BEFORE any path guard could fire. The
        // cross-tenant attack is structurally prevented by role separation:
        // PLATFORM_OPERATOR is the only role that can manage tenants, and it
        // is a platform-scoped role with no tenant context to "cross from".
        // Justification header satisfies the JustificationValidationFilter
        // (which fires pre-Security in this context); the 403 we expect comes
        // from Spring Security after method @PreAuthorize evaluates.
        ResponseEntity<String> response = put(
                "/api/v1/tenants/" + tenantB.getId(),
                "{\"name\":\"Attacker-renamed\"}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * The old GET/PUT /api/v1/tenants/{id}/observability endpoints were
     * removed (platform-observability-split §5.5). Platform-wide config now
     * lives at /api/v1/platform/observability (PLATFORM_OPERATOR only).
     * Cross-tenant access to the per-tenant /surge-threshold endpoint
     * returns 404 (TenantPathGuard design D3: existence-leak prevention).
     */
    @Test
    @DisplayName("PUT /tenants/{B}/surge-threshold from Tenant A → 404")
    void crossTenantUpdateSurgeThreshold_returnsNotFound() {
        ResponseEntity<String> response = put(
                "/api/v1/tenants/" + tenantB.getId() + "/surge-threshold",
                "{\"temperature_threshold_f\":40.0}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /tenants/{B}/surge-threshold from Tenant A → 404")
    void crossTenantGetSurgeThreshold_returnsNotFound() {
        ResponseEntity<String> response = get("/api/v1/tenants/" + tenantB.getId() + "/surge-threshold");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /tenants/{B}/oauth2-providers from Tenant A → 404")
    void crossTenantListOauth2Providers_returns404() {
        ResponseEntity<String> response = get(
                "/api/v1/tenants/" + tenantB.getId() + "/oauth2-providers");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /tenants/{B}/oauth2-providers from Tenant A → 404 (create)")
    void crossTenantCreateOauth2Provider_returns404() {
        ResponseEntity<String> response = post(
                "/api/v1/tenants/" + tenantB.getId() + "/oauth2-providers",
                """
                {
                    "providerName": "attacker-google",
                    "clientId": "attacker-client-id",
                    "clientSecret": "attacker-secret",
                    "issuerUri": "https://accounts.google.com"
                }
                """);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Control: Tenant A PUT on its own /tenants/{A}/config → 200 (guard allows match)")
    void sameTenantUpdateConfig_returns200() {
        ResponseEntity<String> response = put(
                "/api/v1/tenants/" + tenantA.getId() + "/config",
                "{\"test-marker\":\"self-update-ok\"}");
        assertThat(response.getStatusCode())
                .as("Legitimate same-tenant PUT must pass the guard (response body: %s)", response.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("PUT /tenants/{B}/dv-address-policy from Tenant A → 403 (G-4.4: @PlatformAdminOnly)")
    void crossTenantUpdateDvAddressPolicy_returnsForbidden() {
        HttpHeaders headers = authHeaders();
        headers.set("X-Confirm-Policy-Change", "CONFIRM");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/tenants/" + tenantB.getId() + "/dv-address-policy",
                HttpMethod.PUT,
                new HttpEntity<>("{\"policy\":\"NONE\"}", headers),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * Nested-resource cross-tenant attack — the scenario warroom concern #1
     * flagged. Attacker uses their OWN tenant in the URL path (so
     * {@link TenantPathGuard} passes) but references another tenant's
     * providerId. The controller guard alone cannot catch this; the
     * service layer must also enforce tenant-scoped lookup.
     *
     * <p>Verifies {@code TenantOAuth2ProviderService.findByIdOrThrow} uses
     * {@code findByIdAndTenantId(id, TenantContext.getTenantId())}, which
     * returns empty → 404 when the provider belongs to another tenant.
     * If this test fails, the service-layer guard is missing and the
     * guard helper is NOT sufficient defence-in-depth.
     */
    @Test
    @DisplayName("PUT /tenants/{A}/oauth2-providers/{B-provider} → 404 (nested-resource attack, service-layer guard)")
    void nestedResourceCrossTenantUpdate_returns404() {
        UUID bProviderId = createOAuth2ProviderUnder(tenantBToken, tenantB.getId(), "nested-attack-target");

        ResponseEntity<String> response = put(
                "/api/v1/tenants/" + tenantA.getId() + "/oauth2-providers/" + bProviderId,
                """
                {
                    "clientId": "attacker-hijacked",
                    "clientSecret": "attacker-secret",
                    "issuerUri": "https://evil.example.com",
                    "enabled": true
                }
                """);
        assertThat(response.getStatusCode())
                .as("TenantPathGuard passes (A==A) but service-layer findByIdAndTenantId must reject B's provider. Response: %s", response.getBody())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("DELETE /tenants/{A}/oauth2-providers/{B-provider} → 404 (nested-resource attack)")
    void nestedResourceCrossTenantDelete_returns404() {
        UUID bProviderId = createOAuth2ProviderUnder(tenantBToken, tenantB.getId(), "nested-delete-target");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/tenants/" + tenantA.getId() + "/oauth2-providers/" + bProviderId,
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders()),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- helpers ---

    private UUID createOAuth2ProviderUnder(String ownerToken, UUID ownerTenantId, String providerName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ownerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {
                    "providerName": "%s",
                    "clientId": "test-client-id",
                    "clientSecret": "test-secret-value",
                    "issuerUri": "https://accounts.google.com"
                }
                """.formatted(providerName);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/tenants/" + ownerTenantId + "/oauth2-providers",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
        assertThat(response.getStatusCode())
                .as("Owner POST to create provider must succeed (response body: %s)", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        Object idRaw = response.getBody().get("id");
        return UUID.fromString(idRaw.toString());
    }

    private ResponseEntity<String> get(String path) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
    }

    private ResponseEntity<String> put(String path, String body) {
        return restTemplate.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, authHeaders()), String.class);
    }

    private ResponseEntity<String> post(String path, String body) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, authHeaders()), String.class);
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenantAToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        // G-4.4: include justification so the JustificationValidationFilter
        // doesn't 400 before security can evaluate role. Tests asserting 403
        // (cross-tenant via tenant JWT) need security/aspect to be the gate
        // that fires, not the filter.
        headers.set("X-Platform-Justification",
                "TenantPathGuard IT - cross-tenant attack scenario, tenant JWT must not reach platform endpoint");
        return headers;
    }

    private User createAdmin(UUID tenantId, String email) {
        return userRepository.findByTenantIdAndEmail(tenantId, email)
                .orElseGet(() -> {
                    User user = new User();
                    user.setTenantId(tenantId);
                    user.setEmail(email);
                    user.setDisplayName("PathGuard Admin " + email);
                    user.setPasswordHash(passwordService.hash("TestPassword123!"));
                    user.setRoles(new String[]{"PLATFORM_ADMIN", "COC_ADMIN"});
                    user.setDvAccess(false);
                    user.setCreatedAt(Instant.now());
                    user.setUpdatedAt(Instant.now());
                    return userRepository.save(user);
                });
    }
}
