package org.fabt.observability;

/**
 * Platform-level observability configuration — the 6 tenant-agnostic fields
 * that moved out of {@code tenant.config.observability} per the
 * platform-observability-split openspec change (2026-05-02).
 *
 * <p>Stored in the singleton {@code platform_config} JSONB column (V98).
 * Loaded once at startup by {@link PlatformConfigService} and refreshed
 * after every {@code PUT /api/v1/platform/observability} call. The
 * scheduler ({@link OperationalMonitorService}) re-registers its
 * {@code ScheduledFuture}s when the monitor-interval fields change,
 * so cadence updates take effect on the next cycle without restart.
 *
 * <p>Field-by-field rationale (see openspec design D1 + warroom 2026-05-02):
 * <ul>
 *   <li>{@code prometheusEnabled} — JVM-level Prometheus scrape gate. Per-tenant
 *       toggling never made architectural sense (one app = one scrape endpoint);
 *       moved to platform.</li>
 *   <li>{@code tracingEnabled} — JVM-level OTel exporter. Same rationale.</li>
 *   <li>{@code tracingEndpoint} — OTLP collector URL. Infrastructure concern.</li>
 *   <li>{@code monitorStaleIntervalMinutes} — could in principle be per-tenant
 *       (different CoCs have different stale-tolerance), but the underlying
 *       {@code @Scheduled} runs once globally. Until per-tenant scheduling lands,
 *       cadence is platform-wide.</li>
 *   <li>{@code monitorDvCanaryIntervalMinutes} — security health probe (verify
 *       RLS hides DV shelters). The platform's security SLO, not any single
 *       CoC's operational rhythm.</li>
 *   <li>{@code monitorTemperatureIntervalMinutes} — NOAA fetch cadence is
 *       constrained by NOAA's API rate limits. The platform shares one API
 *       key across tenants; per-tenant cadence wouldn't help.</li>
 * </ul>
 *
 * <p>Bounds (enforced by {@code PlatformObservabilityController} on writes):
 * each interval ∈ [1, 1440] minutes; tracing endpoint must be a non-empty
 * URI-shaped string.
 *
 * <p>{@link #DEFAULTS} matches the literal {@code @Scheduled fixedRate}
 * cadences the {@code OperationalMonitorService} used pre-refactor (5/15/60
 * minutes) — startup with an unmigrated DB returns these values, so
 * behavior is unchanged on a fresh boot.
 */
public record PlatformConfig(
        boolean prometheusEnabled,
        boolean tracingEnabled,
        String tracingEndpoint,
        int monitorStaleIntervalMinutes,
        int monitorDvCanaryIntervalMinutes,
        int monitorTemperatureIntervalMinutes
) {
    /**
     * Defaults that match the pre-refactor literal scheduler rates and the
     * baseline OTel endpoint shipped via {@code application.yml}. These are
     * also the values seeded by V98 migration into {@code platform_config.config}.
     */
    public static final PlatformConfig DEFAULTS = new PlatformConfig(
            true,
            false,
            "http://localhost:4318/v1/traces",
            5,
            15,
            60
    );
}
