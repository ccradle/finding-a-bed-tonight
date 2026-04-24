package org.fabt.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.auth.domain.ApiKey;
import org.fabt.auth.repository.ApiKeyRepository;
import org.fabt.shared.config.JsonString;
import org.fabt.shared.security.KidRegistryService;
import org.fabt.shared.web.TenantContext;
import org.fabt.subscription.domain.Subscription;
import org.fabt.subscription.repository.SubscriptionRepository;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.domain.TenantState;
import org.fabt.tenant.domain.TenantStateGuardException;
import org.fabt.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * J7-minimal breach simulation — Marcus's security bar for v0.51.0 per the F-2 warroom.
 * Proves the F-3 read-path enforcement end-to-end: a tenant whose state is flipped to
 * SUSPENDED via {@code TenantLifecycleService.suspend} becomes invisible to
 * request-bound reads, both via the caller-tenant interceptor gate (F-3.3) and the
 * target-tenant SQL JOIN gate (F-3.2). No 403, no 500, no data leak — just 404 (or an
 * empty {@code Optional} at the service layer that maps to 404 at the controller).
 *
 * <p>Two attack vectors covered:
 * <ol>
 *   <li><b>Suspended caller</b> ({@link #suspendedCaller_cannotRead_viaInterceptor}) —
 *       Tenant A's own token gets {@link TenantStateGuardException#kind()}
 *       {@code NON_ACTIVE} from {@link TenantStateGuard#requireActive}. The interceptor
 *       short-circuits every MVC request in this state.</li>
 *   <li><b>Cross-tenant read of suspended resource</b>
 *       ({@link #crossTenantRead_suspendedTarget_returnsEmpty}) — Tenant B's active
 *       context attempts a direct-UUID read of Tenant A's ApiKey after A is suspended;
 *       the F-3.2 SQL JOIN returns empty. At the controller layer this maps to 404;
 *       at this level we assert the Optional directly.</li>
 * </ol>
 */
@TestPropertySource(properties = "fabt.tenant.lifecycle.enabled=true")
class TenantStateBreachSimIntegrationTest extends BaseIntegrationTest {

    @Autowired private TenantLifecycleService lifecycleService;
    @Autowired private TenantStateGuard tenantStateGuard;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private KidRegistryService kidRegistryService;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void seedTenants() {
        tenantA = saveActiveTenantWithKeyMaterial("breach-sim-A");
        tenantB = saveActiveTenantWithKeyMaterial("breach-sim-B");
        // Defense: reset the guard cache so a prior test's state doesn't bleed in.
        tenantStateGuard.invalidate(tenantA);
        tenantStateGuard.invalidate(tenantB);
    }

    @Test
    void suspendedCaller_cannotRead_viaInterceptor() {
        // Suspend Tenant A. Then simulate the JWT-authenticated request flow: the
        // interceptor runs with TenantContext bound to A. requireActive should
        // throw NON_ACTIVE with the correct observed state, which the
        // TenantLifecycleExceptionAdvice maps to 404 at the HTTP layer.
        lifecycleService.suspend(tenantA, UUID.randomUUID(), "breach-sim");

        // Invalidate explicitly — the lifecycle service does this via after-commit
        // hook, but the @TestPropertySource context may share the guard singleton
        // across tests; belt-and-suspenders.
        tenantStateGuard.invalidate(tenantA);

        assertThatThrownBy(() -> TenantContext.callWithContext(tenantA, false,
            () -> {
                tenantStateGuard.requireActive(TenantContext.getTenantId());
                return null;
            }))
            .isInstanceOf(TenantStateGuardException.class)
            .satisfies(ex -> {
                TenantStateGuardException typed = (TenantStateGuardException) ex;
                assertThat(typed.kind()).isEqualTo(TenantStateGuardException.Kind.NON_ACTIVE);
                assertThat(typed.tenantId()).isEqualTo(tenantA);
                assertThat(typed.observedState()).isEqualTo(TenantState.SUSPENDED);
            });
    }

    @Test
    void crossTenantRead_suspendedTarget_returnsEmpty() {
        // Tenant A has an ApiKey AND a Subscription. Tenant B — fully ACTIVE — tries
        // to read A's resources by direct UUID via the Tier-1 SQL-JOIN methods.
        // findByIdAndActiveTenantId on a SUSPENDED tenant returns empty (SQL JOIN
        // filters out the row) AND findByIdAndActiveTenantId with Tenant B's
        // tenantId would ALSO fail the tenant_id = :tenantId filter. Both paths =
        // empty. The point: no row leaks regardless of which combination the
        // attacker supplies.
        UUID apiKeyId = seedApiKey(tenantA);
        UUID subscriptionId = seedSubscription(tenantA);

        // Sanity: before suspend, Tenant A's own context CAN see its own data.
        Optional<ApiKey> preSuspend = apiKeyRepository.findByIdAndActiveTenantId(apiKeyId, tenantA);
        assertThat(preSuspend)
            .as("pre-suspend sanity: ACTIVE tenant sees its own key")
            .isPresent();

        lifecycleService.suspend(tenantA, UUID.randomUUID(), "breach-sim");

        // Now: Tenant A's own query for its own key returns empty (A is SUSPENDED).
        // D3 semantics: no existence leak — indistinguishable from "key never existed".
        Optional<ApiKey> selfReadAfterSuspend =
            apiKeyRepository.findByIdAndActiveTenantId(apiKeyId, tenantA);
        assertThat(selfReadAfterSuspend)
            .as("SUSPENDED tenant's own read returns empty")
            .isEmpty();

        // Cross-tenant: Tenant B tries to fetch Tenant A's key/sub via B's own
        // tenantId. Returns empty on tenant_id filter (the row is A's, B is asking
        // for B's). This is the pre-F-3 isolation behavior, still correct.
        Optional<ApiKey> crossTenantAttemptKey =
            apiKeyRepository.findByIdAndActiveTenantId(apiKeyId, tenantB);
        assertThat(crossTenantAttemptKey)
            .as("cross-tenant attempt (asking B's tenantId) returns empty")
            .isEmpty();

        // Cross-tenant: Tenant B tries to fetch A's subscription via A's tenantId
        // (attacker guessed both). Returns empty — A is SUSPENDED, the JOIN filter
        // blocks.
        Optional<Subscription> crossTenantGuessedBoth =
            subscriptionRepository.findByIdAndActiveTenantId(subscriptionId, tenantA);
        assertThat(crossTenantGuessedBoth)
            .as("attacker with both UUIDs guessed still gets empty — SUSPENDED A's SQL JOIN filter")
            .isEmpty();
    }

    @Test
    void unsuspendRestoresReads() {
        // Round-trip: suspend → blocked, unsuspend → restored. Proves the cache
        // invalidation after-commit hook lands in ms, not 10s (the TTL ceiling).
        UUID apiKeyId = seedApiKey(tenantA);

        lifecycleService.suspend(tenantA, UUID.randomUUID(), "drill");
        assertThat(apiKeyRepository.findByIdAndActiveTenantId(apiKeyId, tenantA))
            .as("suspended → empty")
            .isEmpty();

        lifecycleService.unsuspend(tenantA, UUID.randomUUID(), "cleared");
        assertThat(apiKeyRepository.findByIdAndActiveTenantId(apiKeyId, tenantA))
            .as("unsuspended → visible again, without waiting for 10s TTL")
            .isPresent();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private UUID saveActiveTenantWithKeyMaterial(String slugPrefix) {
        Tenant t = new Tenant();
        t.setName("Breach Sim " + slugPrefix);
        t.setSlug(slugPrefix + "-" + UUID.randomUUID());
        t.setConfig(JsonString.empty());
        t.setState(TenantState.ACTIVE);
        Instant now = Instant.now();
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        Tenant saved = tenantRepository.save(t);
        kidRegistryService.findOrCreateActiveKid(saved.getId());
        return saved.getId();
    }

    private UUID seedApiKey(UUID tenantId) {
        ApiKey k = new ApiKey();
        k.setTenantId(tenantId);
        k.setKeyHash("breach-sim-" + UUID.randomUUID());
        k.setKeySuffix(UUID.randomUUID().toString().substring(0, 4));
        k.setLabel("breach-sim");
        k.setRole("COC_ADMIN");
        k.setActive(true);
        k.setCreatedAt(Instant.now());
        return apiKeyRepository.save(k).getId();
    }

    private UUID seedSubscription(UUID tenantId) {
        Subscription s = new Subscription();
        s.setTenantId(tenantId);
        s.setEventType("shelter.availability_changed");
        s.setFilter(JsonString.empty());
        s.setCallbackUrl("https://example.invalid/webhook");
        s.setCallbackSecretHash("seed-hash-" + UUID.randomUUID());
        s.setStatus("ACTIVE");
        s.setCreatedAt(Instant.now());
        return subscriptionRepository.save(s).getId();
    }
}
