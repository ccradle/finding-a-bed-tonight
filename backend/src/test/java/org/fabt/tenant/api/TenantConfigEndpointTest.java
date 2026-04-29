package org.fabt.tenant.api;

import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.tenant.domain.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link TenantConfigController} covering the
 * URL-rule + {@code @PreAuthorize} alignment that broke silently between
 * G-4.4 (2026-04 platform-scope split) and slice 4 12 of the
 * transitional-reentry-support change.
 *
 * <p><b>Background of the latent bug.</b> The G-4.4 refactor migrated most
 * tenant-management endpoints to {@code PLATFORM_OPERATOR} scope and added
 * a catch-all URL rule
 * .requestMatchers("/api/v1/tenants/**").hasAnyRole("PLATFORM_OPERATOR", "PLATFORM_ADMIN").
 * {@link TenantConfigController} kept its class-level
 * {@code @PreAuthorize("hasRole('COC_ADMIN')")} but the URL rule
 * short-circuits ahead of the method gate (Spring matchers are
 * first-match-wins), so a COC_ADMIN reading their own tenant's config
 * received 403. This was invisible because no integration test asserted
 * that COC_ADMIN can in fact reach the endpoint -- the controller's
 * documented role and the live filter chain had drifted apart.
 *
 * <p>The fix added a more-specific URL rule before the catch-all:
 * .requestMatchers("/api/v1/tenants/star/config").hasRole("COC_ADMIN").
 * This test class exists to prevent a future refactor from removing or
 * narrowing that exception silently. If the URL rule is removed or
 * reverted to PLATFORM_OPERATOR, {@link #cocAdmin_getConfig_returns200}
 * fails with 403 -- the failure message points at the SecurityConfig
 * row, not at the controller.
 *
 * <p>Tenant-scoping (cross-tenant abuse) is enforced by
 * {@code TenantPathGuard.requireMatchingTenant}, which is exercised
 * indirectly by every test here that uses a path-tenantId matching the
 * caller's JWT-bound tenant. A dedicated cross-tenant test would require
 * a second tenant fixture and is deferred -- the same guard is already
 * covered by {@code CrossTenantIsolationTest} and
 * {@code ReservationConfigController}'s existing scoping tests.
 */
@DisplayName("/api/v1/tenants/{id}/config -- URL-rule + @PreAuthorize alignment")
class TenantConfigEndpointTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        String slug = "tenant-config-" + UUID.randomUUID().toString().substring(0, 8);
        Tenant t = authHelper.setupTestTenant(slug);
        tenantId = t.getId();
        authHelper.setupCocAdminUser();
        authHelper.setupCoordinatorUser();
        authHelper.setupOutreachWorkerUser();
    }

    @Test
    @DisplayName("COC_ADMIN GET -- REGRESSION: URL rule must let COC_ADMIN reach the method gate")
    void cocAdmin_getConfig_returns200() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/tenants/" + tenantId + "/config",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.cocAdminHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode())
                .as("COC_ADMIN reading their own tenant's config MUST return 200. "
                        + "If this fails with 403, SecurityConfig's specific "
                        + "tenants-config exception rule has been removed or narrowed; "
                        + "the catch-all /api/v1/tenants/** rule short-circuits the "
                        + "controller's @PreAuthorize. This regression broke the "
                        + "slice 4 admin ReservationSettings panel (load-failed banner "
                        + "rendering instead of the editor).")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("COC_ADMIN PUT -- write path must also work end-to-end")
    void cocAdmin_putConfig_returns200() {
        Map<String, Object> body = Map.of("hold_duration_minutes", 120, "feature_x", true);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/tenants/" + tenantId + "/config",
                HttpMethod.PUT,
                new HttpEntity<>(body, authHelper.cocAdminHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("PUT response echoes the saved config")
                .containsEntry("hold_duration_minutes", 120)
                .containsEntry("feature_x", true);
    }

    @Test
    @DisplayName("COORDINATOR GET -- 403 (method-level @PreAuthorize forbids non-COC_ADMIN)")
    void coordinator_getConfig_returns403() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/tenants/" + tenantId + "/config",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode())
                .as("COORDINATOR is admitted by the URL rule "
                        + "(/api/v1/tenants/*/config -> COC_ADMIN means anyone with "
                        + "lower role gets 403 from the URL rule itself, but the "
                        + "intent of the role hierarchy means the method gate is "
                        + "the authoritative reason for rejection).")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("OUTREACH_WORKER GET -- 403")
    void outreachWorker_getConfig_returns403() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/tenants/" + tenantId + "/config",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.outreachWorkerHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("unauthenticated GET -- 401")
    void unauthenticated_getConfig_returns401() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/tenants/" + tenantId + "/config",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
