package org.fabt.architecture;

import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;

import io.micrometer.core.instrument.MeterRegistry;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.analytics.service.AnalyticsService;
import org.fabt.availability.domain.BedSearchRequest;
import org.fabt.availability.service.BedSearchService;
import org.fabt.shared.cache.CacheNames;
import org.fabt.shared.cache.TenantScopedCacheService;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase C task 4.b fold-in (Riley) — post-migration cache hit-rate sanity
 * across ALL 4.b migrated sites (Analytics × 6 + BedSearch × 1).
 *
 * <p>After the 4.b migration swaps raw {@code CacheService} for
 * {@code TenantScopedCacheService} across the 9-site allowlist, this test
 * guards against the regression where the migration accidentally produces
 * a different key string on the {@code put} path vs. the {@code get} path
 * (composite-key {@code toString()} drift, accidentally stripping a
 * caller-side prefix on one side only). When that happens the wrapper
 * still runs, the cache is present, but every call is a miss — invisible
 * in unit tests, visible as a flat-line hit-rate in prod.
 *
 * <p>Contract per warroom decision D-4.b-5(b): for each migrated call-
 * site that has a natural put→get pairing (read-your-own-writes), invoke
 * twice under the same {@code TenantContext} with identical arguments;
 * assert the second invocation increments the wrapper's
 * {@code fabt.cache.get{result=hit}} counter. If the put-key and get-key
 * diverge, the second call is a miss and the counter delta fails the
 * assertion loudly.
 *
 * <p>Why the 3 eviction-only call sites ({@code AvailabilityService.createSnapshot},
 * {@code ShelterService.evictTenantShelterCaches}) are not parametrized
 * here: they only ever {@code evict}, never {@code put} — so "call twice,
 * assert HIT" has no meaningful shape for them. Their migration contract
 * is covered by {@code FamilyCArchitectureTest} (annotation discipline)
 * and {@code UnboundTenantContextGuardTest} (unbound-context guard).
 */
