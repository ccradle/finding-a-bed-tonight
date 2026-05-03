package org.fabt.observability;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Per-tenant surge-trigger config reader. Caches the parsed
 * {@code tenant.config.observability} sub-map for every tenant and refreshes
 * it on a fixed schedule.
 *
 * <p><b>platform-observability-split (2026-05-02):</b> the 6 platform-wide
 * fields (prometheus_enabled, tracing_enabled, tracing_endpoint, and the 3
 * monitor cadences) moved to {@link PlatformConfigService}. This service now
 * exposes ONLY the genuine per-tenant fields: {@code temperatureThresholdF}
 * (geographic surge threshold, varies by tenant) and {@code noaaStationId}
 * (local weather station id). The original record kept the wider field set
 * for one release as a backward-read; warroom round 4 (2026-05-03) closed
 * that off — the obsoleted JSONB keys are still tolerated on read (parser
 * silently ignores unknown sub-fields), they are simply no longer surfaced
 * by the Java type system. JSONB key drop happens in the v0.58+ follow-up
 * (§15.1).
 *
 * <p>The class name {@code ObservabilityConfigService} is kept for now to
 * avoid a churn-only rename across many call sites; a v0.58+ rename to
 * {@code TenantSurgeConfigService} is captured as §15.2.
 */
@Service
public class ObservabilityConfigService {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityConfigService.class);

    private final TenantRepository tenantRepository;
    private final ObjectMapper objectMapper;
    private final Map<UUID, ObservabilityConfig> configCache = new ConcurrentHashMap<>();

    public ObservabilityConfigService(TenantRepository tenantRepository, ObjectMapper objectMapper) {
        this.tenantRepository = tenantRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Per-tenant surge config. Two fields:
     * <ul>
     *   <li>{@code temperatureThresholdF} — Fahrenheit threshold below which the
     *       monitor flags a temperature/surge gap. Geographic; per-tenant.</li>
     *   <li>{@code noaaStationId} — local NOAA weather station code (KAVL, KEWN,
     *       …); nullable, falling back to the global
     *       {@code fabt.monitoring.noaa.station-id} property when null.</li>
     * </ul>
     *
     * <p>The 6 platform-wide fields (prometheus, tracing, monitor cadences)
     * formerly on this record live on {@link PlatformConfig} now.
     */
    public record ObservabilityConfig(
            double temperatureThresholdF,
            String noaaStationId
    ) {
        static final ObservabilityConfig DEFAULTS = new ObservabilityConfig(32.0, null);
    }

    public ObservabilityConfig getConfig(UUID tenantId) {
        return configCache.getOrDefault(tenantId, ObservabilityConfig.DEFAULTS);
    }

    /**
     * Per-tenant temperature threshold (geographic concern — per-tenant).
     * Platform-wide fields moved to PlatformConfigService in
     * platform-observability-split.
     */
    public Double getTemperatureThresholdF(UUID tenantId) {
        return getConfig(tenantId).temperatureThresholdF();
    }

    public String getNoaaStationId(UUID tenantId) {
        return getConfig(tenantId).noaaStationId();
    }

    @Scheduled(fixedRate = 60_000)
    public void refreshCache() {
        for (Tenant tenant : tenantRepository.findAll()) {
            try {
                ObservabilityConfig config = parseConfig(tenant);
                configCache.put(tenant.getId(), config);
            } catch (Exception e) {
                log.warn("Failed to parse observability config for tenant {}: {}", tenant.getId(), e.getMessage());
            }
        }
    }

    private ObservabilityConfig parseConfig(Tenant tenant) {
        if (tenant.getConfig() == null || tenant.getConfig().value() == null || tenant.getConfig().value().isBlank()) {
            return ObservabilityConfig.DEFAULTS;
        }

        try {
            JsonNode root = objectMapper.readTree(tenant.getConfig().value());
            JsonNode obs = root.get("observability");
            if (obs == null) {
                return ObservabilityConfig.DEFAULTS;
            }

            // Backward-read: obsoleted platform-wide JSONB keys
            // (prometheus_enabled, tracing_enabled, tracing_endpoint,
            // monitor_*_interval_minutes) may still be present on prod tenant
            // rows from before V98. We read but DO NOT expose them — the
            // PlatformConfig surface has authority. Per design D3 the keys
            // are dropped in v0.58+.
            return new ObservabilityConfig(
                    getDoubleOrDefault(obs, "temperature_threshold_f", 32.0),
                    getStringOrNull(obs, "noaa_station_id")
            );
        } catch (Exception e) {
            log.warn("Failed to parse observability JSON for tenant {}: {}", tenant.getId(), e.getMessage());
            return ObservabilityConfig.DEFAULTS;
        }
    }

    private static String getStringOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText(null);
        return text == null || text.isBlank() ? null : text;
    }

    private static double getDoubleOrDefault(JsonNode node, String field, double defaultValue) {
        JsonNode value = node.get(field);
        return value != null ? value.asDouble(defaultValue) : defaultValue;
    }
}
