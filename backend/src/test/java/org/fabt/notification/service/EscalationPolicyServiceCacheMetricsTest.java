package org.fabt.notification.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.fabt.notification.domain.EscalationPolicy;
import org.fabt.notification.repository.EscalationPolicyRepository;
import org.fabt.shared.audit.DetachedAuditPersister;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T-53 regression guard: the two Caffeine caches in {@link EscalationPolicyService}
 * MUST have {@code .recordStats()} flipped on their builders AND MUST be wired
 * into Micrometer via {@code CaffeineCacheMetrics.monitor(...)} with the
 * fabt-prefixed cache names.
 *
 * <p><b>Why this test exists (Alex Chen review, 2026-04-12):</b> without
 * {@code .recordStats()}, Caffeine's internal counters stay at zero and
 * Micrometer's {@code CaffeineCacheMetrics} binder silently emits all-zero
 * {@code cache.gets}, {@code cache.puts}, and {@code cache.evictions}. The
 * Grafana cache-hit-rate panel would be a flatline at 0% with no visible
 * error — a "silent failure" that bypasses the {@code feedback_never_skip_silently}
 * rule. This test exercises both caches and asserts a non-zero hit counter,
 * so any future "cleanup" that removes {@code .recordStats()} breaks CI.</p>
 *
 * <p>Pure unit test: mocked repository, SimpleMeterRegistry, no Spring context,
 * no Testcontainer.</p>
 *
 * <p><b>API note:</b> {@code CaffeineCacheMetrics} emits {@code cache.gets}
 * as a {@link FunctionCounter} (not a plain {@code Counter}), because the
 * value is computed from Caffeine's {@code CacheStats.hitCount()} /
 * {@code missCount()} rather than incremented imperatively. Use
 * {@code registry.find(...).functionCounter()} — {@code .counter()} returns
 * null for FunctionCounter instances. This was a subtle trap on first write;
 * fix documented here so future readers don't fall into it.</p>
 */
class EscalationPolicyServiceCacheMetricsTest {

    private static final String EVENT_TYPE = "dv-referral";

    /**
     * Exercises the {@code policyById} cache via findById, then asserts the
     * Micrometer hit counter tagged {@code cache="fabt.escalation.policy.by-id",result="hit"}
     * is non-zero.
     */
    @Test
    @DisplayName("T-53: policyById cache records hit on second lookup")
    void policyById_recordsHitCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EscalationPolicyRepository repository = mock(EscalationPolicyRepository.class);
        EscalationPolicyService service = new EscalationPolicyService(repository, mock(DetachedAuditPersister.class), registry);

        UUID policyId = UUID.randomUUID();
        EscalationPolicy policy = samplePolicy(policyId);
        when(repository.findById(policyId)).thenReturn(Optional.of(policy));

        // First call → cache miss → DB load
        Optional<EscalationPolicy> first = service.findByIdForBatch(policyId);
        assertThat(first).isPresent();

        // Second call → cache hit (does NOT hit the repository)
        Optional<EscalationPolicy> second = service.findByIdForBatch(policyId);
        assertThat(second).isPresent();

        // Assert Micrometer hit counter is non-zero. The full metric name is
        // `cache.gets` with tags `cache=<name>, result=hit|miss`. Note:
        // CaffeineCacheMetrics emits these as FunctionCounter, not Counter —
        // see class Javadoc.
        FunctionCounter hitCounter = registry.find("cache.gets")
                .tag("cache", "fabt.escalation.policy.by-id")
                .tag("result", "hit")
                .functionCounter();

        assertThat(hitCounter)
                .as("cache.gets{cache=fabt.escalation.policy.by-id,result=hit} must exist — "
                        + ".recordStats() may have been removed from the Caffeine builder")
                .isNotNull();
        assertThat(hitCounter.count())
                .as("At least one cache hit must have been recorded after the second findById call")
                .isGreaterThanOrEqualTo(1.0);
    }

    /**
     * Exercises the {@code currentPolicyByTenant} cache via getCurrentForTenant,
     * then asserts the Micrometer hit counter for the second cache is non-zero.
     */
    @Test
    @DisplayName("T-53: currentPolicyByTenant cache records hit on second lookup")
    void currentPolicyByTenant_recordsHitCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EscalationPolicyRepository repository = mock(EscalationPolicyRepository.class);
        EscalationPolicyService service = new EscalationPolicyService(repository, mock(DetachedAuditPersister.class), registry);

        UUID tenantId = UUID.randomUUID();
        EscalationPolicy policy = samplePolicy(UUID.randomUUID());
        when(repository.findCurrentByTenantAndEventType(eq(tenantId), eq(EVENT_TYPE)))
                .thenReturn(Optional.of(policy));

        // First call → cache miss → DB load
        Optional<EscalationPolicy> first = service.getCurrentForTenant(tenantId, EVENT_TYPE);
        assertThat(first).isPresent();

        // Second call → cache hit
        Optional<EscalationPolicy> second = service.getCurrentForTenant(tenantId, EVENT_TYPE);
        assertThat(second).isPresent();

        FunctionCounter hitCounter = registry.find("cache.gets")
                .tag("cache", "fabt.escalation.policy.current-by-tenant")
                .tag("result", "hit")
                .functionCounter();

        assertThat(hitCounter)
                .as("cache.gets{cache=fabt.escalation.policy.current-by-tenant,result=hit} must exist")
                .isNotNull();
        assertThat(hitCounter.count())
                .as("At least one cache hit must have been recorded after the second "
                        + "getCurrentForTenant call")
                .isGreaterThanOrEqualTo(1.0);
    }

    /**
     * Sanity: the miss counter also has to exist and be non-zero on a cold
     * cache. If this fails, Caffeine is not recording stats at all, and the
     * hit-counter assertion would pass only coincidentally.
     */
    @Test
    @DisplayName("T-53: cache miss counter is recorded on cold load")
    void coldCache_recordsMissCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EscalationPolicyRepository repository = mock(EscalationPolicyRepository.class);
        EscalationPolicyService service = new EscalationPolicyService(repository, mock(DetachedAuditPersister.class), registry);

        UUID policyId = UUID.randomUUID();
        when(repository.findById(any(UUID.class)))
                .thenReturn(Optional.of(samplePolicy(policyId)));

        // Cold lookup — forces a miss
        service.findByIdForBatch(policyId);

        FunctionCounter missCounter = registry.find("cache.gets")
                .tag("cache", "fabt.escalation.policy.by-id")
                .tag("result", "miss")
                .functionCounter();

        assertThat(missCounter)
                .as("cache.gets{cache=fabt.escalation.policy.by-id,result=miss} must exist")
                .isNotNull();
        assertThat(missCounter.count())
                .as("Cold lookup must have recorded a cache miss")
                .isGreaterThanOrEqualTo(1.0);
    }

    private static EscalationPolicy samplePolicy(UUID id) {
        return new EscalationPolicy(
                id,
                null, // tenantId = platform default
                EVENT_TYPE,
                1,
                List.of(new EscalationPolicy.Threshold(
                        "1h", Duration.ofHours(1), "ACTION_REQUIRED", List.of("COORDINATOR"))),
                Instant.now(), // createdAt
                null); // createdBy = null on platform default
    }
}
