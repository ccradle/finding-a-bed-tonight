package org.fabt.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
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
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Service;

/**
 * Operational monitor that periodically runs three platform-wide checks:
 * stale-shelter detection, DV canary verification, and temperature/surge
 * gap detection. Per-tenant logic still fans out via virtual threads, but
 * the cadence is platform-wide.
 *
 * <p><b>Dynamic scheduling (platform-observability-split, 2026-05-02):</b>
 * pre-refactor this class used {@code @Scheduled(fixedRate=300_000)}
 * literals. Those values were stored in {@code tenant.config} but never
 * read at runtime — silent-lies bug surfaced by the platform-observability-split
 * design audit. Now the class implements {@link SchedulingConfigurer} and
 * registers its tasks via {@link TaskScheduler}, reading the cadences from
 * {@link PlatformConfigService}. Operator-initiated changes via
 * {@code PUT /api/v1/platform/observability} call {@link #rescheduleFromConfig}
 * to swap the {@link ScheduledFuture} instances without restart.
 *
 * <p>Pattern mirrors {@code org.fabt.analytics.config.BatchJobScheduler}
 * (production since v0.42 — well-trodden ground for dynamic Spring scheduling).
 */
@Service
public class OperationalMonitorService implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(OperationalMonitorService.class);
    private static final Duration STALE_THRESHOLD = Duration.ofHours(8);
    private static final int MAX_CONCURRENT_TENANT_CHECKS = 10;

    /**
     * Initial delay for the temperature monitor — preserves the
     * pre-refactor 90-second warmup so the first run doesn't race the
     * {@link ObservabilityConfigService#refreshCache} that primes per-tenant
     * NOAA station ids.
     */
    private static final Duration TEMPERATURE_INITIAL_DELAY = Duration.ofSeconds(90);

    /** Stable keys for the 3 registered tasks — used by reschedule. */
    private static final String TASK_STALE = "stale";
    private static final String TASK_DV_CANARY = "dv-canary";
    private static final String TASK_TEMPERATURE = "temperature";

    private final JdbcTemplate jdbcTemplate;
    private final ShelterRepository shelterRepository;
    private final BedSearchService bedSearchService;
    private final SurgeEventService surgeEventService;
    private final NoaaClient noaaClient;
    private final ObservabilityMetrics metrics;
    private final ObservabilityConfigService configService;
    private final PlatformConfigService platformConfigService;
    private final TenantRepository tenantRepository;
    private final String defaultStationId;

    /**
     * Active scheduled futures keyed by {@link #TASK_STALE} / {@link #TASK_DV_CANARY}
     * / {@link #TASK_TEMPERATURE}. {@link #rescheduleFromConfig} cancels the
     * existing future (non-interrupting per design D2) and replaces with a
     * new registration when cadence changes.
     */
    private final Map<String, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();

    /**
     * Captured during {@link #configureTasks} — the same scheduler Spring
     * Boot would have used for {@code @Scheduled} methods. Null until the
     * SchedulingConfigurer hook fires; {@link #rescheduleFromConfig} no-ops
     * if called before then (defensive — shouldn't happen in production).
     */
    private TaskScheduler taskScheduler;

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
                                     PlatformConfigService platformConfigService,
                                     TenantRepository tenantRepository,
                                     @Value("${fabt.monitoring.noaa.station-id:KRDU}") String defaultStationId) {
        this.jdbcTemplate = jdbcTemplate;
        this.shelterRepository = shelterRepository;
        this.bedSearchService = bedSearchService;
        this.surgeEventService = surgeEventService;
        this.noaaClient = noaaClient;
        this.metrics = metrics;
        this.configService = configService;
        this.platformConfigService = platformConfigService;
        this.tenantRepository = tenantRepository;
        this.defaultStationId = defaultStationId;
    }

    /**
     * SchedulingConfigurer hook — Spring calls this once at startup with
     * the framework's TaskScheduler. We capture the scheduler reference and
     * register all three monitor tasks with cadences from
     * {@link PlatformConfigService}. Mirrors {@code BatchJobScheduler.configureTasks}.
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        this.taskScheduler = registrar.getScheduler();
        if (this.taskScheduler == null) {
            log.warn("ScheduledTaskRegistrar provided no TaskScheduler; monitor tasks will NOT register. "
                    + "This typically indicates a misconfigured @EnableScheduling — file a bug.");
            return;
        }
        scheduleAll(true);
    }

    /**
     * Re-register all monitor tasks with the latest cadences from
     * {@link PlatformConfigService}. Called by
     * {@code PlatformObservabilityController} after a successful
     * platform-config write so cadence changes take effect within one
     * cycle (no restart needed).
     *
     * <p><b>Mid-flight safety:</b> uses {@code future.cancel(false)} —
     * non-interrupting. A monitor task currently mid-fan-out completes
     * with the old cadence; the next invocation uses the new one. No
     * orphaned-future risk because we replace the map entry atomically.
     */
    public void rescheduleFromConfig() {
        if (this.taskScheduler == null) {
            log.debug("rescheduleFromConfig() called before SchedulingConfigurer captured the scheduler; no-op");
            return;
        }
        scheduleAll(false);
    }

    /**
     * Register all three monitor tasks. {@code initialRun} controls whether
     * the temperature monitor gets its 90-second warmup delay (only on
     * the very first registration during application startup; reschedules
     * skip the delay because the cache is already primed).
     */
    private void scheduleAll(boolean initialRun) {
        PlatformConfig cfg = platformConfigService.get();
        rescheduleTask(TASK_STALE, this::checkStaleShelters,
                Duration.ofMinutes(cfg.monitorStaleIntervalMinutes()), Duration.ZERO);
        rescheduleTask(TASK_DV_CANARY, this::checkDvCanary,
                Duration.ofMinutes(cfg.monitorDvCanaryIntervalMinutes()), Duration.ZERO);
        rescheduleTask(TASK_TEMPERATURE, this::checkTemperatureSurgeGap,
                Duration.ofMinutes(cfg.monitorTemperatureIntervalMinutes()),
                initialRun ? TEMPERATURE_INITIAL_DELAY : Duration.ZERO);
        log.info("Operational monitors registered: stale={}min, dv-canary={}min, temperature={}min",
                cfg.monitorStaleIntervalMinutes(),
                cfg.monitorDvCanaryIntervalMinutes(),
                cfg.monitorTemperatureIntervalMinutes());
    }

    /**
     * Cancel the prior future (if any) for {@code key} and register a new
     * one. {@code initialDelay} is the wait before the first invocation;
     * {@code interval} is the period between subsequent invocations.
     */
    private void rescheduleTask(String key, Runnable task, Duration interval, Duration initialDelay) {
        ScheduledFuture<?> existing = scheduledFutures.remove(key);
        if (existing != null) {
            existing.cancel(false); // non-interrupting per design D2
        }
        Instant firstRun = Instant.now().plus(initialDelay);
        scheduledFutures.put(key, taskScheduler.scheduleAtFixedRate(task, firstRun, interval));
    }

    public TemperatureStatus getTemperatureStatus(UUID tenantId) {
        return cachedTemperatureByTenant.get(tenantId);
    }

    /**
     * Monitor 1: Stale shelter detection.
     * Cadence is platform-configurable (default 5 minutes; see
     * {@link PlatformConfig#monitorStaleIntervalMinutes()}). Per-tenant
     * checks fan out on virtual threads.
     */
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
     * Cadence is platform-configurable (default 15 minutes; see
     * {@link PlatformConfig#monitorDvCanaryIntervalMinutes()}). Per-tenant
     * canary checks fan out on virtual threads with dvAccess=false to
     * verify DV shelters are hidden.
     */
    public void checkDvCanary() {
        List<UUID> tenantIds = tenantIds();
        BoundedFanOut.forEachTenant(tenantIds, false, MAX_CONCURRENT_TENANT_CHECKS, tenantId -> {
            BedSearchRequest request = new BedSearchRequest(null, null, null, 100, null, null, null);
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
     * Cadence is platform-configurable (default 1 hour; see
     * {@link PlatformConfig#monitorTemperatureIntervalMinutes()}). NOAA
     * fetch is a single call per distinct station; per-tenant surge gap
     * evaluation fans out on virtual threads.
     */
    public void checkTemperatureSurgeGap() {
        // initialDelay=90s ensures PlatformConfigService + per-tenant config
        // caches are primed before the first per-tenant station lookup.
        // The cadence (monitorTemperatureIntervalMinutes) is set when the
        // task is registered in scheduleAll() — platform-wide. The body only
        // needs the per-tenant station id + threshold (both per-tenant).
        List<UUID> tenantIds = tenantIds();
        // Fetch temperature once per distinct station across all tenants this cycle,
        // so multiple tenants sharing a station don't hit NOAA N times.
        Map<String, Double> tempByStation = new ConcurrentHashMap<>();
        BoundedFanOut.forEachTenant(tenantIds, false, MAX_CONCURRENT_TENANT_CHECKS, tenantId -> {
            var perTenantCfg = configService.getConfig(tenantId);
            String resolvedStation = perTenantCfg.noaaStationId() != null
                    ? perTenantCfg.noaaStationId() : defaultStationId;
            Double tempF = tempByStation.computeIfAbsent(resolvedStation, noaaClient::getCurrentTemperatureFahrenheit);
            if (tempF == null) {
                log.debug("Temperature monitor: unable to fetch NOAA data for station {} (tenant {}), skipping", resolvedStation, tenantId);
                return;
            }

            double threshold = perTenantCfg.temperatureThresholdF();
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
