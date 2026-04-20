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

    public record ObservabilityConfig(
            boolean prometheusEnabled,
            boolean tracingEnabled,
            String tracingEndpoint,
            int monitorStaleIntervalMinutes,
            int monitorDvCanaryIntervalMinutes,
            int monitorTemperatureIntervalMinutes,
            double temperatureThresholdF,
            String noaaStationId
    ) {
        // noaaStationId is nullable — null means fall back to the global
        // fabt.monitoring.noaa.station-id property (see NoaaClient). Set
        // per-tenant to KAVL / KEWN / etc. to get correct local weather for
        // the surge-trigger threshold.
        static final ObservabilityConfig DEFAULTS = new ObservabilityConfig(
                true, false, "http://localhost:4318/v1/traces", 5, 15, 60, 32.0, null
        );
    }

    public boolean isTracingEnabled(UUID tenantId) {
        return getConfig(tenantId).tracingEnabled();
    }

    public boolean isPrometheusEnabled(UUID tenantId) {
        return getConfig(tenantId).prometheusEnabled();
    }

    public ObservabilityConfig getMonitorIntervals(UUID tenantId) {
        return getConfig(tenantId);
    }

    public ObservabilityConfig getConfig(UUID tenantId) {
        return configCache.getOrDefault(tenantId, ObservabilityConfig.DEFAULTS);
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

            return new ObservabilityConfig(
                    getBooleanOrDefault(obs, "prometheus_enabled", true),
                    getBooleanOrDefault(obs, "tracing_enabled", false),
                    getStringOrDefault(obs, "tracing_endpoint", "http://localhost:4318/v1/traces"),
                    getIntOrDefault(obs, "monitor_stale_interval_minutes", 5),
                    getIntOrDefault(obs, "monitor_dv_canary_interval_minutes", 15),
                    getIntOrDefault(obs, "monitor_temperature_interval_minutes", 60),
                    getDoubleOrDefault(obs, "temperature_threshold_f", 32.0),
                    getStringOrNull(obs, "noaa_station_id")
            );
        } catch (Exception e) {
            log.warn("Failed to parse observability JSON for tenant {}: {}", tenant.getId(), e.getMessage());
            return ObservabilityConfig.DEFAULTS;
        }
    }

    private static boolean getBooleanOrDefault(JsonNode node, String field, boolean defaultValue) {
        JsonNode value = node.get(field);
        return value != null ? value.asBoolean(defaultValue) : defaultValue;
    }

    private static String getStringOrDefault(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText(defaultValue) : defaultValue;
    }

    private static String getStringOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText(null);
        return text == null || text.isBlank() ? null : text;
    }

    private static int getIntOrDefault(JsonNode node, String field, int defaultValue) {
        JsonNode value = node.get(field);
        return value != null ? value.asInt(defaultValue) : defaultValue;
    }

    private static double getDoubleOrDefault(JsonNode node, String field, double defaultValue) {
        JsonNode value = node.get(field);
        return value != null ? value.asDouble(defaultValue) : defaultValue;
    }
}
