package org.fabt.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
    private final String stationId;

    // Cached temperature state for API display
    private volatile TemperatureStatus cachedTemperatureStatus;

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
                                     @Value("${fabt.monitoring.noaa.station-id:KRDU}") String stationId) {
        this.jdbcTemplate = jdbcTemplate;
        this.shelterRepository = shelterRepository;
        this.bedSearchService = bedSearchService;
        this.surgeEventService = surgeEventService;
        this.noaaClient = noaaClient;
        this.metrics = metrics;
        this.configService = configService;
        this.tenantRepository = tenantRepository;
        this.stationId = stationId;
    }

    public TemperatureStatus getTemperatureStatus() {
        return cachedTemperatureStatus;
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
    @Scheduled(fixedRate = 3_600_000)
    public void checkTemperatureSurgeGap() {
        Double tempF = noaaClient.getCurrentTemperatureFahrenheit();
        if (tempF == null) {
            log.debug("Temperature monitor: unable to fetch NOAA data, skipping check");
            return;
        }

        List<UUID> tenantIds = tenantIds();
        BoundedFanOut.forEachTenant(tenantIds, false, MAX_CONCURRENT_TENANT_CHECKS, tenantId -> {
            double threshold = configService.getConfig(tenantId).temperatureThresholdF();
            boolean surgeActive = surgeEventService.getActive().isPresent();
            boolean gapDetected = tempF < threshold && !surgeActive;

            metrics.setTemperatureSurgeGap(gapDetected);

            // Cache for API display
            cachedTemperatureStatus = new TemperatureStatus(
                    tempF, stationId, threshold, surgeActive, gapDetected, Instant.now());

            if (gapDetected) {
                log.warn("Temperature/surge gap: temperature is {}°F (below threshold {}°F) but no active surge for tenant {}. Consider activating surge mode.",
                        String.format("%.1f", tempF), String.format("%.0f", threshold), tenantId);
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
