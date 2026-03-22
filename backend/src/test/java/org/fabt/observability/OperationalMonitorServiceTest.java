package org.fabt.observability;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.fabt.availability.domain.BedSearchRequest;
import org.fabt.availability.domain.BedSearchResult;
import org.fabt.availability.service.BedSearchService;
import org.fabt.availability.service.BedSearchService.BedSearchResponse;
import org.fabt.shelter.domain.Shelter;
import org.fabt.shelter.repository.ShelterRepository;
import org.fabt.surge.domain.SurgeEvent;
import org.fabt.surge.service.SurgeEventService;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OperationalMonitorServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ShelterRepository shelterRepository;
    @Mock private BedSearchService bedSearchService;
    @Mock private SurgeEventService surgeEventService;
    @Mock private NoaaClient noaaClient;
    @Mock private ObservabilityConfigService configService;
    @Mock private TenantRepository tenantRepository;

    private ObservabilityMetrics metrics;
    private OperationalMonitorService monitorService;

    private final UUID tenantId = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    private final UUID shelterId = UUID.fromString("c0000000-0000-0000-0000-000000000001");
    private final UUID dvShelterId = UUID.fromString("c0000000-0000-0000-0000-000000000002");

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics = new ObservabilityMetrics(registry);
        monitorService = new OperationalMonitorService(
                jdbcTemplate, shelterRepository, bedSearchService,
                surgeEventService, noaaClient, metrics, configService, tenantRepository, "KRDU");

        // Default config returns 32°F threshold
        lenient().when(configService.getConfig(any())).thenReturn(ObservabilityConfigService.ObservabilityConfig.DEFAULTS);
    }

    private Tenant createTenant() {
        Tenant t = new Tenant();
        t.setId(tenantId);
        t.setName("Test CoC");
        t.setSlug("test-coc");
        return t;
    }

    private Shelter createShelter(UUID id, String name, boolean dvShelter) {
        Shelter s = new Shelter();
        s.setId(id);
        s.setTenantId(tenantId);
        s.setName(name);
        s.setDvShelter(dvShelter);
        return s;
    }

    // --- Monitor 1: Stale shelter detection ---

    @Test
    void checkStaleShelters_detectsStaleShelter() {
        Tenant tenant = createTenant();
        Shelter shelter = createShelter(shelterId, "Safe Haven", false);

        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(shelterRepository.findByTenantId(tenantId)).thenReturn(List.of(shelter));

        // Shelter has no snapshot (returns empty list — no rows)
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(shelterId)))
                .thenReturn(List.of());

        monitorService.checkStaleShelters();

        // Stale count should be 1 (no snapshot at all = stale)
        assertEquals(1, (int) metrics.getStaleShelterCountValue());
    }

    @Test
    void checkStaleShelters_freshShelter_noAlert() {
        Tenant tenant = createTenant();
        Shelter shelter = createShelter(shelterId, "Safe Haven", false);

        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(shelterRepository.findByTenantId(tenantId)).thenReturn(List.of(shelter));

        // Shelter has a recent snapshot (1 hour ago)
        Instant recentSnapshot = Instant.now().minus(1, ChronoUnit.HOURS);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(shelterId)))
                .thenReturn(List.of(recentSnapshot));

        monitorService.checkStaleShelters();

        assertEquals(0, (int) metrics.getStaleShelterCountValue());
    }

    // --- Monitor 2: DV canary ---

    @Test
    void checkDvCanary_dvShelterLeaked_failsCanary() {
        Tenant tenant = createTenant();
        Shelter dvShelter = createShelter(dvShelterId, "DV Safe House", true);
        Shelter normalShelter = createShelter(shelterId, "Safe Haven", false);

        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(shelterRepository.findByTenantId(tenantId)).thenReturn(List.of(normalShelter, dvShelter));

        // Simulate DV shelter appearing in non-DV search results
        BedSearchResult dvResult = new BedSearchResult(
                dvShelterId, "DV Safe House", "123 Main St", "555-0001",
                null, null, List.of(), null, "FRESH", null, null, false);
        BedSearchResponse response = new BedSearchResponse(List.of(dvResult), 1);
        when(bedSearchService.search(any(BedSearchRequest.class))).thenReturn(response);

        monitorService.checkDvCanary();

        // DV canary should fail (gauge = 0)
        assertEquals(0.0, metrics.getDvCanaryPassValue());
    }

    @Test
    void checkDvCanary_noDvShelterInResults_passes() {
        Tenant tenant = createTenant();
        Shelter normalShelter = createShelter(shelterId, "Safe Haven", false);
        Shelter dvShelter = createShelter(dvShelterId, "DV Safe House", true);

        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(shelterRepository.findByTenantId(tenantId)).thenReturn(List.of(normalShelter, dvShelter));

        // Only non-DV shelter in results
        BedSearchResult normalResult = new BedSearchResult(
                shelterId, "Safe Haven", "456 Oak Ave", "555-0002",
                null, null, List.of(), null, "FRESH", null, null, false);
        BedSearchResponse response = new BedSearchResponse(List.of(normalResult), 1);
        when(bedSearchService.search(any(BedSearchRequest.class))).thenReturn(response);

        monitorService.checkDvCanary();

        // DV canary should pass (gauge = 1)
        assertEquals(1.0, metrics.getDvCanaryPassValue());
    }

    // --- Monitor 3: Temperature/surge gap ---

    @Test
    void checkTemperatureSurgeGap_coldNoSurge_publishesGaugeAndCachesStatus() {
        Tenant tenant = createTenant();
        when(noaaClient.getCurrentTemperatureFahrenheit()).thenReturn(25.0);
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(surgeEventService.getActive()).thenReturn(Optional.empty());

        monitorService.checkTemperatureSurgeGap();

        assertEquals(1.0, metrics.getTemperatureSurgeGapValue(), "Gap gauge should be 1 when cold + no surge");
        assertNotNull(monitorService.getTemperatureStatus());
        assertTrue(monitorService.getTemperatureStatus().gapDetected());
        assertEquals(25.0, monitorService.getTemperatureStatus().temperatureF());
        assertEquals("KRDU", monitorService.getTemperatureStatus().stationId());
    }

    @Test
    void checkTemperatureSurgeGap_coldWithSurge_noGap() {
        Tenant tenant = createTenant();
        when(noaaClient.getCurrentTemperatureFahrenheit()).thenReturn(25.0);
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(surgeEventService.getActive()).thenReturn(Optional.of(mock(SurgeEvent.class)));

        monitorService.checkTemperatureSurgeGap();

        assertEquals(0.0, metrics.getTemperatureSurgeGapValue(), "Gap gauge should be 0 when surge is active");
        assertFalse(monitorService.getTemperatureStatus().gapDetected());
    }

    @Test
    void checkTemperatureSurgeGap_warmWeather_noGap() {
        Tenant tenant = createTenant();
        when(noaaClient.getCurrentTemperatureFahrenheit()).thenReturn(55.0);
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(surgeEventService.getActive()).thenReturn(Optional.empty());

        monitorService.checkTemperatureSurgeGap();

        assertEquals(0.0, metrics.getTemperatureSurgeGapValue(), "Gap gauge should be 0 when warm");
        assertFalse(monitorService.getTemperatureStatus().gapDetected());
    }

    @Test
    void checkTemperatureSurgeGap_configurableThreshold() {
        Tenant tenant = createTenant();
        // Configure a higher threshold (40°F)
        when(configService.getConfig(tenantId)).thenReturn(
                new ObservabilityConfigService.ObservabilityConfig(
                        true, false, "http://localhost:4318/v1/traces", 5, 15, 60, 40.0));
        when(noaaClient.getCurrentTemperatureFahrenheit()).thenReturn(35.0);
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(surgeEventService.getActive()).thenReturn(Optional.empty());

        monitorService.checkTemperatureSurgeGap();

        // 35°F is below 40°F threshold, gap should be detected
        assertEquals(1.0, metrics.getTemperatureSurgeGapValue());
        assertTrue(monitorService.getTemperatureStatus().gapDetected());
        assertEquals(40.0, monitorService.getTemperatureStatus().thresholdF());
    }

    @Test
    void checkTemperatureSurgeGap_noaaUnavailable_skipsCheck() {
        when(noaaClient.getCurrentTemperatureFahrenheit()).thenReturn(null);

        assertDoesNotThrow(() -> monitorService.checkTemperatureSurgeGap());
        verifyNoInteractions(tenantRepository);
    }
}
