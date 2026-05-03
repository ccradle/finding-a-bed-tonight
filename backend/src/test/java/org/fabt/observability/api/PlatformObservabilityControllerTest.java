package org.fabt.observability.api;

import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.observability.OperationalMonitorService;
import org.fabt.observability.PlatformConfigService;
import org.fabt.shared.errors.ErrorCodes;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Integration tests for {@link PlatformObservabilityController} covering
 * platform-observability-split §2.8 + warroom round 4 strengthening.
 *
 * <p>Round 4 additions vs. the original suite:
 * <ul>
 *   <li>{@code putHappyPath_triggersReschedule} — verifies that interval
 *       changes call {@link OperationalMonitorService#rescheduleFromConfig}
 *       (was assumed but never asserted; now SpyBean catches a no-op
 *       regression).</li>
 *   <li>{@code putNonIntervalChange_doesNotTriggerReschedule} — boolean
 *       flips don't disturb the scheduler.</li>
 *   <li>{@code cocAdminPutForbidden} — the higher-risk PUT path is the one
 *       worth asserting; original suite only covered GET.</li>
 *   <li>{@code putTracingEndpoint_happyPath} — exercises tracing_endpoint
 *       update; original only exercised 2 of 6 fields.</li>
 *   <li>{@code putValidationRejection_typeMismatch} — exercises the
 *       FIELD_TYPE_MISMATCH error code (warroom round 4 B6 split).</li>
 *   <li>{@code putUnknownField_returnsUnknownFieldErrorCode} — exercises
 *       the UNKNOWN_FIELD error code (warroom round 4 B6 split).</li>
 *   <li>Audit assertions tightened: {@code outcome: "applied"} verified
 *       and {@code old_value} verified, not just count.</li>
 * </ul>
 */
@DisplayName("GET/PUT /api/v1/platform/observability")
class PlatformObservabilityControllerTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformConfigService configService;

    /**
     * Spy on the real bean so we can assert that rescheduleFromConfig() is
     * actually called by the controller after a successful interval-changing
     * PUT (warroom round 4: original test left this as an unverified assumption).
     */
    @MockitoSpyBean private OperationalMonitorService monitorService;

    private HttpHeaders operatorHeaders;
    private HttpHeaders cocAdminHeaders;

    @BeforeEach
    void setUp() {
        // Platform operator with valid justification
        operatorHeaders = authHelper.platformOperatorHeaders("Testing platform observability update");

        // COC_ADMIN for negative-path authorization tests.
        // setupTestTenant binds a random tenant to the authHelper.
        authHelper.setupTestTenant("platform-obs-test-" + UUID.randomUUID().toString().substring(0, 8));
        cocAdminHeaders = authHelper.cocAdminHeaders();

        // Reset platform config to known state before each test
        TenantContext.runWithContext(TenantContext.SYSTEM_TENANT_ID, true, () -> {
            jdbc.update("UPDATE platform_config SET config = ?::jsonb WHERE id = ?",
                    "{\"prometheus_enabled\": true, \"tracing_enabled\": false, \"tracing_endpoint\": \"http://localhost:4318/v1/traces\", \"monitor_stale_interval_minutes\": 5, \"monitor_dv_canary_interval_minutes\": 15, \"monitor_temperature_interval_minutes\": 60}",
                    UUID.fromString("00000000-0000-0000-0000-000000000001"));
        });
        // Sync the in-memory cache with the reset DB row so configService.get()
        // returns the correct baseline for tests that check audit diffs.
        configService.refresh();

        // Reset the spy invocation counts between tests so reschedule
        // assertions are scoped to the test under test.
        org.mockito.Mockito.clearInvocations(monitorService);
    }

    @Test
    @DisplayName("GET happy-path — 200, returns defaults initially")
    void getHappyPath() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/platform/observability",
                HttpMethod.GET,
                new HttpEntity<>(operatorHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("prometheusEnabled", true);
        assertThat(response.getBody()).containsEntry("monitorStaleIntervalMinutes", 5);
    }

    @Test
    @DisplayName("PUT happy-path — 200, updates DB, emits field audits")
    void putHappyPath() {
        Map<String, Object> patch = Map.of(
                "monitor_stale_interval_minutes", 10,
                "prometheus_enabled", false
        );

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/platform/observability",
                HttpMethod.PUT,
                new HttpEntity<>(patch, operatorHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("monitorStaleIntervalMinutes", 10);
        assertThat(response.getBody()).containsEntry("prometheusEnabled", false);

        // Verify DB write
        String json = jdbc.queryForObject(
                "SELECT config::text FROM platform_config WHERE id = ?",
                String.class, UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(json).contains("\"monitor_stale_interval_minutes\": 10");
        assertThat(json).contains("\"prometheus_enabled\": false");

        // Verify per-field audits (D5) — not just count, but old_value + outcome
        assertThat(auditDetail("monitor_stale_interval_minutes", "new_value")).isEqualTo("10");
        assertThat(auditDetail("monitor_stale_interval_minutes", "old_value")).isEqualTo("5");
        assertThat(auditDetail("monitor_stale_interval_minutes", "value_changed")).isEqualTo("true");
        assertThat(auditDetail("monitor_stale_interval_minutes", "outcome")).isEqualTo("applied");

        assertThat(auditDetail("prometheus_enabled", "new_value")).isEqualTo("false");
        assertThat(auditDetail("prometheus_enabled", "old_value")).isEqualTo("true");
        assertThat(auditDetail("prometheus_enabled", "value_changed")).isEqualTo("true");
    }

    @Test
    @DisplayName("PUT happy-path on interval — triggers OperationalMonitorService.rescheduleFromConfig")
    void putHappyPath_triggersReschedule() {
        Map<String, Object> patch = Map.of("monitor_stale_interval_minutes", 10);

        restTemplate.exchange(
                "/api/v1/platform/observability",
                HttpMethod.PUT,
                new HttpEntity<>(patch, operatorHeaders),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        // Warroom round 4: this assertion was missing. Without it, a
        // controller regression that drops the reschedule call would go
        // silently undetected — the test would pass even though monitor
        // cadence wouldn't actually change in production.
        verify(monitorService, times(1)).rescheduleFromConfig();
    }

    @Test
    @DisplayName("PUT non-interval change (boolean flip) — does NOT trigger reschedule")
    void putNonIntervalChange_doesNotTriggerReschedule() {
        Map<String, Object> patch = Map.of("tracing_enabled", true);

        restTemplate.exchange(
                "/api/v1/platform/observability",
                HttpMethod.PUT,
                new HttpEntity<>(patch, operatorHeaders),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        // Boolean changes (prom/tracing) don't affect scheduler cadences;
        // the reschedule call would be wasted work + an unnecessary thread-
        // pool stir. Asserting this guards against an over-eager refactor
        // that triggers reschedule on every PUT.
        verify(monitorService, never()).rescheduleFromConfig();
    }

    @Test
    @DisplayName("PUT happy-path on tracing_endpoint — accepts a valid OTel URL")
    void putTracingEndpoint_happyPath() {
        Map<String, Object> patch = Map.of(
                "tracing_endpoint", "http://otel-collector.platform:4318/v1/traces");

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/platform/observability",
                HttpMethod.PUT,
                new HttpEntity<>(patch, operatorHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("tracingEndpoint", "http://otel-collector.platform:4318/v1/traces");
    }

    @Test
    @DisplayName("PUT validation rejection — 400 for out-of-range interval")
    void putValidationRejectionInterval() {
        Map<String, Object> patch = Map.of("monitor_stale_interval_minutes", 2000);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/platform/observability",
                HttpMethod.PUT,
                new HttpEntity<>(patch, operatorHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) response.getBody().get("context");
        assertThat(context).containsEntry("errorCode", ErrorCodes.PLATFORM_OBSERVABILITY_INTERVAL_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("PUT validation rejection — 400 for malformed tracing endpoint")
    void putValidationRejectionEndpoint() {
        Map<String, Object> patch = Map.of("tracing_endpoint", "not-a-url");

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/platform/observability",
                HttpMethod.PUT,
                new HttpEntity<>(patch, operatorHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) response.getBody().get("context");
        assertThat(context).containsEntry("errorCode", ErrorCodes.PLATFORM_OBSERVABILITY_TRACING_ENDPOINT_MALFORMED);
    }

    @Test
    @DisplayName("PUT validation rejection — 400 for type mismatch on a boolean field")
    void putValidationRejection_typeMismatch() {
        // Warroom round 4 B6: type mismatch gets the dedicated
        // FIELD_TYPE_MISMATCH code, not the bounds code.
        Map<String, Object> patch = Map.of("tracing_enabled", "yes");

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/platform/observability",
                HttpMethod.PUT,
                new HttpEntity<>(patch, operatorHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) response.getBody().get("context");
        assertThat(context).containsEntry("errorCode", ErrorCodes.PLATFORM_OBSERVABILITY_FIELD_TYPE_MISMATCH);
    }

    @Test
    @DisplayName("PUT missing justification — 400 (JustificationValidationFilter)")
    void putMissingJustification() {
        HttpHeaders noJustification = new HttpHeaders();
        noJustification.setAll(operatorHeaders.toSingleValueMap());
        noJustification.remove("X-Platform-Justification");

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/platform/observability",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("prometheus_enabled", true), noJustification),
                new ParameterizedTypeReference<>() {});

        // JustificationValidationFilter returns 400 when missing header
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("COC_ADMIN GET — 403")
    void cocAdminGetForbidden() {
        // Must provide justification so filter doesn't 400 first
        HttpHeaders headers = authHelper.withJustification(cocAdminHeaders, "Testing bypass");

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/platform/observability",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("COC_ADMIN PUT — 403 (the high-risk path; original suite only covered GET)")
    void cocAdminPutForbidden() {
        // Warroom round 4: PUT is the higher-risk path because it mutates
        // platform-wide state that affects every tenant. Test it, not just GET.
        HttpHeaders headers = authHelper.withJustification(cocAdminHeaders, "Testing bypass");

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/platform/observability",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("tracing_enabled", true), headers),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Idempotent re-set audit — value_changed=false")
    void idempotentAudit() {
        // Initial state is true for prometheus_enabled. Set it to true again.
        Map<String, Object> patch = Map.of("prometheus_enabled", true);

        restTemplate.exchange(
                "/api/v1/platform/observability",
                HttpMethod.PUT,
                new HttpEntity<>(patch, operatorHeaders),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        // Audit row IS emitted (intent traceability), but value_changed=false.
        assertThat(auditDetail("prometheus_enabled", "new_value")).isEqualTo("true");
        assertThat(auditDetail("prometheus_enabled", "old_value")).isEqualTo("true");
        assertThat(auditDetail("prometheus_enabled", "value_changed")).isEqualTo("false");
        assertThat(auditDetail("prometheus_enabled", "outcome")).isEqualTo("applied");
    }

    @Test
    @DisplayName("PUT unknown field — 400 with UNKNOWN_FIELD error code")
    void putUnknownField_returnsUnknownFieldErrorCode() {
        Map<String, Object> patch = Map.of("unknown_field", "value");

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/platform/observability",
                HttpMethod.PUT,
                new HttpEntity<>(patch, operatorHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Warroom round 4 B6: dedicated UNKNOWN_FIELD code, not the
        // overloaded INTERVAL_OUT_OF_RANGE that the original code emitted.
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) response.getBody().get("context");
        assertThat(context).containsEntry("errorCode", ErrorCodes.PLATFORM_OBSERVABILITY_UNKNOWN_FIELD);
    }

    /**
     * Returns the value of {@code details ->> field} for the most recent
     * PLATFORM_OBSERVABILITY_UPDATED audit row whose {@code details ->> 'field'}
     * matches the given platform-config key. Filtering by recency (no time
     * window) is fine because each test wraps in its own transactional rollback
     * via BaseIntegrationTest, so cross-test pollution is bounded.
     */
    private String auditDetail(String configField, String detailKey) {
        return TenantContext.callWithContext(TenantContext.SYSTEM_TENANT_ID, true, () -> jdbc.queryForObject(
                "SELECT details ->> ? FROM audit_events "
                        + "WHERE action = 'PLATFORM_OBSERVABILITY_UPDATED' "
                        + "  AND details ->> 'field' = ? "
                        + "ORDER BY timestamp DESC LIMIT 1",
                String.class, detailKey, configField));
    }
}
