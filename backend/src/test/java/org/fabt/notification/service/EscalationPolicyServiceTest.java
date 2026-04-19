package org.fabt.notification.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.fabt.notification.domain.EscalationPolicy;
import org.fabt.notification.repository.EscalationPolicyRepository;
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.audit.AuditEventTypes;
import org.fabt.shared.audit.DetachedAuditPersister;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T-22 — Unit tests for {@link EscalationPolicyService}.
 *
 * <p>Pure-mock unit test (no Spring context, no Testcontainer). Exercises:</p>
 * <ul>
 *   <li>Threshold validation: id format, monotonic ordering, severity, roles, recipients</li>
 *   <li>Cache hit / miss / invalidation behaviour</li>
 *   <li>Fallback to platform default when a tenant has no custom policy</li>
 * </ul>
 *
 * <p>Riley Cho's principle: validation must live at the service layer, not the
 * controller, so direct service calls (this test) cannot bypass it.</p>
 */
class EscalationPolicyServiceTest {

    private EscalationPolicyRepository repository;
    private DetachedAuditPersister detachedAuditPersister;
    private SimpleMeterRegistry meterRegistry;
    private EscalationPolicyService service;

    @BeforeEach
    void setUp() {
        repository = mock(EscalationPolicyRepository.class);
        // DetachedAuditPersister is mocked at the service boundary — the
        // EscalationPolicyService only invokes persistDetached on cross-tenant
        // reject; Phase C task 4.1 already exercises the real REQUIRES_NEW
        // persistence path against Testcontainers Postgres in
        // TenantScopedCacheAuditRollbackIntegrationTest.
        detachedAuditPersister = mock(DetachedAuditPersister.class);
        // SimpleMeterRegistry is a real (non-mock) Micrometer registry that
        // records no-op bindings without requiring a Spring context. Used
        // here because the service constructor wires CaffeineCacheMetrics
        // into the registry at construction time (T-53).
        meterRegistry = new SimpleMeterRegistry();
        service = new EscalationPolicyService(repository, detachedAuditPersister, meterRegistry);
    }

    private static EscalationPolicy.Threshold threshold(String id, Duration at, String severity, String... roles) {
        return new EscalationPolicy.Threshold(id, at, severity, List.of(roles));
    }

