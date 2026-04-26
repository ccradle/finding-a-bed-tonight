package org.fabt.tenant.api;

import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W-A4-2 — controller IT for {@link TenantKeyRotationController}. Covers the
 * thin slice the service IT can't reach: the {@code @PreAuthorize} role
 * gate, the 202 response shape, and the actor extraction from the JWT.
 *
 * <p>The service-layer IT ({@code TenantKeyRotationServiceIntegrationTest})
 * already validates the rotation behavior end-to-end against Postgres.
 * This file's purpose is to confirm the HTTP surface enforces what it
 * claims: only PLATFORM_ADMIN can trigger; success returns 202 with the
 * documented body shape.
 */
class TenantKeyRotationControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private org.fabt.auth.service.JwtService jwtService;

    private UUID tenantA;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        tenantA = authHelper.getTestTenantId();
    }

    @Test
    @DisplayName("PLATFORM_OPERATOR gets 202 + rotation summary body")
    void platformOperatorTriggers202() {
        // Bootstrap tenantA's first kid by issuing a token for any user
        // (so the first generation exists in tenant_jwt_kid before rotation).
        User seed = authHelper.createUserInTenant(tenantA,
                "kid-seed-" + UUID.randomUUID() + "@test.fabt.org",
                "Kid Seed", new String[]{"COC_ADMIN"}, false);
        jwtService.generateAccessToken(seed);

        // G-4.4: endpoint migrated to PLATFORM_OPERATOR + @PlatformAdminOnly.
        HttpHeaders headers = authHelper.platformOperatorHeaders(
                "G-4.4 IT - rotate JWT key for tenant " + tenantA);
        ResponseEntity<java.util.Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/admin/tenants/" + tenantA + "/rotate-jwt-key",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                new org.springframework.core.ParameterizedTypeReference<>() {});

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("rotated", response.getBody().get("status"));
        assertEquals(tenantA.toString(), response.getBody().get("tenantId"));
        assertNotNull(response.getBody().get("oldGeneration"));
        assertNotNull(response.getBody().get("newGeneration"));
        Integer oldGen = ((Number) response.getBody().get("oldGeneration")).intValue();
        Integer newGen = ((Number) response.getBody().get("newGeneration")).intValue();
        assertEquals(oldGen + 1, newGen,
                "newGeneration must be oldGeneration + 1");
        assertNotNull(response.getBody().get("rotatedAt"));
    }

    @Test
    @DisplayName("COC_ADMIN gets 403 — endpoint is PLATFORM_OPERATOR-only")
    void cocAdminGets403() {
        User cocAdmin = authHelper.createUserInTenant(tenantA,
                "coc-admin-" + UUID.randomUUID() + "@test.fabt.org",
                "Tenant CoC Admin", new String[]{"COC_ADMIN"}, false);

        // G-4.4: provide justification so JustificationValidationFilter doesn't
        // 400 first; the security/aspect rejection (403) is what we want.
        HttpHeaders headers = authHelper.withJustification(
                authHelper.headersForUser(cocAdmin),
                "G-4.4 IT - COC_ADMIN must not be able to rotate keys");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/tenants/" + tenantA + "/rotate-jwt-key",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "COC_ADMIN must NOT be able to trigger JWT rotation; "
                + "@PreAuthorize gate should have blocked. Body: " + response.getBody());
    }

    @Test
    @DisplayName("Anonymous (no auth header) gets 401")
    void anonymousGets401() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/tenants/" + tenantA + "/rotate-jwt-key",
                HttpMethod.POST,
                new HttpEntity<>(new HttpHeaders()),
                String.class);

        assertTrue(response.getStatusCode() == HttpStatus.UNAUTHORIZED
                        || response.getStatusCode() == HttpStatus.FORBIDDEN,
                "anonymous request must be 401 Unauthorized (or 403 if security "
                + "filter chain returns 403 for anonymous on protected endpoints); "
                + "actual: " + response.getStatusCode());
    }

    @Test
    @DisplayName("OUTREACH_WORKER role gets 403")
    void outreachWorkerGets403() {
        User outreach = authHelper.createUserInTenant(tenantA,
                "outreach-" + UUID.randomUUID() + "@test.fabt.org",
                "Outreach Worker", new String[]{"OUTREACH_WORKER"}, false);

        HttpHeaders headers = authHelper.withJustification(
                authHelper.headersForUser(outreach),
                "G-4.4 IT - OUTREACH_WORKER must not be able to rotate keys");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/tenants/" + tenantA + "/rotate-jwt-key",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "OUTREACH_WORKER must NOT trigger rotation");
    }
}
