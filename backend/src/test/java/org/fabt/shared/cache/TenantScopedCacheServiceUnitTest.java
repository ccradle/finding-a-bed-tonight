package org.fabt.shared.cache;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.audit.AuditEventTypes;
import org.fabt.shared.audit.DetachedAuditPersister;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantScopedCacheService}.
 *
 * <p>Covers Phase C tasks 4.8 (missing context), 4.9 (prefix isolation),
 * 4.9b (invalidateTenant isolation), 4.9c (cross-tenant poisoning),
 * 4.9d (malformed entry), 4.9e (idempotency), 4.9f (post-restart empty-
 * registry safety), 4.9h (null-value rejection). Task 4.9g is an
 * integration test covered separately.
 */
@DisplayName("TenantScopedCacheService")
class TenantScopedCacheServiceUnitTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private CacheService delegate;
    private ApplicationEventPublisher eventPublisher;
    private DetachedAuditPersister detachedAuditPersister;
    private MeterRegistry meterRegistry;
    private TenantScopedCacheService wrapper;

    @BeforeEach
    void setUp() {
        delegate = new CaffeineCacheService(60L);
        eventPublisher = mock(ApplicationEventPublisher.class);
        // DetachedAuditPersister is mocked at the TenantScopedCacheService boundary
        // so the wrapper tests don't need real DB or tx manager wiring. The 4.9g IT
        // exercises the real persister + REQUIRES_NEW rollback behaviour.
        detachedAuditPersister = mock(DetachedAuditPersister.class);
        meterRegistry = new SimpleMeterRegistry();

        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(meterRegistry);

        wrapper = new TenantScopedCacheService(delegate, eventPublisher,
                detachedAuditPersister, provider);
        wrapper.seedRegisteredCacheNames();
    }

    // ---- Task 4.9f — post-restart empty-registry safety (also: eager seed) ----

    @Test
    @DisplayName("4.9f — eager seed populates registry from CacheNames, non-empty")
    void eagerSeedPopulatesRegistry() {
        assertThat(wrapper.registeredCacheNamesSnapshot())
                .isNotEmpty()
                .contains(CacheNames.SHELTER_PROFILE)
                .contains(CacheNames.SHELTER_LIST)
                .contains(CacheNames.SHELTER_AVAILABILITY)
                .contains(CacheNames.ANALYTICS_UTILIZATION);
    }

    @Test
    @DisplayName("4.9f — invalidateTenant on never-written tenant iterates all seeded caches + returns zero per cache")
    void invalidateTenantOnNeverWrittenTenantIteratesAllCaches() {
        // Fresh wrapper, no puts yet. invalidateTenant must NOT NPE and must iterate.
        wrapper.invalidateTenant(TENANT_A);

        ArgumentCaptor<AuditEventRecord> captor = ArgumentCaptor.forClass(AuditEventRecord.class);
        verify(eventPublisher).publishEvent(captor.capture());
        AuditEventRecord audit = captor.getValue();

        assertThat(audit.action()).isEqualTo(AuditEventTypes.TENANT_CACHE_INVALIDATED);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> details = (java.util.Map<String, Object>) audit.details();
        assertThat(details.get("tenantId")).isEqualTo(TENANT_A.toString());
        assertThat(details.get("totalEvicted")).isEqualTo(0L);
        // per-cache counts map includes every seeded cache name with 0 evictions
        @SuppressWarnings("unchecked")
        java.util.Map<String, Long> perCache = (java.util.Map<String, Long>) details.get("perCacheEvictionCounts");
        assertThat(perCache).isNotEmpty();
        assertThat(perCache.values()).allMatch(count -> count == 0L);
    }

    // ---- Task 4.8 — missing TenantContext fails fast on get + put ----

    @Nested
    @DisplayName("4.8 — missing TenantContext")
    class MissingContext {

        @Test
        @DisplayName("get throws TENANT_CONTEXT_UNBOUND with no UUID in message")
        void getThrowsWithoutContext() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> wrapper.get(CacheNames.SHELTER_PROFILE, "k", String.class))
                    .withMessage("TENANT_CONTEXT_UNBOUND");
        }

        @Test
        @DisplayName("put throws TENANT_CONTEXT_UNBOUND with no UUID in message")
        void putThrowsWithoutContext() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> wrapper.put(CacheNames.SHELTER_PROFILE, "k", "v", Duration.ofSeconds(10)))
                    .withMessage("TENANT_CONTEXT_UNBOUND");
        }

        @Test
        @DisplayName("evict throws TENANT_CONTEXT_UNBOUND")
        void evictThrowsWithoutContext() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> wrapper.evict(CacheNames.SHELTER_PROFILE, "k"))
                    .withMessage("TENANT_CONTEXT_UNBOUND");
        }
    }

    // ---- Task 4.9 — key prefix + hit/miss semantics ----

    @Test
    @DisplayName("4.9 — tenant A put + tenant A get returns value; tenant B get returns miss")
    void prefixIsolatesTenants() {
        TenantContext.runWithContext(TENANT_A, false, () ->
                wrapper.put(CacheNames.SHELTER_PROFILE, "s1", "valueA", Duration.ofSeconds(30))
        );
        Optional<String> aRead = TenantContext.callWithContext(TENANT_A, false, () ->
                wrapper.get(CacheNames.SHELTER_PROFILE, "s1", String.class)
        );
        Optional<String> bRead = TenantContext.callWithContext(TENANT_B, false, () ->
                wrapper.get(CacheNames.SHELTER_PROFILE, "s1", String.class)
        );

        assertThat(aRead).contains("valueA");
        assertThat(bRead).isEmpty();
    }

    @Test
    @DisplayName("4.9 — pipe separator produces <tenantId>|<key> effective key")
    void usesPipeSeparator() {
        TenantContext.runWithContext(TENANT_A, false, () ->
                wrapper.put(CacheNames.SHELTER_PROFILE, "s1", "v", Duration.ofSeconds(30))
        );
        // Verify underlying key is <tenantId>|s1
        Optional<TenantScopedValue> raw = delegate.get(CacheNames.SHELTER_PROFILE,
                TENANT_A + "|s1", TenantScopedValue.class);
        assertThat(raw).isPresent();
        assertThat(raw.get().tenantId()).isEqualTo(TENANT_A);
        assertThat(raw.get().value()).isEqualTo("v");
    }

    @Test
    @DisplayName("4.9 — hit counter increments; miss counter increments")
    void countersIncrement() {
        TenantContext.runWithContext(TENANT_A, false, () -> {
            wrapper.put(CacheNames.SHELTER_PROFILE, "s1", "v", Duration.ofSeconds(30));
            wrapper.get(CacheNames.SHELTER_PROFILE, "s1", String.class);
            wrapper.get(CacheNames.SHELTER_PROFILE, "does-not-exist", String.class);
        });

        double hits = meterRegistry.counter("fabt.cache.get",
                "cache", CacheNames.SHELTER_PROFILE,
                "tenant", TENANT_A.toString(),
                "result", "hit").count();
        double misses = meterRegistry.counter("fabt.cache.get",
                "cache", CacheNames.SHELTER_PROFILE,
                "tenant", TENANT_A.toString(),
                "result", "miss").count();
        double puts = meterRegistry.counter("fabt.cache.put",
                "cache", CacheNames.SHELTER_PROFILE,
                "tenant", TENANT_A.toString()).count();

        assertThat(hits).isEqualTo(1.0);
        assertThat(misses).isEqualTo(1.0);
        assertThat(puts).isEqualTo(1.0);
    }

    // ---- Task 4.9 extension — concurrent puts from two tenants ----

    @Test
    @DisplayName("4.9 — concurrent puts from two tenants persist independently (no race)")
    void concurrentPutsSurvive() throws InterruptedException {
        int iterations = 50;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);
        AtomicReference<Throwable> caught = new AtomicReference<>();

        Runnable writerA = () -> {
            try {
                startGate.await();
                TenantContext.runWithContext(TENANT_A, false, () -> {
                    for (int i = 0; i < iterations; i++) {
                        wrapper.put(CacheNames.SHELTER_PROFILE, "k" + i, "A-" + i,
                                Duration.ofSeconds(30));
                    }
                });
            } catch (Throwable t) {
                caught.compareAndSet(null, t);
            } finally {
                doneGate.countDown();
            }
        };
        Runnable writerB = () -> {
            try {
                startGate.await();
                TenantContext.runWithContext(TENANT_B, false, () -> {
                    for (int i = 0; i < iterations; i++) {
                        wrapper.put(CacheNames.SHELTER_PROFILE, "k" + i, "B-" + i,
                                Duration.ofSeconds(30));
                    }
                });
            } catch (Throwable t) {
                caught.compareAndSet(null, t);
            } finally {
                doneGate.countDown();
            }
        };

        Thread t1 = Thread.ofVirtual().start(writerA);
        Thread t2 = Thread.ofVirtual().start(writerB);
        startGate.countDown();
        doneGate.await();

        assertThat(caught.get()).isNull();

        // Both tenants' envelopes survive under their correct prefix — zero cross-contamination
        for (int i = 0; i < iterations; i++) {
            final int idx = i;
            String aVal = TenantContext.callWithContext(TENANT_A, false, () ->
                    wrapper.get(CacheNames.SHELTER_PROFILE, "k" + idx, String.class).orElse(null)
            );
            String bVal = TenantContext.callWithContext(TENANT_B, false, () ->
                    wrapper.get(CacheNames.SHELTER_PROFILE, "k" + idx, String.class).orElse(null)
            );
            assertThat(aVal).as("tenant A read for k%d", idx).isEqualTo("A-" + idx);
            assertThat(bVal).as("tenant B read for k%d", idx).isEqualTo("B-" + idx);
        }
    }

    // ---- Task 4.9b — invalidateTenant scope ----

    @Test
    @DisplayName("4.9b — invalidateTenant(A) evicts A across all caches; B entries survive")
    void invalidateTenantScope() {
        TenantContext.runWithContext(TENANT_A, false, () -> {
            wrapper.put(CacheNames.SHELTER_PROFILE, "s1", "va1", Duration.ofSeconds(30));
            wrapper.put(CacheNames.SHELTER_LIST, "list", "la", Duration.ofSeconds(30));
        });
        TenantContext.runWithContext(TENANT_B, false, () -> {
            wrapper.put(CacheNames.SHELTER_PROFILE, "s1", "vb1", Duration.ofSeconds(30));
            wrapper.put(CacheNames.SHELTER_LIST, "list", "lb", Duration.ofSeconds(30));
        });

        wrapper.invalidateTenant(TENANT_A);

        Optional<String> aMiss = TenantContext.callWithContext(TENANT_A, false, () ->
                wrapper.get(CacheNames.SHELTER_PROFILE, "s1", String.class)
        );
        Optional<String> bHit = TenantContext.callWithContext(TENANT_B, false, () ->
                wrapper.get(CacheNames.SHELTER_PROFILE, "s1", String.class)
        );

        assertThat(aMiss).isEmpty();
        assertThat(bHit).contains("vb1");
    }

    @Test
    @DisplayName("4.9b — invalidateTenant emits audit row with per-cache eviction counts")
    void invalidateTenantEmitsAudit() {
        TenantContext.runWithContext(TENANT_A, false, () -> {
            wrapper.put(CacheNames.SHELTER_PROFILE, "s1", "v", Duration.ofSeconds(30));
            wrapper.put(CacheNames.SHELTER_PROFILE, "s2", "v", Duration.ofSeconds(30));
            wrapper.put(CacheNames.SHELTER_LIST, "list", "v", Duration.ofSeconds(30));
        });
        Mockito.clearInvocations(eventPublisher);

        wrapper.invalidateTenant(TENANT_A);

        ArgumentCaptor<AuditEventRecord> captor = ArgumentCaptor.forClass(AuditEventRecord.class);
        verify(eventPublisher).publishEvent(captor.capture());
        AuditEventRecord audit = captor.getValue();

        assertThat(audit.action()).isEqualTo(AuditEventTypes.TENANT_CACHE_INVALIDATED);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> details = (java.util.Map<String, Object>) audit.details();
        assertThat(details.get("totalEvicted")).isEqualTo(3L);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Long> perCache = (java.util.Map<String, Long>) details.get("perCacheEvictionCounts");
        assertThat(perCache.get(CacheNames.SHELTER_PROFILE)).isEqualTo(2L);
        assertThat(perCache.get(CacheNames.SHELTER_LIST)).isEqualTo(1L);
    }

    // ---- Task 4.9c — cross-tenant cache-poisoning regression (stamp/verify defence) ----

    @Test
    @DisplayName("4.9c — cross-tenant read throws CROSS_TENANT_CACHE_READ + emits DetachedAuditPersister call")
    void crossTenantReadThrows() {
        // Write an envelope stamped with TENANT_A directly into the raw cache
        // under TENANT_B's prefixed key (simulates a wrapper-bypass write with
        // wrong context — the #1 2025-2026 cache-leak pattern).
        String tenantBKey = TENANT_B + "|s1";
        delegate.put(CacheNames.SHELTER_PROFILE, tenantBKey,
                new TenantScopedValue<>(TENANT_A, "poisoned-value"),
                Duration.ofSeconds(30));

        assertThatIllegalStateException()
                .isThrownBy(() ->
                        TenantContext.callWithContext(TENANT_B, false, () ->
                                wrapper.get(CacheNames.SHELTER_PROFILE, "s1", String.class)
                        )
                )
                .withMessage("CROSS_TENANT_CACHE_READ");

        // cross_tenant_reject counter increments
        double rejected = meterRegistry.counter("fabt.cache.get",
                "cache", CacheNames.SHELTER_PROFILE,
                "tenant", TENANT_B.toString(),
                "result", "cross_tenant_reject").count();
        assertThat(rejected).isEqualTo(1.0);

        // DetachedAuditPersister called with CROSS_TENANT_CACHE_READ
        ArgumentCaptor<AuditEventRecord> captor = ArgumentCaptor.forClass(AuditEventRecord.class);
        verify(detachedAuditPersister).persistDetached(eq(TENANT_B), captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(AuditEventTypes.CROSS_TENANT_CACHE_READ);

        // No event-bus audit for this security-evidence event
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("4.9c — exception message carries ONLY action tag, no UUIDs (no information disclosure)")
    void crossTenantExceptionMessageSanitised() {
        String tenantBKey = TENANT_B + "|s1";
        delegate.put(CacheNames.SHELTER_PROFILE, tenantBKey,
                new TenantScopedValue<>(TENANT_A, "v"), Duration.ofSeconds(30));

        try {
            TenantContext.runWithContext(TENANT_B, false, () ->
                    wrapper.get(CacheNames.SHELTER_PROFILE, "s1", String.class)
            );
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).isEqualTo("CROSS_TENANT_CACHE_READ");
            assertThat(e.getMessage()).doesNotContain(TENANT_A.toString());
            assertThat(e.getMessage()).doesNotContain(TENANT_B.toString());
            return;
        }
        throw new AssertionError("Expected IllegalStateException was not thrown");
    }

    // ---- Task 4.9d — malformed entry defence ----

    @Test
    @DisplayName("4.9d — raw non-envelope payload in cache triggers MALFORMED_CACHE_ENTRY")
    void malformedEntryThrows() {
        // Caller bypasses the wrapper and writes a raw String payload directly
        // (simulates a pre-migration call site not yet converted by task 4.b).
        String tenantAKey = TENANT_A + "|s1";
        delegate.put(CacheNames.SHELTER_PROFILE, tenantAKey,
                "raw-string-not-envelope", Duration.ofSeconds(30));

        assertThatIllegalStateException()
                .isThrownBy(() ->
                        TenantContext.callWithContext(TENANT_A, false, () ->
                                wrapper.get(CacheNames.SHELTER_PROFILE, "s1", String.class)
                        )
                )
                .withMessage("MALFORMED_CACHE_ENTRY");

        double malformed = meterRegistry.counter("fabt.cache.get",
                "cache", CacheNames.SHELTER_PROFILE,
                "tenant", TENANT_A.toString(),
                "result", "malformed_entry").count();
        assertThat(malformed).isEqualTo(1.0);
    }

    @Test
    @DisplayName("4.9d — envelope's inner value of wrong type triggers MALFORMED_CACHE_ENTRY")
    void envelopeWrongInnerTypeThrows() {
        // Envelope is valid but its inner value is an Integer; caller asks for String.
        String tenantAKey = TENANT_A + "|s1";
        delegate.put(CacheNames.SHELTER_PROFILE, tenantAKey,
                new TenantScopedValue<>(TENANT_A, Integer.valueOf(42)),
                Duration.ofSeconds(30));

        assertThatIllegalStateException()
                .isThrownBy(() ->
                        TenantContext.callWithContext(TENANT_A, false, () ->
                                wrapper.get(CacheNames.SHELTER_PROFILE, "s1", String.class)
                        )
                )
                .withMessage("MALFORMED_CACHE_ENTRY");
    }

    // ---- Task 4.9e — invalidateTenant idempotency ----

    @Test
    @DisplayName("4.9e — invalidateTenant is idempotent: second call returns 0")
    void invalidateTenantIdempotent() {
        TenantContext.runWithContext(TENANT_A, false, () -> {
            wrapper.put(CacheNames.SHELTER_PROFILE, "s1", "v", Duration.ofSeconds(30));
        });

        wrapper.invalidateTenant(TENANT_A);
        wrapper.invalidateTenant(TENANT_A);

        // Two audit rows emitted (one per call)
        ArgumentCaptor<AuditEventRecord> captor = ArgumentCaptor.forClass(AuditEventRecord.class);
        verify(eventPublisher, times(2)).publishEvent(captor.capture());

        List<AuditEventRecord> rows = captor.getAllValues();
        @SuppressWarnings("unchecked")
        long firstTotal = ((Number) ((java.util.Map<String, Object>) rows.get(0).details())
                .get("totalEvicted")).longValue();
        @SuppressWarnings("unchecked")
        long secondTotal = ((Number) ((java.util.Map<String, Object>) rows.get(1).details())
                .get("totalEvicted")).longValue();

        assertThat(firstTotal).isEqualTo(1L);
        assertThat(secondTotal).isEqualTo(0L);
    }

    // ---- Task 4.9h — defensive null-value rejection ----

    @Test
    @DisplayName("4.9h — put(null) throws IllegalArgumentException immediately; no cache write")
    void nullValueRejected() {
        TenantContext.runWithContext(TENANT_A, false, () -> {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> wrapper.put(CacheNames.SHELTER_PROFILE, "s1", null,
                            Duration.ofSeconds(30)));
        });

        // No raw entry written
        Optional<TenantScopedValue> raw = delegate.get(CacheNames.SHELTER_PROFILE,
                TENANT_A + "|s1", TenantScopedValue.class);
        assertThat(raw).isEmpty();
    }
}