    private static EscalationPolicy validPolicy() {
        return new EscalationPolicy(
                UUID.randomUUID(), null, "dv-referral", 1,
                List.of(
                        threshold("1h",   Duration.ofHours(1),    "ACTION_REQUIRED", "COORDINATOR"),
                        threshold("2h",   Duration.ofHours(2),    "CRITICAL",        "COC_ADMIN"),
                        threshold("3_5h", Duration.ofMinutes(210), "CRITICAL",       "COORDINATOR", "OUTREACH_WORKER"),
                        threshold("4h",   Duration.ofHours(4),    "ACTION_REQUIRED", "OUTREACH_WORKER")),
                Instant.now(), null);
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("validateThresholds")
    class Validation {

        @Test
        @DisplayName("accepts the platform default policy shape")
        void acceptsValidPolicy() {
            service.validateThresholds(validPolicy().thresholds());
            // no exception
        }

        @Test
        @DisplayName("rejects null threshold list")
        void rejectsNullList() {
            assertThatThrownBy(() -> service.validateThresholds(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one threshold");
        }

        @Test
        @DisplayName("rejects empty threshold list")
        void rejectsEmptyList() {
            assertThatThrownBy(() -> service.validateThresholds(List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one threshold");
        }

        @Test
        @DisplayName("rejects null id")
        void rejectsNullId() {
            assertThatThrownBy(() -> service.validateThresholds(List.of(
                    threshold(null, Duration.ofHours(1), "INFO", "COORDINATOR"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("id");
        }

        @Test
        @DisplayName("rejects id with uppercase letters or punctuation")
        void rejectsBadIdShape() {
            assertThatThrownBy(() -> service.validateThresholds(List.of(
                    threshold("Hour-1", Duration.ofHours(1), "INFO", "COORDINATOR"))))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects duplicate ids in same policy")
        void rejectsDuplicateIds() {
            assertThatThrownBy(() -> service.validateThresholds(List.of(
                    threshold("1h", Duration.ofHours(1), "INFO", "COORDINATOR"),
                    threshold("1h", Duration.ofHours(2), "INFO", "COC_ADMIN"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("duplicated");
        }

        @Test
        @DisplayName("rejects non-monotonic durations")
        void rejectsNonMonotonic() {
            assertThatThrownBy(() -> service.validateThresholds(List.of(
                    threshold("1h", Duration.ofHours(2), "INFO", "COORDINATOR"),
                    threshold("2h", Duration.ofHours(1), "INFO", "COC_ADMIN"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("monotonically increasing");
        }

        @Test
        @DisplayName("rejects identical durations (must be strictly greater)")
        void rejectsEqualDurations() {
            assertThatThrownBy(() -> service.validateThresholds(List.of(
                    threshold("a", Duration.ofHours(1), "INFO", "COORDINATOR"),
                    threshold("b", Duration.ofHours(1), "INFO", "COC_ADMIN"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("strictly greater");
        }

        @Test
        @DisplayName("rejects zero or negative duration")
        void rejectsNonPositive() {
            assertThatThrownBy(() -> service.validateThresholds(List.of(
                    threshold("z", Duration.ZERO, "INFO", "COORDINATOR"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }

        @Test
        @DisplayName("rejects unknown severity")
        void rejectsBadSeverity() {
            assertThatThrownBy(() -> service.validateThresholds(List.of(
                    threshold("1h", Duration.ofHours(1), "URGENT_ISH", "COORDINATOR"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("severity");
        }

        @Test
        @DisplayName("rejects empty recipients")
        void rejectsEmptyRecipients() {
            assertThatThrownBy(() -> service.validateThresholds(List.of(
                    threshold("1h", Duration.ofHours(1), "INFO"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("recipients");
        }

        @Test
        @DisplayName("rejects unknown recipient role")
        void rejectsBadRole() {
            assertThatThrownBy(() -> service.validateThresholds(List.of(
                    threshold("1h", Duration.ofHours(1), "INFO", "JANITOR"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("JANITOR");
        }
    }

    // -------------------------------------------------------------------------
    // Caching + fallback
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("findById caches subsequent lookups")
    void findByIdCaches() {
        EscalationPolicy p = validPolicy();
        when(repository.findById(p.id())).thenReturn(java.util.Optional.of(p));

        assertThat(service.findByIdForBatch(p.id())).contains(p);
        assertThat(service.findByIdForBatch(p.id())).contains(p);
        assertThat(service.findByIdForBatch(p.id())).contains(p);

        verify(repository, times(1)).findById(p.id());
    }

    @Test
    @DisplayName("findById returns empty for null id without hitting repository")
    void findByIdNullSafe() {
        assertThat(service.findByIdForBatch(null)).isEmpty();
        verify(repository, never()).findById(any());
    }

    @Test
    @DisplayName("getCurrentForTenant falls back to platform default when tenant has none")
    void fallbackToPlatformDefault() {
        UUID tenantId = UUID.randomUUID();
        EscalationPolicy platformDefault = new EscalationPolicy(
                UUID.randomUUID(), null, "dv-referral", 1,
                validPolicy().thresholds(), Instant.now(), null);
        when(repository.findCurrentByTenantAndEventType(tenantId, "dv-referral"))
                .thenReturn(java.util.Optional.empty());
        when(repository.findCurrentPlatformDefault("dv-referral"))
                .thenReturn(java.util.Optional.of(platformDefault));

        var resolved = service.getCurrentForTenant(tenantId, "dv-referral");
        assertThat(resolved).contains(platformDefault);

        // Second call must hit cache, not repository.
        assertThat(service.getCurrentForTenant(tenantId, "dv-referral")).contains(platformDefault);
        verify(repository, times(1)).findCurrentByTenantAndEventType(tenantId, "dv-referral");
        verify(repository, times(1)).findCurrentPlatformDefault("dv-referral");
    }

    @Test
    @DisplayName("update inserts a new version, invalidates cache, returns the new policy")
    void updateInsertsAndInvalidates() {
        UUID tenantId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        EscalationPolicy v1 = new EscalationPolicy(
                UUID.randomUUID(), tenantId, "dv-referral", 1,
                validPolicy().thresholds(), Instant.now(), actor);
        EscalationPolicy v2 = new EscalationPolicy(
                UUID.randomUUID(), tenantId, "dv-referral", 2,
                validPolicy().thresholds(), Instant.now(), actor);

        // Prime the tenant cache with v1.
        when(repository.findCurrentByTenantAndEventType(tenantId, "dv-referral"))
                .thenReturn(java.util.Optional.of(v1));
        assertThat(service.getCurrentForTenant(tenantId, "dv-referral")).contains(v1);

        // Update returns v2 — cache must invalidate so the next read sees v2.
        when(repository.insertNewVersion(eq(tenantId), eq("dv-referral"), any(), eq(actor)))
                .thenReturn(v2);
        when(repository.findCurrentByTenantAndEventType(tenantId, "dv-referral"))
                .thenReturn(java.util.Optional.of(v2));

        EscalationPolicy returned = TenantContext.callWithContext(tenantId, false,
                () -> service.update("dv-referral", v2.thresholds(), actor));
        assertThat(returned).isEqualTo(v2);

        assertThat(service.getCurrentForTenant(tenantId, "dv-referral")).contains(v2);
        // findById should be cached for v2 because update() pre-populates it.
        assertThat(service.findByIdForBatch(v2.id())).contains(v2);
        verify(repository, never()).findById(v2.id());
    }

    @Test
    @DisplayName("update rejects invalid policy without inserting")
    void updateRejectsInvalid() {
        UUID actor = UUID.randomUUID();

        // D11: update() validates BEFORE pulling TenantContext, so this
        // invalid-policy test doesn't need a context wrap — validation
        // throws IllegalArgumentException first.
        assertThatThrownBy(() -> service.update("dv-referral",
                List.of(threshold("z", Duration.ZERO, "INFO", "COORDINATOR")), actor))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).insertNewVersion(any(), anyString(), any(), any());
    }

    // -------------------------------------------------------------------------
    // findByTenantAndId — Phase C task 4.4 split (request-path counterpart to
    // findByIdForBatch with on-read tenant verification). Spec:
    // escalation-policy-service-cache-split. Design-c D-C-2 / D-4.4-{1..5}.
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("findByTenantAndId (Phase C task 4.4)")
    class FindByTenantAndId {

        private static final UUID TENANT_A = UUID.fromString("aaaa0000-0000-0000-0000-000000000001");
        private static final UUID TENANT_B = UUID.fromString("bbbb0000-0000-0000-0000-000000000002");

        private EscalationPolicy tenantOwnedPolicy(UUID tenantId) {
            return new EscalationPolicy(
                    UUID.randomUUID(), tenantId, "dv-referral", 1,
                    validPolicy().thresholds(), Instant.now(), null);
        }

        private EscalationPolicy platformDefaultPolicy() {
            return new EscalationPolicy(
                    UUID.randomUUID(), null, "dv-referral", 1,
                    validPolicy().thresholds(), Instant.now(), null);
        }

        @Test
        @DisplayName("cachesSubsequentLookups — same (tenant, policyId) hits cache after first call")
        void cachesSubsequentLookups() {
            EscalationPolicy p = tenantOwnedPolicy(TENANT_A);
            when(repository.findById(p.id())).thenReturn(Optional.of(p));

            assertThat(service.findByTenantAndId(TENANT_A, p.id())).contains(p);
            assertThat(service.findByTenantAndId(TENANT_A, p.id())).contains(p);
            assertThat(service.findByTenantAndId(TENANT_A, p.id())).contains(p);

            verify(repository, times(1)).findById(p.id());
        }

        @Test
        @DisplayName("crossTenantReadReturnsEmpty — policy owned by A, B's read returns empty + emits audit + counter increments")
        void crossTenantReadReturnsEmpty() {
            EscalationPolicy p = tenantOwnedPolicy(TENANT_A);
            when(repository.findById(p.id())).thenReturn(Optional.of(p));

            // Sanity check: TENANT_A can read.
            assertThat(service.findByTenantAndId(TENANT_A, p.id())).contains(p);

            // Cross-tenant reach by TENANT_B.
            assertThat(service.findByTenantAndId(TENANT_B, p.id())).isEmpty();

            // DetachedAuditPersister invoked with CROSS_TENANT_POLICY_READ for TENANT_B.
            ArgumentCaptor<AuditEventRecord> captor = ArgumentCaptor.forClass(AuditEventRecord.class);
            verify(detachedAuditPersister).persistDetached(eq(TENANT_B), captor.capture());
            assertThat(captor.getValue().action()).isEqualTo(AuditEventTypes.CROSS_TENANT_POLICY_READ);

            // cross_tenant_reject counter incremented for TENANT_B.
            double rejectCount = meterRegistry.counter("fabt.cache.get",
                    "cache", "fabt.escalation.policy.by-tenant-and-id",
                    "tenant", TENANT_B.toString(),
                    "result", "cross_tenant_reject").count();
            assertThat(rejectCount).isEqualTo(1.0);
        }

        @Test
        @DisplayName("crossTenantReadDoesNotCache — after reject, legit owner A's read still hits repo (no poisoning)")
        void crossTenantReadDoesNotCache() {
            EscalationPolicy p = tenantOwnedPolicy(TENANT_A);
            when(repository.findById(p.id())).thenReturn(Optional.of(p));

            // B tries and gets rejected.
            assertThat(service.findByTenantAndId(TENANT_B, p.id())).isEmpty();

            // A's subsequent read must go to the repo (not served from any cache
            // line B's read might have touched).
            assertThat(service.findByTenantAndId(TENANT_A, p.id())).contains(p);

            // repo.findById was called twice — once for B's rejected lookup
            // and once for A's legit lookup. Neither was served from cache
            // because B's cross-tenant reject intentionally does NOT cache.
            verify(repository, times(2)).findById(p.id());
        }

        @Test
        @DisplayName("platformDefaultAccessibleFromAnyTenant — policy.tenantId=null, both tenants succeed, separate cache entries")
        void platformDefaultAccessibleFromAnyTenant() {
            EscalationPolicy platformDefault = platformDefaultPolicy();
            when(repository.findById(platformDefault.id())).thenReturn(Optional.of(platformDefault));

            assertThat(service.findByTenantAndId(TENANT_A, platformDefault.id())).contains(platformDefault);
            assertThat(service.findByTenantAndId(TENANT_B, platformDefault.id())).contains(platformDefault);

            // Each tenant has a separate cache entry → two repo calls, one per
            // tenant. D-4.4-1: N× duplication accepted for rare platform-default rows.
            verify(repository, times(2)).findById(platformDefault.id());

            // Subsequent reads hit cache; no additional repo calls.
            assertThat(service.findByTenantAndId(TENANT_A, platformDefault.id())).contains(platformDefault);
            assertThat(service.findByTenantAndId(TENANT_B, platformDefault.id())).contains(platformDefault);
            verify(repository, times(2)).findById(platformDefault.id());
        }

        @Test
        @DisplayName("separateCacheEntriesByTenant — two tenants each own different policies by distinct UUIDs")
        void separateCacheEntriesByTenant() {
            EscalationPolicy pA = tenantOwnedPolicy(TENANT_A);
            EscalationPolicy pB = tenantOwnedPolicy(TENANT_B);
            when(repository.findById(pA.id())).thenReturn(Optional.of(pA));
            when(repository.findById(pB.id())).thenReturn(Optional.of(pB));

            assertThat(service.findByTenantAndId(TENANT_A, pA.id())).contains(pA);
            assertThat(service.findByTenantAndId(TENANT_B, pB.id())).contains(pB);

            // Keys are distinct: (A, pA.id) and (B, pB.id). No cross-collision.
            verify(repository, times(1)).findById(pA.id());
            verify(repository, times(1)).findById(pB.id());
        }

        @Test
        @DisplayName("throwsOnNullTenantId — fail-loud per feedback_never_skip_silently")
        void throwsOnNullTenantId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.findByTenantAndId(null, UUID.randomUUID()))
                    .withMessageContaining("tenantId");
        }

        @Test
        @DisplayName("throwsOnNullPolicyId — fail-loud per feedback_never_skip_silently")
        void throwsOnNullPolicyId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.findByTenantAndId(UUID.randomUUID(), null))
                    .withMessageContaining("policyId");
        }

        @Test
        @DisplayName("policyNotFound — repository empty returns empty without invoking DetachedAuditPersister (not-found is not a cross-tenant event)")
        void policyNotFound() {
            UUID missingId = UUID.randomUUID();
            when(repository.findById(missingId)).thenReturn(Optional.empty());

            assertThat(service.findByTenantAndId(TENANT_A, missingId)).isEmpty();

            // Confirm the empty came from the DB path (not a null-guard short-circuit
            // or a cached empty). Warroom polish-nit.
            verify(repository).findById(missingId);

            // Not-found is NOT a cross-tenant reach — DetachedAuditPersister MUST NOT fire.
            verify(detachedAuditPersister, never()).persistDetached(any(), any());

            // cross_tenant_reject counter MUST NOT increment.
            double rejectCount = meterRegistry.counter("fabt.cache.get",
                    "cache", "fabt.escalation.policy.by-tenant-and-id",
                    "tenant", TENANT_A.toString(),
                    "result", "cross_tenant_reject").count();
            assertThat(rejectCount).isEqualTo(0.0);
        }

        @Test
        @DisplayName("concurrentCrossTenantIsolation — A and B race on same policyId (A owns); A succeeds, B rejected per-reader with audit+counter, no poisoning")
        void concurrentCrossTenantIsolation() throws Exception {
            EscalationPolicy pA = tenantOwnedPolicy(TENANT_A);
            when(repository.findById(pA.id())).thenReturn(Optional.of(pA));

            int perTenant = 8;
            java.util.concurrent.CountDownLatch startGate = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch doneGate = new java.util.concurrent.CountDownLatch(perTenant * 2);
            java.util.concurrent.atomic.AtomicReference<Throwable> caught =
                    new java.util.concurrent.atomic.AtomicReference<>();

            Runnable aReader = () -> {
                try {
                    startGate.await();
                    assertThat(service.findByTenantAndId(TENANT_A, pA.id())).contains(pA);
                } catch (Throwable t) {
                    caught.compareAndSet(null, t);
                } finally {
                    doneGate.countDown();
                }
            };
            Runnable bReader = () -> {
                try {
                    startGate.await();
                    assertThat(service.findByTenantAndId(TENANT_B, pA.id())).isEmpty();
                } catch (Throwable t) {
                    caught.compareAndSet(null, t);
                } finally {
                    doneGate.countDown();
                }
            };

            for (int i = 0; i < perTenant; i++) {
                Thread.ofVirtual().start(aReader);
                Thread.ofVirtual().start(bReader);
            }
            startGate.countDown();
            doneGate.await();

            assertThat(caught.get()).isNull();

            // Every B-reader must produce an audit row — no cache-put on the reject
            // path means each B-call individually calls DetachedAuditPersister.
            // Pins the contract that cross-tenant rejects are NOT deduplicated by
            // a racing cache entry.
            verify(detachedAuditPersister, times(perTenant))
                    .persistDetached(eq(TENANT_B), any(AuditEventRecord.class));

            // Reject counter total equals perTenant (one per B-call).
            double bRejectCount = meterRegistry.counter("fabt.cache.get",
                    "cache", "fabt.escalation.policy.by-tenant-and-id",
                    "tenant", TENANT_B.toString(),
                    "result", "cross_tenant_reject").count();
            assertThat(bRejectCount).isEqualTo((double) perTenant);
        }

        @Test
        @DisplayName("cacheEntrySurvivesPolicyUpdate — policies are immutable-by-id; update() does NOT invalidate by-tenant-and-id cache")
        void cacheEntrySurvivesPolicyUpdate() {
            UUID actor = UUID.randomUUID();
            EscalationPolicy v1 = tenantOwnedPolicy(TENANT_A);
            EscalationPolicy v2 = new EscalationPolicy(
                    UUID.randomUUID(), TENANT_A, "dv-referral", 2,
                    validPolicy().thresholds(), Instant.now(), actor);

            // Prime the by-tenant-and-id cache for v1.
            when(repository.findById(v1.id())).thenReturn(Optional.of(v1));
            assertThat(service.findByTenantAndId(TENANT_A, v1.id())).contains(v1);

            // Publish v2 via update() — inserts a NEW policy with a NEW id.
            when(repository.insertNewVersion(eq(TENANT_A), eq("dv-referral"), any(), eq(actor)))
                    .thenReturn(v2);
            TenantContext.runWithContext(TENANT_A, false,
                    () -> service.update("dv-referral", v2.thresholds(), actor));

            // v1's cache entry must survive — by-id lookups for v1 still resolve
            // without a repo hit. (Policies are immutable once inserted; old
            // referrals that snapshotted v1 still need v1 to remain resolvable.)
            assertThat(service.findByTenantAndId(TENANT_A, v1.id())).contains(v1);
            verify(repository, times(1)).findById(v1.id());
        }
    }

    @Test
    @DisplayName("clearCaches_testOnly also clears policyByTenantAndId (Phase C task 4.4)")
    void clearCachesTestOnly_clearsNewCache() {
        UUID tenantA = UUID.fromString("aaaa0000-0000-0000-0000-000000000001");
        EscalationPolicy p = new EscalationPolicy(
                UUID.randomUUID(), tenantA, "dv-referral", 1,
                validPolicy().thresholds(), Instant.now(), null);
        when(repository.findById(p.id())).thenReturn(Optional.of(p));

        // Populate all three caches.
        service.findByIdForBatch(p.id());                  // policyById
        service.findByTenantAndId(tenantA, p.id());        // policyByTenantAndId
        when(repository.findCurrentByTenantAndEventType(tenantA, "dv-referral"))
                .thenReturn(Optional.of(p));
        service.getCurrentForTenant(tenantA, "dv-referral"); // currentPolicyByTenant

        service.clearCaches_testOnly();

        // Next reads must go back to the repo.
        service.findByIdForBatch(p.id());
        service.findByTenantAndId(tenantA, p.id());
        service.getCurrentForTenant(tenantA, "dv-referral");

        // findByIdForBatch + findByTenantAndId use SEPARATE caches (policyById +
        // policyByTenantAndId), so each pre-clear + post-clear call pair hits
        // the repo independently: 2 before + 2 after = 4 total findById calls.
        verify(repository, times(4)).findById(p.id());
        verify(repository, times(2)).findCurrentByTenantAndEventType(tenantA, "dv-referral");
    }
}
