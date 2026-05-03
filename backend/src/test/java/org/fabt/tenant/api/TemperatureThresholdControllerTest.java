package org.fabt.tenant.api;

import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.shared.errors.ErrorCodes;
import org.fabt.shared.web.TenantContext;
import org.fabt.auth.domain.User;
import org.fabt.tenant.domain.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

/**
 * Integration tests for {@link TemperatureThresholdController}.
 * Covers platform-observability-split §5.4 + warroom round 4 strengthening.
 *
 * <p>Round 4 additions vs. the original 4-test suite:
 * <ul>
 *   <li>{@code coordinatorForbidden} + {@code outreachForbidden} — non-admin
 *       roles must not be able to write surge thresholds (the spec is
 *       explicit; the original suite skipped these).</li>
 *   <li>{@code putUpdate_emitsAuditRow} — verifies the
 *       TENANT_CONFIG_UPDATED audit row is emitted with the spec-mandated
 *       details payload (warroom round 4 §5.3 fix). Original implementation
 *       silently swallowed the audit; this test now catches that.</li>
 *   <li>{@code putRejectionRange_returnsCorrectErrorCode} — pins the
 *       error code to {@code tenant.surgeThreshold.outOfRange}, not just
 *       a 400 status.</li>
 *   <li>{@code putRejectionTypeMismatch} — exercises the
 *       wrong-type rejection path (warroom round 4 B4 fix — was returning
 *       PLATFORM_OBSERVABILITY_INTERVAL_OUT_OF_RANGE).</li>
 *   <li>{@code putPreservesSiblingObservabilityKeys} — exercises the
 *       read-modify-write path so a future regression that overwrites the
 *       observability sub-map with only temperature_threshold_f is caught.</li>
 *   <li>{@code crossTenantPut_404} — direct symmetry of the GET test for the
 *       higher-risk PUT path.</li>
 * </ul>
 */