@DisplayName("Phase C task 4.b — post-migration cache hit-rate sanity")
class Task4bCacheHitRateTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private BedSearchService bedSearchService;

    @Autowired
    private TenantScopedCacheService tenantScopedCache;

    @Autowired
    private MeterRegistry meterRegistry;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        tenantId = authHelper.getTestTenantId();
    }

    /**
     * Each row exercises one migrated put→get site + names the cache the
     * wrapper writes to. The invocation runs twice under bound
     * TenantContext; we assert the hit counter moves by exactly 1 between
     * the two calls.
     */
    static Stream<Arguments> migratedPutGetSites() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 12, 31);
        return Stream.of(
                Arguments.of(
                        "AnalyticsService.getUtilization",
                        CacheNames.ANALYTICS_UTILIZATION,
                        (Task4bInvoker) ctx -> ctx.analytics().getUtilization(ctx.tenantId(), from, to, "daily")),
                Arguments.of(
                        "AnalyticsService.getDemand",
                        CacheNames.ANALYTICS_DEMAND,
                        (Task4bInvoker) ctx -> ctx.analytics().getDemand(ctx.tenantId(), from, to)),
                Arguments.of(
                        "AnalyticsService.getCapacity",
                        CacheNames.ANALYTICS_CAPACITY,
                        (Task4bInvoker) ctx -> ctx.analytics().getCapacity(ctx.tenantId(), from, to)),
                Arguments.of(
                        "AnalyticsService.getDvSummary",
                        CacheNames.ANALYTICS_DV_SUMMARY,
                        (Task4bInvoker) ctx -> ctx.analytics().getDvSummary(ctx.tenantId())),
                Arguments.of(
                        "AnalyticsService.getGeographic",
                        CacheNames.ANALYTICS_GEOGRAPHIC,
                        (Task4bInvoker) ctx -> ctx.analytics().getGeographic(ctx.tenantId())),
                Arguments.of(
                        "AnalyticsService.getHmisHealth",
                        CacheNames.ANALYTICS_HMIS_HEALTH,
                        (Task4bInvoker) ctx -> ctx.analytics().getHmisHealth(ctx.tenantId())),
                Arguments.of(
                        "BedSearchService.doSearch",
                        CacheNames.SHELTER_AVAILABILITY,
                        (Task4bInvoker) ctx -> {
                            // Empty result-set proves the key-stability contract —
                            // wrapper is key-agnostic, so an empty List written + read
                            // back increments the hit counter exactly as a non-empty
                            // one would. Per Riley slice-2 fold-in: seed not required.
                            BedSearchRequest req = new BedSearchRequest(null, null, null, 10);
                            ctx.bedSearch().search(req);
                        }));
    }

    @ParameterizedTest(name = "{0} — second invocation under same tenant hits cache")
    @MethodSource("migratedPutGetSites")
    void secondInvocationHitsCache(String methodName, String cacheName, Task4bInvoker invoker)
            throws Exception {
        // Clear any prior entries so the first call is guaranteed a miss.
        TenantContext.runWithContext(tenantId, true, () ->
                tenantScopedCache.invalidateTenant(tenantId));

        long hitsBefore = currentHitCount(cacheName);
        long missesBefore = currentMissCount(cacheName);

        // First call — expected miss (cache empty). Second call — expected hit
        // if put-key and get-key agree. If the migration silently produced
        // divergent keys, second call is another miss and the assertion fails.
        Task4bContext ctx = new Task4bContext(analyticsService, bedSearchService, tenantId);
        TenantContext.runWithContext(tenantId, true, () -> {
            try {
                invoker.invoke(ctx);
                invoker.invoke(ctx);
            } catch (Exception e) {
                throw new RuntimeException(methodName + " threw", e);
            }
        });

        long hitsDelta = currentHitCount(cacheName) - hitsBefore;
        long missesDelta = currentMissCount(cacheName) - missesBefore;

        // Tight equality — two invocations, one miss (first), one hit (second).
        // The `before` snapshot handles prior counter state; isEqualTo(1) catches
        // (a) key-drift (second call also misses → hitsDelta=0, missesDelta=2),
        // (b) spurious extra hits from concurrent state (hitsDelta>1),
        // (c) early short-circuits that skip the wrapper entirely (both deltas=0).
        // Per Riley post-commit warroom: `.isGreaterThanOrEqualTo` allowed loose
        // passes when unrelated JVM-global counter state bled in; equality is the
        // contract the migration guarantees.
        assertThat(hitsDelta)
                .as("%s: exactly ONE hit between the two invocations. "
                        + "Delta != 1 = migration's put-key diverges from its "
                        + "get-key OR spurious cross-test counter bleed.",
                        methodName)
                .isEqualTo(1);

        assertThat(missesDelta)
                .as("%s: exactly ONE miss (the first invocation; cache was fresh "
                        + "after invalidateTenant). Delta != 1 = second call was "
                        + "also a miss (key drift) or something short-circuited "
                        + "the wrapper path entirely.",
                        methodName)
                .isEqualTo(1);
    }

    private long currentHitCount(String cacheName) {
        return (long) meterRegistry.find("fabt.cache.get")
                .tag("cache", cacheName)
                .tag("tenant", tenantId.toString())
                .tag("result", "hit")
                .counters()
                .stream()
                .mapToDouble(c -> c.count())
                .sum();
    }

    private long currentMissCount(String cacheName) {
        return (long) meterRegistry.find("fabt.cache.get")
                .tag("cache", cacheName)
                .tag("tenant", tenantId.toString())
                .tag("result", "miss")
                .counters()
                .stream()
                .mapToDouble(c -> c.count())
                .sum();
    }

    /**
     * Parametrized-test handle that carries every service a 4.b migrated
     * site may need. Added services incrementally as slices migrate — keep
     * constructor small so new rows don't fight constructor churn.
     */
    record Task4bContext(AnalyticsService analytics, BedSearchService bedSearch, UUID tenantId) {}

    /** Functional interface so each row can describe its call shape. */
    @FunctionalInterface
    interface Task4bInvoker {
        void invoke(Task4bContext ctx) throws Exception;
    }
}
