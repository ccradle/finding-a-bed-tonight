package org.fabt.observability;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.fabt.shared.errors.ErrorCodes;
import org.fabt.shared.errors.StructuredErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
// JacksonException covers both parse and serialize failures in Spring Boot 4 / Jackson 3
// (replaces the legacy com.fasterxml.jackson.core.JsonProcessingException).
import tools.jackson.databind.ObjectMapper;

/**
 * Reads + writes the singleton {@code platform_config} row for platform-level
 * observability settings. New in the platform-observability-split openspec
 * change (2026-05-02).
 *
 * <p><b>Singleton pattern:</b> the table holds exactly one row, identified
 * by {@link #SINGLETON_ID}. The V98 migration's CHECK constraint enforces
 * this — any insert with another UUID is rejected at the DB layer. So this
 * service can SELECT/UPDATE WHERE id = ? without worrying about which row.
 *
 * <p><b>Cache:</b> the parsed {@link PlatformConfig} is cached in a single
 * {@link AtomicReference} loaded at startup ({@link #onApplicationReady}) and
 * refreshed by {@link #update}. Reads via {@link #get} are O(1) and lock-free,
 * which matters because the {@code OperationalMonitorService} calls
 * {@code get()} on every monitor cycle. Writes are infrequent (operator-driven)
 * so the eventual-consistency between {@code update}'s in-DB write and any
 * reader still holding a stale reference is bounded by one cycle —
 * acceptable per the design D2 cadence-takes-effect-on-next-cycle SLA.
 *
 * <p><b>Validation:</b> {@link #update} enforces the bounds documented at
 * {@link PlatformConfig} (intervals 1..1440 minutes, tracing endpoint
 * non-blank). Out-of-bounds writes throw
 * {@link StructuredErrorException} with errorCode
 * {@link ErrorCodes#PLATFORM_OBSERVABILITY_INTERVAL_OUT_OF_RANGE} or
 * {@link ErrorCodes#PLATFORM_OBSERVABILITY_TRACING_ENDPOINT_MALFORMED}.
 *
 * <p><b>What this service intentionally does NOT do:</b>
 * <ul>
 *   <li>Audit emission — that's the controller's job (D5 — keep the
 *       service focused on data access; the audit row carries operator
 *       identity which only the controller has).</li>
 *   <li>Reschedule the {@link OperationalMonitorService} — caller's job.
 *       The service exposes raw read/write; the orchestration to "write,
 *       then trigger reschedule" lives in the controller.</li>
 * </ul>
 */
@Service
public class PlatformConfigService {

    private static final Logger log = LoggerFactory.getLogger(PlatformConfigService.class);