@DisplayName("GET/PUT /api/v1/tenants/{id}/surge-threshold")
class TemperatureThresholdControllerTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;
    private User cocAdminUser;
    private HttpHeaders cocAdminHeaders;
    private HttpHeaders coordinatorHeaders;
    private HttpHeaders outreachHeaders;

    @BeforeEach
    void setUp() {
        // Fresh tenant per test so cross-tenant audit assertions don't leak.
        String slug = "temp-test-" + UUID.randomUUID().toString().substring(0, 8);
        Tenant tenant = authHelper.setupTestTenant(slug);
        tenantId = tenant.getId();

        // Create role-specific users INSIDE the test tenant. Using
        // authHelper.cocAdminHeaders() / coordinatorHeaders() directly would
        // bind to the prior testTenant from a different test (TestAuthHelper
        // caches role-specific singletons), giving a JWT.tenantId that doesn't
        // match the URL path → TenantPathGuard 404 instead of role-403.
        cocAdminUser = authHelper.createUserInTenant(
                tenantId, "cocadmin@temp-test.fabt", "CoC Admin",
                new String[]{"COC_ADMIN"}, false);
        cocAdminHeaders = authHelper.headersForUser(cocAdminUser);

        User coordinatorUser = authHelper.createUserInTenant(
                tenantId, "coordinator@temp-test.fabt", "Coordinator",
                new String[]{"COORDINATOR"}, false);
        coordinatorHeaders = authHelper.headersForUser(coordinatorUser);

        User outreachUser = authHelper.createUserInTenant(
                tenantId, "outreach@temp-test.fabt", "Outreach Worker",
                new String[]{"OUTREACH_WORKER"}, false);
        outreachHeaders = authHelper.headersForUser(outreachUser);

        // Ensure clean config so old test state doesn't bleed into assertions.
        TenantContext.runWithContext(tenantId, true, () -> {
            jdbc.update("UPDATE tenant SET config = '{}'::jsonb WHERE id = ?", tenantId);
        });
    }

    @Test
    @DisplayName("GET happy-path — returns default 32.0 if not set")
    void getReturnsDefault() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/tenants/" + tenantId + "/surge-threshold",
                HttpMethod.GET,
                new HttpEntity<>(cocAdminHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("temperature_threshold_f", 32.0);
    }

    @Test
    @DisplayName("PUT happy-path — updates tenant config and persists")
    void putUpdatesConfig() {
        Map<String, Object> body = Map.of("temperature_threshold_f", 40.5);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/tenants/" + tenantId + "/surge-threshold",
                HttpMethod.PUT,
                new HttpEntity<>(body, cocAdminHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("temperature_threshold_f", 40.5);

        // Verify DB. Whitespace-tolerant — the assertion only cares the
        // key+value survived round-trip; the JSONB serialiser formatting is
        // a Postgres / Jackson concern, not part of the contract under test.
        String json = TenantContext.callWithContext(tenantId, true, () -> jdbc.queryForObject(
                "SELECT config::text FROM tenant WHERE id = ?",
                String.class, tenantId));
        assertThat(json.replaceAll("\\s+", ""))
                .contains("\"temperature_threshold_f\":40.5");
    }

    @Test
    @DisplayName("PUT happy-path — emits TENANT_CONFIG_UPDATED audit row (§5.3)")
    void putUpdate_emitsAuditRow() {
        // Warroom round 4 §5.3 fix: original controller never emitted the
        // audit. This test was missing; without it, the spec violation was
        // invisible.
        Map<String, Object> body = Map.of("temperature_threshold_f", 28.0);

        restTemplate.exchange(
                "/api/v1/tenants/" + tenantId + "/surge-threshold",
                HttpMethod.PUT,
                new HttpEntity<>(body, cocAdminHeaders),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        // Audit row exists, carries the right config_key, and has
        // outcome=applied + value_changed=true.
        String configKey = TenantContext.callWithContext(tenantId, true, () -> jdbc.queryForObject(
                "SELECT details ->> 'config_key' FROM audit_events "
                        + "WHERE action = 'TENANT_CONFIG_UPDATED' "
                        + "  AND tenant_id = ? "
                        + "ORDER BY timestamp DESC LIMIT 1",
                String.class, tenantId));
        assertThat(configKey).isEqualTo("temperature_threshold_f");

        String newValue = TenantContext.callWithContext(tenantId, true, () -> jdbc.queryForObject(
                "SELECT details ->> 'new_value' FROM audit_events "
                        + "WHERE action = 'TENANT_CONFIG_UPDATED' "
                        + "  AND tenant_id = ? "
                        + "  AND details ->> 'config_key' = 'temperature_threshold_f' "
                        + "ORDER BY timestamp DESC LIMIT 1",
                String.class, tenantId));
        assertThat(newValue).isEqualTo("28.0");

        String oldValue = TenantContext.callWithContext(tenantId, true, () -> jdbc.queryForObject(
                "SELECT details ->> 'old_value' FROM audit_events "
                        + "WHERE action = 'TENANT_CONFIG_UPDATED' "
                        + "  AND tenant_id = ? "
                        + "  AND details ->> 'config_key' = 'temperature_threshold_f' "
                        + "ORDER BY timestamp DESC LIMIT 1",
                String.class, tenantId));
        assertThat(oldValue).isEqualTo("32.0");

        String outcome = TenantContext.callWithContext(tenantId, true, () -> jdbc.queryForObject(
                "SELECT details ->> 'outcome' FROM audit_events "
                        + "WHERE action = 'TENANT_CONFIG_UPDATED' "
                        + "  AND tenant_id = ? "
                        + "  AND details ->> 'config_key' = 'temperature_threshold_f' "
                        + "ORDER BY timestamp DESC LIMIT 1",
                String.class, tenantId));
        assertThat(outcome).isEqualTo("applied");
    }

    @Test
    @DisplayName("PUT preserves sibling observability keys (read-modify-write)")
    void putPreservesSiblingObservabilityKeys() {
        // Pre-seed an unrelated key in the observability sub-map. If the
        // controller's write path overwrites the entire sub-map with just
        // temperature_threshold_f (the original B-class bug), this key
        // disappears and the assertion fails.
        TenantContext.runWithContext(tenantId, true, () -> jdbc.update(
                "UPDATE tenant SET config = '{\"observability\":{\"noaa_station_id\":\"KAVL\"}}'::jsonb WHERE id = ?",
                tenantId));

        restTemplate.exchange(
                "/api/v1/tenants/" + tenantId + "/surge-threshold",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("temperature_threshold_f", 35.0), cocAdminHeaders),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        String json = TenantContext.callWithContext(tenantId, true, () -> jdbc.queryForObject(
                "SELECT config::text FROM tenant WHERE id = ?",
                String.class, tenantId));
        // Whitespace-tolerant — Postgres + Jackson3 may emit `"k": "v"` with
        // a space, the regression test only cares that the kv pair survived.
        String compact = json.replaceAll("\\s+", "");
        assertThat(compact).contains("\"noaa_station_id\":\"KAVL\"");
        assertThat(compact).contains("\"temperature_threshold_f\":35.0");
    }

    @Test
    @DisplayName("PUT rejection — out of range returns tenant.surgeThreshold.outOfRange")
    void putRejectionRange_returnsCorrectErrorCode() {
        Map<String, Object> body = Map.of("temperature_threshold_f", 200.0);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/tenants/" + tenantId + "/surge-threshold",
                HttpMethod.PUT,
                new HttpEntity<>(body, cocAdminHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) response.getBody().get("context");
        assertThat(context).containsEntry("errorCode", ErrorCodes.TENANT_SURGE_THRESHOLD_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("PUT rejection — type mismatch returns tenant.surgeThreshold.outOfRange (B4 fix)")
    void putRejectionTypeMismatch() {
        // Warroom round 4 B4: was throwing PLATFORM_OBSERVABILITY_INTERVAL_OUT_OF_RANGE
        // because the controller mistakenly reused the platform-observability
        // code constant for the tenant-scoped path.
        Map<String, Object> body = Map.of("temperature_threshold_f", "not-a-number");

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/tenants/" + tenantId + "/surge-threshold",
                HttpMethod.PUT,
                new HttpEntity<>(body, cocAdminHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) response.getBody().get("context");
        assertThat(context).containsEntry("errorCode", ErrorCodes.TENANT_SURGE_THRESHOLD_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("PUT — COORDINATOR forbidden (403)")
    void coordinatorForbidden() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/tenants/" + tenantId + "/surge-threshold",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("temperature_threshold_f", 32.0), coordinatorHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("PUT — OUTREACH_WORKER forbidden (403)")
    void outreachForbidden() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/tenants/" + tenantId + "/surge-threshold",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("temperature_threshold_f", 32.0), outreachHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET — different tenant → 404 (TenantPathGuard existence-leak prevention)")
    void crossTenantGet_404() {
        UUID otherTenantId = UUID.randomUUID();

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/tenants/" + otherTenantId + "/surge-threshold",
                HttpMethod.GET,
                new HttpEntity<>(cocAdminHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("PUT — different tenant → 404 (TenantPathGuard existence-leak prevention)")
    void crossTenantPut_404() {
        UUID otherTenantId = UUID.randomUUID();

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/tenants/" + otherTenantId + "/surge-threshold",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("temperature_threshold_f", 40.0), cocAdminHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
