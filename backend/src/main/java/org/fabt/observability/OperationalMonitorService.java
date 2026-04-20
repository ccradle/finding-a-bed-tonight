package org.fabt.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.StreamSupport;

import org.fabt.availability.domain.BedSearchRequest;
import org.fabt.availability.service.BedSearchService;
import org.fabt.availability.service.BedSearchService.BedSearchResponse;
import org.fabt.shared.concurrent.BoundedFanOut;
import org.fabt.shelter.domain.Shelter;
import org.fabt.shelter.repository.ShelterRepository;
import org.fabt.surge.service.SurgeEventService;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class OperationalMonitorService {

    private static final Logger log = LoggerFactory.getLogger(OperationalMonitorService.class);
    private static final Duration STALE_THRESHOLD = Duration.ofHours(8);
    private static final int MAX_CONCURRENT_TENANT_CHECKS = 10;

    private final JdbcTemplate jdbcTemplate;
    private final ShelterRepository shelterRepository;
    private final BedSearchService bedSearchService;
    private final SurgeEventService surgeEventService;
    private final NoaaClient noaaClient;
    private final ObservabilityMetrics metrics;
    private final ObservabilityConfigService configService;
    private final TenantRepository tenantRepository;
    private final String defaultStationId;

    // Per-tenant temperature status cache. Populated by the scheduled monitor;
    // read by the /api/v1/monitoring/temperature endpoint scoped to the
    // caller's tenant. Each tenant may use a different NOAA station (Option A,
    // per-tenant-weather-station requirement).
    private final Map<UUID, TemperatureStatus> cachedTemperatureByTenant = new ConcurrentHashMap<>();

    public record TemperatureStatus(
            Double temperatureF,
            String stationId,
            double thresholdF,
            boolean surgeActive,
            boolean gapDetected,
            Instant lastChecked
    ) {}

    public OperationalMonitorService(JdbcTemplate jdbcTemplate,
                                     ShelterRepository shelterRepository,
                                     BedSearchService bedSearchService,
                                     SurgeEventService surgeEventService,
                                     NoaaClient noaaClient,
                                     ObservabilityMetrics metrics,
                                     ObservabilityConfigService configService,
                                     TenantRepository tenantRepository,
                                     @Value("${fabt.monitoring.noaa.station-id:KRDU}") String defaultStationId) {
        this.jdbcTemplate = jdbcTemplate;
        this.shelterRepository = shelterRepository;
        this.bedSearchService = bedSearchService;
        this.surgeEventService = surgeEventService;
        this.noaaClient = noaaClient;
        this.metrics = metrics;
        this.configService = configService;
        this.tenantRepository = tenantRepository;
        this.defaultStationId = defaultStationId;
    }

    public TemperatureStatus getTemperatureStatus(UUID tenantId) {
        return cachedTemperatureByTenant.get(tenantId);
    }

    /**
     * Monitor 1: Stale shelter detection.
     * Runs every 5 minutes. Per-tenant checks fan out on virtual threads.
     */
    @Scheduled(fixedRate = 300_000)
    public void checkStaleShelters() {
        List<UUID> tenantIds = tenantIds();
        BoundedFanOut.forEachTenant(tenantIds, false, MAX_CONCURRENT_TENANT_CHECKS, tenantId -> {
            List<Shelter> shelters = shelterRepository.findByTenantId(tenantId);
            List<Shelter> staleShelters = new ArrayList<>();

            for (Shelter shelter : shelters) {
                Instant latestSnapshot = getLatestSnapshotTime(shelter.getId());
                if (latestSnapshot == null || Duration.between(latestSnapshot, Instant.now()).compareTo(STALE_THRESHOLD) > 0) {
                    staleShelters.add(shelter);
                    log.warn("Stale shelter detected: shelterId={}, shelterName={}, lastUpdate={}",
                            shelter.getId(), shelter.getName(), latestSnapshot);
                }
            }

            metrics.setStaleShelterCount(staleShelters.size());

            if (!staleShelters.isEmpty()) {
                log.warn("Stale shelter summary: {} of {} shelters stale for tenant {}",
                        staleShelters.size(), shelters.size(), tenantId);
            }
        });
    }

    /**
     * Monitor 2: DV canary check.
     * Runs every 15 minutes. Per-tenant canary checks fan out on virtual threads
     * with dvAccess=false to verify DV shelters are hidden.
     */
    @Scheduled(fixedRate = 900_000)
    public void checkDvCanary() {
        List<UUID> tenantIds = tenantIds();
        BoundedFanOut.forEachTenant(tenantIds, false, MAX_CONCURRENT_TENANT_CHECKS, tenantId -> {
            BedSearchRequest request = new BedSearchRequest(null, null, null, 100);
            BedSearchResponse response = bedSearchService.search(request);

            // Check if any DV shelters leaked into results
            List<Shelter> allShelters = shelterRepository.findByTenantId(tenantId);
            boolean dvLeaked = false;

            for (var result : response.results()) {
                for (Shelter shelter : allShelters) {
                    if (shelter.getId().equals(result.shelterId()) && shelter.isDvShelter()) {
                        dvLeaked = true;
                        log.error("CRITICAL: DV canary FAILED — DV shelter {} ({}) appeared in non-DV search results for tenant {}",
                                shelter.getId(), shelter.getName(), tenantId);
                    }
                }
            }

            metrics.setDvCanaryPass(!dvLeaked);

            if (!dvLeaked) {
                log.debug("DV canary passed for tenant {}", tenantId);
            }
        });
    }

    /**
     * Monitor 3: Temperature/surge gap detection.
     * Runs every hour. NOAA fetch is a single call; per-tenant surge gap evaluation
     * fans out on virtual threads.
     */
    @Scheduled(fixedRate = 3_600_000, initialDelay = 90_000)
    public void checkTemperatureSurgeGap() {
        // initialDelay=90s ensures ObservabilityConfigService.refreshCache() (runs
        // every 60s from t=0) has populated per-tenant config before the first
        // per-tenant station lookup. Without it the first run races the cache
        // and all tenants fall back to the global default station.
        List<UUID> tenantIds = tenantIds();
        // Fetch temperature once per distinct station across all tenants this cycle,
        // so multiple tenants sharing a station don't hit NOAA N times.
        Map<String, Double> tempByStation = new ConcurrentHashMap<>();
        BoundedFanOut.forEachTenant(tenantIds, false, MAX_CONCURRENT_TENANT_CHECKS, tenantId -> {
            var cfg = configService.getConfig(tenantId);
            String resolvedStation = cfg.noaaStationId() != null ? cfg.noaaStationId() : defaultStationId;
            Double tempF = tempByStation.computeIfAbsent(resolvedStation, noaaClient::getCurrentTemperatureFahrenheit);
            if (tempF == null) {
                log.debug("Temperature monitor: unable to fetch NOAA data for station {} (tenant {}), skipping", resolvedStation, tenantId);
                return;
            }

            double threshold = cfg.temperatureThresholdF();
            boolean surgeActive = surgeEventService.getActive().isPresent();
            boolean gapDetected = tempF < threshold && !surgeActive;

            metrics.setTemperatureSurgeGap(gapDetected);

            cachedTemperatureByTenant.put(tenantId, new TemperatureStatus(
                    tempF, resolvedStation, threshold, surgeActive, gapDetected, Instant.now()));

            if (gapDetected) {
                log.warn("Temperature/surge gap: temperature is {}°F (below threshold {}°F) but no active surge for tenant {} (station {}). Consider activating surge mode.",
                        String.format("%.1f", tempF), String.format("%.0f", threshold), tenantId, resolvedStation);
            }
        });
    }

    private List<UUID> tenantIds() {
        return StreamSupport.stream(tenantRepository.findAll().spliterator(), false)
                .map(Tenant::getId)
                .toList();
    }

    private Instant getLatestSnapshotTime(UUID shelterId) {
        List<Instant> results = jdbcTemplate.query(
                "SELECT MAX(snapshot_ts) FROM bed_availability WHERE shelter_id = ?",
                (rs, rowNum) -> {
                    java.sql.Timestamp ts = rs.getTimestamp(1);
                    return ts != null ? ts.toInstant() : null;
                },
                shelterId
        );
        return results.isEmpty() ? null : results.get(0);
    }
}
