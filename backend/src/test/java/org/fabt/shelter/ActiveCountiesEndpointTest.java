package org.fabt.shelter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.shared.config.JsonString;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.repository.TenantRepository;
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
 * Integration tests for {@code GET /api/v1/active-counties}
 * (transitional-reentry-support slice 4 prereq, warroom H1).
 *
 * <p>Covers all four branches of the resolved-list state machine plus the
 * key authorization guarantee: an OUTREACH_WORKER (not COC_ADMIN) can read
 * this endpoint — that's the whole point of splitting it from
 * {@code TenantConfigController}.
 */
@DisplayName("GET /api/v1/active-counties — slice 4 prereq H1")
class ActiveCountiesEndpointTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private TenantRepository tenantRepository;

    private Tenant tenant;

    @BeforeEach
    void setUp() {
        String slug = "act-counties-" + UUID.randomUUID().toString().substring(0, 8);
        tenant = authHelper.setupTestTenant(slug);
        authHelper.setupOutreachWorkerUser();
        authHelper.setupCocAdminUser();
    }

    @Test
    @DisplayName("OUTREACH_WORKER can read (was 403 against /tenants/{id}/config which is COC_ADMIN-only)")
    void outreachWorker_canRead() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/active-counties",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.outreachWorkerHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode())
                .as("OUTREACH_WORKER must reach the new endpoint — that's the whole reason "
                        + "it exists separate from /tenants/{id}/config")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Branch 4: active_counties key absent → returns NC 100-county default list")
    @SuppressWarnings("unchecked")
    void keyAbsent_returnsNcDefaults() {
        // Tenant.config defaults to "{}" via TenantService.create — no active_counties key.
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/active-counties",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.outreachWorkerHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> counties = (List<String>) response.getBody().get("activeCounties");
        assertThat(counties)
                .as("absent key must fall back to NC defaults (Wake + Durham + Mecklenburg sample)")
                .contains("Wake", "Durham", "Mecklenburg");
        assertThat(counties.size())
                .as("NC defaults are 100 counties exactly")
                .isEqualTo(100);
    }

    @Test
    @DisplayName("Branch 3: explicit non-empty list → returns that list verbatim")
    @SuppressWarnings("unchecked")
    void explicitList_returnsVerbatim() {
        tenant.setConfig(new JsonString("{\"active_counties\": [\"Wake\", \"Johnston\"]}"));
        tenantRepository.save(tenant);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/active-counties",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.outreachWorkerHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> counties = (List<String>) response.getBody().get("activeCounties");
        assertThat(counties)
                .as("explicit list must come back exactly — no fallback overlay")
                .containsExactly("Wake", "Johnston");
    }

    @Test
    @DisplayName("Branch 2: explicit [] → returns empty list (UI should render free-text input)")
    @SuppressWarnings("unchecked")
    void explicitEmpty_returnsEmpty() {
        tenant.setConfig(new JsonString("{\"active_counties\": []}"));
        tenantRepository.save(tenant);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/active-counties",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.outreachWorkerHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> counties = (List<String>) response.getBody().get("activeCounties");
        assertThat(counties)
                .as("explicit empty array means validation is OFF — frontend hides dropdown, "
                        + "shows a free-text county input. NOT the same as 'fall back to NC defaults'.")
                .isEmpty();
    }

    @Test
    @DisplayName("Unauthenticated request returns 401")
    void unauthenticated_returns401() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/active-counties",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode())
                .as("isAuthenticated() guard must reject anonymous callers")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