    /**
     * The CHECK constraint on the V98 table enforces this UUID. Hardcoded
     * here so the service code reads naturally — there's no other valid id.
     */
    public static final UUID SINGLETON_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * Hot path: every monitor cycle calls {@link #get}. Holding the parsed
     * record in an AtomicReference avoids a JSONB-parse + DB roundtrip per
     * read. Updated only by {@link #update} and {@link #refresh}.
     *
     * <p>Initialized to {@link PlatformConfig#DEFAULTS} so application-context
     * startup that hits {@code get()} BEFORE {@link #onApplicationReady} fires
     * (rare but possible in tests) sees sensible defaults rather than NPE.
     * The first reload replaces this with the persisted row.
     */
    private final AtomicReference<PlatformConfig> cache = new AtomicReference<>(PlatformConfig.DEFAULTS);

    public PlatformConfigService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Loads the singleton row into the in-memory cache. Triggered on
     * application context refresh so the row is visible to all components
     * that {@link #get} during their {@code @PostConstruct} (e.g. the
     * {@link OperationalMonitorService} scheduler registration).
     *
     * <p>If the row doesn't exist (V98 hasn't run, e.g. in a misconfigured
     * test that skips Flyway), the service silently keeps {@link PlatformConfig#DEFAULTS}
     * so startup doesn't fail. Logged as a warning so an operator can trace
     * the misconfiguration if monitor cadences look wrong.
     */
    @EventListener(ContextRefreshedEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    /**
     * Returns the cached parsed config. O(1), lock-free.
     */
    public PlatformConfig get() {
        return cache.get();
    }

    /**
     * Reload the cache from the DB. Called by {@link #onApplicationReady}
     * and by {@link #update} after a successful write. Public so tests can
     * trigger a re-read after manipulating the row directly.
     */
    public void refresh() {
        try {
            String json = jdbc.queryForObject(
                    "SELECT config::text FROM platform_config WHERE id = ?",
                    String.class, SINGLETON_ID);
            if (json == null || json.isBlank()) {
                log.warn("platform_config row exists but has empty/null config; falling back to DEFAULTS");
                cache.set(PlatformConfig.DEFAULTS);
                return;
            }
            cache.set(parse(json));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            log.warn("platform_config singleton row not found (V98 may not have run); using DEFAULTS");
            cache.set(PlatformConfig.DEFAULTS);
        } catch (Exception e) {
            log.warn("Failed to refresh platform_config cache: {}; keeping previous value", e.getMessage());
        }
    }

    /**
     * Apply a partial update to the singleton config. The {@code patch} map
     * uses snake_case keys matching the JSONB schema (e.g.
     * {@code "tracing_enabled"}, {@code "monitor_stale_interval_minutes"});
     * unknown keys are rejected to prevent typo silently widening the
     * persisted JSONB.
     *
     * <p>Returns the {@link PlatformConfig} as it stands AFTER the merge,
     * which lets the controller emit per-field audit rows comparing
     * pre-update {@link #get} to the returned post-update value.
     *
     * @param patch keys and new values; only keys recognized by
     *              {@link PlatformConfig} are accepted
     * @param actorId operator who initiated the change; persisted to
     *                {@code platform_config.updated_by} for forensic linkage
     *                with the audit row
     * @return the merged config (post-write, freshly read from cache)
     * @throws StructuredErrorException on validation failure
     *         (errorCode = {@link ErrorCodes#PLATFORM_OBSERVABILITY_INTERVAL_OUT_OF_RANGE}
     *         or {@link ErrorCodes#PLATFORM_OBSERVABILITY_TRACING_ENDPOINT_MALFORMED})
     */
    @Transactional
    public PlatformConfig update(Map<String, Object> patch, UUID actorId) {
        validatePatch(patch);

        // Read existing config as a mutable map. Use the JSONB ::text cast
        // so JdbcTemplate hands us a String we can hand to ObjectMapper
        // directly (avoids the org.postgresql.util.PGobject unboxing dance).
        String currentJson = jdbc.queryForObject(
                "SELECT config::text FROM platform_config WHERE id = ?",
                String.class, SINGLETON_ID);
        Map<String, Object> merged;
        try {
            if (currentJson == null || currentJson.isBlank()) {
                merged = new HashMap<>();
            } else {
                merged = new HashMap<>(objectMapper.readValue(currentJson,
                        new TypeReference<Map<String, Object>>() {}));
            }
        } catch (JacksonException e) {
            throw new IllegalStateException(
                    "Failed to parse existing platform_config JSON; refusing to overwrite", e);
        }

        // Merge. Caller already passed validation in validatePatch; safe to
        // overwrite each key blindly.
        merged.putAll(patch);

        String mergedJson;
        try {
            mergedJson = objectMapper.writeValueAsString(merged);
        } catch (JacksonException e) {
            throw new IllegalStateException(
                    "Failed to serialize merged platform_config JSON", e);
        }

        jdbc.update(
                "UPDATE platform_config "
                        + "SET config = ?::jsonb, updated_at = NOW(), updated_by = ? "
                        + "WHERE id = ?",
                mergedJson, actorId, SINGLETON_ID);

        // Refresh the cache so subsequent get() calls see the new value
        // immediately, before the next ContextRefreshedEvent.
        refresh();
        return cache.get();
    }

    /**
     * Validate a patch map: keys must be in the recognized set + values must
     * pass type + bounds checks. Throws {@link StructuredErrorException} on
     * any failure (caller — the controller — wraps as 400).
     */
    private void validatePatch(Map<String, Object> patch) {
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            switch (key) {
                case "prometheus_enabled", "tracing_enabled" -> {
                    if (!(value instanceof Boolean)) {
                        throw new StructuredErrorException(
                                ErrorCodes.PLATFORM_OBSERVABILITY_INTERVAL_OUT_OF_RANGE,
                                key + " must be a boolean",
                                Map.of("field", key, "received", String.valueOf(value)));
                    }
                }
                case "tracing_endpoint" -> validateTracingEndpoint(value);
                case "monitor_stale_interval_minutes",
                     "monitor_dv_canary_interval_minutes",
                     "monitor_temperature_interval_minutes" -> validateInterval(key, value);
                default -> throw new StructuredErrorException(
                        ErrorCodes.PLATFORM_OBSERVABILITY_INTERVAL_OUT_OF_RANGE,
                        "Unknown platform observability field: " + key,
                        Map.of("field", key));
            }
        }
    }

    private void validateTracingEndpoint(Object value) {
        if (!(value instanceof String s) || s.isBlank()) {
            throw new StructuredErrorException(
                    ErrorCodes.PLATFORM_OBSERVABILITY_TRACING_ENDPOINT_MALFORMED,
                    "tracing_endpoint must be a non-blank string",
                    Map.of("field", "tracing_endpoint"));
        }
        try {
            // Lenient — we don't require the URL to resolve, only that it
            // parses. An invalid OTLP endpoint will fail at exporter
            // initialization with a clearer error than this validator can
            // produce.
            java.net.URI uri = java.net.URI.create(s);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("missing scheme or host");
            }
        } catch (IllegalArgumentException e) {
            throw new StructuredErrorException(
                    ErrorCodes.PLATFORM_OBSERVABILITY_TRACING_ENDPOINT_MALFORMED,
                    "tracing_endpoint must be a valid URI: " + e.getMessage(),
                    Map.of("field", "tracing_endpoint", "value", s));
        }
    }

    private void validateInterval(String key, Object value) {
        // Jackson deserializes JSON ints into Integer or Long depending on
        // size. Accept both; reject non-integer types (Double, String).
        int intValue;
        if (value instanceof Integer i) intValue = i;
        else if (value instanceof Long l) intValue = Math.toIntExact(l);
        else {
            throw new StructuredErrorException(
                    ErrorCodes.PLATFORM_OBSERVABILITY_INTERVAL_OUT_OF_RANGE,
                    key + " must be an integer",
                    Map.of("field", key, "received", String.valueOf(value)));
        }
        if (intValue < 1 || intValue > 1440) {
            throw new StructuredErrorException(
                    ErrorCodes.PLATFORM_OBSERVABILITY_INTERVAL_OUT_OF_RANGE,
                    key + " must be in [1, 1440] minutes",
                    Map.of("field", key, "received", intValue, "min", 1, "max", 1440));
        }
    }

    /**
     * Defensive parse — converts the persisted JSONB into a {@link PlatformConfig}
     * record, falling back to {@link PlatformConfig#DEFAULTS} on missing keys
     * so partial-config rows don't crash startup.
     */
    private PlatformConfig parse(String json) throws JacksonException {
        Map<String, Object> raw = objectMapper.readValue(json,
                new TypeReference<Map<String, Object>>() {});
        return new PlatformConfig(
                getBool(raw, "prometheus_enabled", PlatformConfig.DEFAULTS.prometheusEnabled()),
                getBool(raw, "tracing_enabled", PlatformConfig.DEFAULTS.tracingEnabled()),
                getString(raw, "tracing_endpoint", PlatformConfig.DEFAULTS.tracingEndpoint()),
                getInt(raw, "monitor_stale_interval_minutes", PlatformConfig.DEFAULTS.monitorStaleIntervalMinutes()),
                getInt(raw, "monitor_dv_canary_interval_minutes", PlatformConfig.DEFAULTS.monitorDvCanaryIntervalMinutes()),
                getInt(raw, "monitor_temperature_interval_minutes", PlatformConfig.DEFAULTS.monitorTemperatureIntervalMinutes())
        );
    }

    private static boolean getBool(Map<String, Object> raw, String key, boolean fallback) {
        Object v = raw.get(key);
        return v instanceof Boolean b ? b : fallback;
    }

    private static String getString(Map<String, Object> raw, String key, String fallback) {
        Object v = raw.get(key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static int getInt(Map<String, Object> raw, String key, int fallback) {
        Object v = raw.get(key);
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return Math.toIntExact(l);
        return fallback;
    }

    /**
     * Test seam — exposed via package-private accessor for unit tests that
     * want to assert "the cache contains the freshly-loaded value". Real
     * code should use {@link #get}.
     */
    Map<String, Object> snapshotForTest() {
        Map<String, Object> snap = new LinkedHashMap<>();
        PlatformConfig cfg = cache.get();
        snap.put("prometheus_enabled", cfg.prometheusEnabled());
        snap.put("tracing_enabled", cfg.tracingEnabled());
        snap.put("tracing_endpoint", cfg.tracingEndpoint());
        snap.put("monitor_stale_interval_minutes", cfg.monitorStaleIntervalMinutes());
        snap.put("monitor_dv_canary_interval_minutes", cfg.monitorDvCanaryIntervalMinutes());
        snap.put("monitor_temperature_interval_minutes", cfg.monitorTemperatureIntervalMinutes());
        return snap;
    }
}
