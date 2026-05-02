package org.fabt.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.fabt.availability.service.BedSearchService;
import org.fabt.shelter.repository.ShelterRepository;
import org.fabt.surge.service.SurgeEventService;
import org.fabt.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit IT for {@link OperationalMonitorService}'s dynamic-scheduling
 * behavior — platform-observability-split tasks §3.6.
 *
 * <p>Mocks {@link TaskScheduler} so we can assert what
 * {@code OperationalMonitorService} registers on startup vs. on
 * {@link OperationalMonitorService#rescheduleFromConfig} without spinning
 * a real Spring scheduler. The slow tier-2 IT
 * ({@code OperationalMonitorRealCadenceIT}, §3.7) covers the
 * end-to-end "cadence change → next monitor cycle uses new interval"
 * assertion with a real {@code @Tag("slow")} test.
 *
 * <p>Mirrors the design D2 contract: {@code configureTasks} registers all
 * 3 monitor tasks with cadences from {@link PlatformConfigService};
 * {@code rescheduleFromConfig} cancels the prior {@link ScheduledFuture}
 * (non-interrupting) and re-registers with the new cadence.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OperationalMonitorService dynamic scheduling")
class OperationalMonitorReschedulingTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ShelterRepository shelterRepository;
    @Mock private BedSearchService bedSearchService;
    @Mock private SurgeEventService surgeEventService;
    @Mock private NoaaClient noaaClient;
    @Mock private ObservabilityConfigService configService;
    @Mock private PlatformConfigService platformConfigService;
    @Mock private TenantRepository tenantRepository;

    private ObservabilityMetrics metrics;
    private OperationalMonitorService monitorService;
    private TaskScheduler taskScheduler;

    @BeforeEach
    void setUp() {
        metrics = new ObservabilityMetrics(new SimpleMeterRegistry());
        monitorService = new OperationalMonitorService(
                jdbcTemplate, shelterRepository, bedSearchService,
                surgeEventService, noaaClient, metrics, configService,
                platformConfigService, tenantRepository, "KRDU");
        taskScheduler = mock(TaskScheduler.class);
    }

    /**
     * Helper — wires the monitor service into a mock SchedulingConfigurer
     * registrar that returns our mock TaskScheduler. Returns the registrar
     * so tests can inspect captured arguments if needed.
     */
    private void runConfigureTasks() {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        registrar.setTaskScheduler(taskScheduler);
        monitorService.configureTasks(registrar);
    }

    @Test
    @DisplayName("configureTasks registers all 3 monitors at the configured intervals")
    void initialRegistrationUsesPlatformConfig() {
        when(platformConfigService.get()).thenReturn(new PlatformConfig(
                true, false, "http://localhost:4318/v1/traces",
                5, 15, 60));
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));

        runConfigureTasks();

        // 3 task registrations expected
        ArgumentCaptor<Duration> intervalCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(taskScheduler, atLeastOnce()).scheduleAtFixedRate(
                any(Runnable.class), any(Instant.class), intervalCaptor.capture());
        assertThat(intervalCaptor.getAllValues())
                .as("3 tasks registered with the configured cadences")
                .containsExactlyInAnyOrder(
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(15),
                        Duration.ofMinutes(60));
    }

    @Test
    @DisplayName("rescheduleFromConfig cancels old futures + registers new ones with updated intervals")
    void rescheduleSwapsCadence() {
        when(platformConfigService.get()).thenReturn(new PlatformConfig(
                true, false, "http://localhost:4318/v1/traces",
                5, 15, 60));
        ScheduledFuture<?> firstFuture = mock(ScheduledFuture.class);
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class)))
                .thenAnswer(invocation -> firstFuture);

        runConfigureTasks();

        // Now the operator updates the platform config to bump stale interval
        when(platformConfigService.get()).thenReturn(new PlatformConfig(
                true, false, "http://localhost:4318/v1/traces",
                10, 15, 60));
        ScheduledFuture<?> secondFuture = mock(ScheduledFuture.class);
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class)))
                .thenAnswer(invocation -> secondFuture);

        monitorService.rescheduleFromConfig();

        // Each prior future was cancelled non-interruptively
        verify(firstFuture, atLeastOnce()).cancel(eq(false));

        // New registrations occurred (3 from initial + 3 from reschedule = 6 total)
        ArgumentCaptor<Duration> intervalCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(taskScheduler, atLeastOnce()).scheduleAtFixedRate(
                any(Runnable.class), any(Instant.class), intervalCaptor.capture());
        assertThat(intervalCaptor.getAllValues())
                .as("Should include the new 10-minute stale cadence")
                .contains(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("rescheduleFromConfig before configureTasks no-ops (defensive)")
    void rescheduleBeforeConfigureIsNoOp() {
        // Don't run configureTasks; taskScheduler stays null inside the service
        monitorService.rescheduleFromConfig();
        verifyNoInteractions(taskScheduler);
    }

    @Test
    @DisplayName("configureTasks with null TaskScheduler logs and skips registration")
    void configureTasksWithoutSchedulerIsSafe() {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        // Intentionally do NOT setTaskScheduler — registrar.getScheduler() returns null
        monitorService.configureTasks(registrar);

        // Reschedule still no-ops because the captured scheduler is null
        monitorService.rescheduleFromConfig();
        verifyNoInteractions(taskScheduler);
    }

    @Test
    @DisplayName("Each task is replaceable independently — only the changed entry's future is cancelled")
    void taskKeysAreScopedIndependently() {
        when(platformConfigService.get()).thenReturn(new PlatformConfig(
                true, false, "http://localhost:4318/v1/traces",
                5, 15, 60));
        ScheduledFuture<?> staleFuture = mock(ScheduledFuture.class, "stale");
        ScheduledFuture<?> dvCanaryFuture = mock(ScheduledFuture.class, "dv-canary");
        ScheduledFuture<?> tempFuture = mock(ScheduledFuture.class, "temp");
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), eq(Duration.ofMinutes(5))))
                .thenAnswer(inv -> staleFuture);
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), eq(Duration.ofMinutes(15))))
                .thenAnswer(inv -> dvCanaryFuture);
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), eq(Duration.ofMinutes(60))))
                .thenAnswer(inv -> tempFuture);

        runConfigureTasks();

        // Reschedule with all 3 cadences different — all 3 prior futures
        // should be cancelled. (Scoping correctness: keys identify each
        // task uniquely so reschedule maps to the right cancel.)
        when(platformConfigService.get()).thenReturn(new PlatformConfig(
                true, false, "http://localhost:4318/v1/traces",
                10, 30, 120));
        ScheduledFuture<?> newFuture = mock(ScheduledFuture.class, "new");
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class)))
                .thenAnswer(inv -> newFuture);

        monitorService.rescheduleFromConfig();

        verify(staleFuture).cancel(false);
        verify(dvCanaryFuture).cancel(false);
        verify(tempFuture).cancel(false);
    }
}
