package org.fabt.observability;

import java.util.Map;
import java.util.UUID;

import org.fabt.Application;
import org.fabt.BaseIntegrationTest;
import org.fabt.shared.errors.ErrorCodes;
import org.fabt.shared.errors.StructuredErrorException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IT for {@link PlatformConfigService} — platform-observability-split tasks §1.5.
 *
 * <p>Round-trips the singleton {@code platform_config} JSONB through the service
 * to prove read + merge + cache + validation behaviors all work end-to-end with
 * a real Postgres + Flyway + V98-seeded row.
 *
 * <p>Each test resets the singleton row to {@link PlatformConfig#DEFAULTS} in
 * {@link #resetSingleton} so tests don't leak state into each other.
 */
@DisplayName("PlatformConfigService — platform_config singleton")
@SpringBootTest(classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformConfigServiceIntegrationTest extends BaseIntegrationTest {

    private static final UUID ACTOR = UUID.fromString("b0000000-0000-0000-0000-000000000001");

    @Autowired private PlatformConfigService service;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void resetSingleton() {
        // Restore the V98-seeded defaults between tests.
        jdbc.update(
                "UPDATE platform_config SET config = ?::jsonb, updated_by = NULL WHERE id = ?",
                "{"
                        + "\"prometheus_enabled\": true,"
                        + "\"tracing_enabled\": false,"
                        + "\"tracing_endpoint\": \"http://localhost:4318/v1/traces\","
                        + "\"monitor_stale_interval_minutes\": 5,"
                        + "\"monitor_dv_canary_interval_minutes\": 15,"
                        + "\"monitor_temperature_interval_minutes\": 60"
                        + "}",
                PlatformConfigService.SINGLETON_ID);
        service.refresh();
    }

    // ----- get / refresh ------------------------------------------------

    @Test
    @DisplayName("get() returns the seeded V98 defaults after refresh")
    void getReturnsSeededDefaults() {
        PlatformConfig cfg = service.get();
        assertThat(cfg.prometheusEnabled()).isTrue();
        assertThat(cfg.tracingEnabled()).isFalse();
        assertThat(cfg.tracingEndpoint()).isEqualTo("http://localhost:4318/v1/traces");
        assertThat(cfg.monitorStaleIntervalMinutes()).isEqualTo(5);
        assertThat(cfg.monitorDvCanaryIntervalMinutes()).isEqualTo(15);
        assertThat(cfg.monitorTemperatureIntervalMinutes()).isEqualTo(60);
    }

    @Test
    @DisplayName("refresh() picks up out-of-band JSONB changes")
    void refreshReadsLatestRow() {
        // Write directly via JDBC, bypassing the service. The cache is stale
        // until refresh() runs.
        jdbc.update(
                "UPDATE platform_config "
                        + "SET config = jsonb_set(config, '{tracing_enabled}', 'true'::jsonb) "
                        + "WHERE id = ?",
                PlatformConfigService.SINGLETON_ID);
        // Pre-refresh: cache still says false
        assertThat(service.get().tracingEnabled())
                .as("cache should be stale before refresh")
                .isFalse();
        service.refresh();
        assertThat(service.get().tracingEnabled())
                .as("cache should reflect out-of-band change after refresh")
                .isTrue();
    }

    // ----- update — happy path ------------------------------------------

    @Test
    @DisplayName("update() merges single field + refreshes cache + persists")
    void updateMergesSingleField() {
        PlatformConfig result = service.update(
                Map.of("monitor_stale_interval_minutes", 10), ACTOR);

        assertThat(result.monitorStaleIntervalMinutes()).isEqualTo(10);
        assertThat(result.monitorDvCanaryIntervalMinutes())
                .as("untouched fields preserved")
                .isEqualTo(15);

        // Cache reflects the new value
        assertThat(service.get().monitorStaleIntervalMinutes()).isEqualTo(10);

        // Database reflects the new value
        String persisted = jdbc.queryForObject(
                "SELECT config ->> 'monitor_stale_interval_minutes' FROM platform_config WHERE id = ?",
                String.class, PlatformConfigService.SINGLETON_ID);
        assertThat(persisted).isEqualTo("10");
    }

    @Test
    @DisplayName("update() can change multiple fields atomically")
    void updateMultipleFields() {
        service.update(Map.of(
                "tracing_enabled", true,
                "tracing_endpoint", "http://otel-collector.platform:4318/v1/traces",
                "monitor_dv_canary_interval_minutes", 30
        ), ACTOR);

        PlatformConfig cfg = service.get();
        assertThat(cfg.tracingEnabled()).isTrue();
        assertThat(cfg.tracingEndpoint())
                .isEqualTo("http://otel-collector.platform:4318/v1/traces");
        assertThat(cfg.monitorDvCanaryIntervalMinutes()).isEqualTo(30);
    }

    @Test
    @DisplayName("update() persists updated_by for audit linkage")
    void updateRecordsActor() {
        service.update(Map.of("tracing_enabled", true), ACTOR);

        UUID persistedBy = jdbc.queryForObject(
                "SELECT updated_by FROM platform_config WHERE id = ?",
                UUID.class, PlatformConfigService.SINGLETON_ID);
        assertThat(persistedBy).isEqualTo(ACTOR);
    }

    // ----- update — validation rejects -----------------------------------

    @Test
    @DisplayName("update() rejects interval below the [1, 1440] floor")
    void rejectsIntervalBelowFloor() {
        assertThatThrownBy(() -> service.update(
                Map.of("monitor_stale_interval_minutes", 0), ACTOR))
                .isInstanceOf(StructuredErrorException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        ErrorCodes.PLATFORM_OBSERVABILITY_INTERVAL_OUT_OF_RANGE);

        // Pre-existing value preserved (transactional rollback)
        assertThat(service.get().monitorStaleIntervalMinutes()).isEqualTo(5);
    }

    @Test
    @DisplayName("update() rejects interval above the [1, 1440] ceiling")
    void rejectsIntervalAboveCeiling() {
        assertThatThrownBy(() -> service.update(
                Map.of("monitor_temperature_interval_minutes", 1441), ACTOR))
                .isInstanceOf(StructuredErrorException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        ErrorCodes.PLATFORM_OBSERVABILITY_INTERVAL_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("update() rejects non-integer interval value")
    void rejectsNonIntegerInterval() {
        assertThatThrownBy(() -> service.update(
                Map.of("monitor_stale_interval_minutes", "ten"), ACTOR))
                .isInstanceOf(StructuredErrorException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        ErrorCodes.PLATFORM_OBSERVABILITY_INTERVAL_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("update() rejects unknown field key")
    void rejectsUnknownKey() {
        assertThatThrownBy(() -> service.update(
                Map.of("definitely_not_a_real_field", true), ACTOR))
                .isInstanceOf(StructuredErrorException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        ErrorCodes.PLATFORM_OBSERVABILITY_INTERVAL_OUT_OF_RANGE)
                .hasMessageContaining("Unknown");
    }

    @Test
    @DisplayName("update() rejects blank tracing endpoint")
    void rejectsBlankTracingEndpoint() {
        assertThatThrownBy(() -> service.update(
                Map.of("tracing_endpoint", ""), ACTOR))
                .isInstanceOf(StructuredErrorException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        ErrorCodes.PLATFORM_OBSERVABILITY_TRACING_ENDPOINT_MALFORMED);
    }

    @Test
    @DisplayName("update() rejects malformed tracing endpoint URI")
    void rejectsMalformedTracingEndpoint() {
        // Missing scheme — URI.create("just-a-host") parses but with no scheme/host
        assertThatThrownBy(() -> service.update(
                Map.of("tracing_endpoint", "not a uri at all"), ACTOR))
                .isInstanceOf(StructuredErrorException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        ErrorCodes.PLATFORM_OBSERVABILITY_TRACING_ENDPOINT_MALFORMED);
    }

    @Test
    @DisplayName("update() rejects non-boolean for boolean field")
    void rejectsNonBooleanForBoolean() {
        assertThatThrownBy(() -> service.update(
                Map.of("tracing_enabled", "yes"), ACTOR))
                .isInstanceOf(StructuredErrorException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        ErrorCodes.PLATFORM_OBSERVABILITY_INTERVAL_OUT_OF_RANGE);
    }

    // ----- bounds boundaries (inclusive) ---------------------------------

    @Test
    @DisplayName("update() accepts exactly 1 (lower inclusive bound)")
    void acceptsLowerBound() {
        service.update(Map.of("monitor_stale_interval_minutes", 1), ACTOR);
        assertThat(service.get().monitorStaleIntervalMinutes()).isEqualTo(1);
    }

    @Test
    @DisplayName("update() accepts exactly 1440 (upper inclusive bound)")
    void acceptsUpperBound() {
        service.update(Map.of("monitor_temperature_interval_minutes", 1440), ACTOR);
        assertThat(service.get().monitorTemperatureIntervalMinutes()).isEqualTo(1440);
    }
}
