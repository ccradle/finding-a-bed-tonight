package org.fabt.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.auth.domain.ApiKey;
import org.fabt.auth.repository.ApiKeyRepository;
import org.fabt.shared.audit.AuditEventTypes;
import org.fabt.shared.config.JsonString;
import org.fabt.shared.security.KidRegistryService;
import org.fabt.tenant.domain.IllegalStateTransitionException;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.domain.TenantState;
import org.fabt.tenant.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * End-to-end integration test for Phase F slice F-2: suspend/unsuspend with JWT bump,
 * API-key deactivation, and audit emission. Runs against the full Spring context
 * (flag-on) so the real {@link TenantLifecycleService}, {@code TenantKeyRotationService},
 * {@code ApiKeyService}, and {@code AuditEventService} all participate.
 *
 * <p>Covers the assertions the unit test cannot: that {@code JWT_KEY_GENERATION_BUMPED}
 * and {@code TENANT_SUSPENDED} audit rows both land in the same transaction, that
 * {@code tenant.jwt_key_generation} actually increments, that {@code api_key.active}
 * rows flip to false in the database, and that the idempotency contract
 * (second-POST-suspend → 409 via IllegalStateTransitionException) holds against a
 * real Postgres instance.</p>
 */
@TestPropertySource(properties = "fabt.tenant.lifecycle.enabled=true")
class TenantLifecycleServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired private TenantLifecycleService lifecycleService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private KidRegistryService kidRegistryService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void suspend_incrementsJwtGeneration_flipsState_deactivatesKeys_writesBothAuditRows() {
        Tenant tenant = saveActiveTenantWithKeyMaterial();
        UUID tenantId = tenant.getId();
        int genBefore = queryJwtGen(tenantId);
        UUID key1 = seedActiveApiKey(tenantId);
        UUID key2 = seedActiveApiKey(tenantId);
        UUID actor = UUID.randomUUID();

        Tenant result = lifecycleService.suspend(tenantId, actor, "live-fire-drill");

        // State flipped
        assertThat(result.getState()).isEqualTo(TenantState.SUSPENDED);
        assertThat(tenantRepository.findById(tenantId).orElseThrow().getState())
            .isEqualTo(TenantState.SUSPENDED);

        // JWT generation bumped by exactly 1
        assertThat(queryJwtGen(tenantId)).isEqualTo(genBefore + 1);

        // Both API keys deactivated
        assertThat(apiKeyRepository.findById(key1).orElseThrow().isActive()).isFalse();
        assertThat(apiKeyRepository.findById(key2).orElseThrow().isActive()).isFalse();

        // Both audit rows present in the same tx (JWT_KEY_GENERATION_BUMPED + TENANT_SUSPENDED).
        // audit_events is FORCE-RLS'd (V69); this SELECT as fabt_app with no tenant
        // context would see zero rows regardless of what got written. Bind
        // app.tenant_id to the target tenant so RLS admits its own rows.
        List<String> actions = queryAuditActionsForTenant(tenantId, actor,
            List.of("JWT_KEY_GENERATION_BUMPED", "TENANT_SUSPENDED"));
        assertThat(actions).containsExactly("JWT_KEY_GENERATION_BUMPED", "TENANT_SUSPENDED");
    }

    @Test
    void suspend_alreadySuspendedTenant_throwsIllegalStateTransition_noSideEffects() {
        // Idempotency-as-409 contract: second suspend on SUSPENDED tenant must
        // throw without further JWT bumps or API-key churn.
        Tenant tenant = saveActiveTenantWithKeyMaterial();
        UUID tenantId = tenant.getId();
        lifecycleService.suspend(tenantId, UUID.randomUUID(), "first");
        int genAfterFirst = queryJwtGen(tenantId);

        UUID secondActor = UUID.randomUUID();
        assertThatThrownBy(() ->
                lifecycleService.suspend(tenantId, secondActor, "second"))
            .isInstanceOf(IllegalStateTransitionException.class);

        // JWT gen unchanged — no second bump
        assertThat(queryJwtGen(tenantId)).isEqualTo(genAfterFirst);

        // And no audit trail for the failed attempt (F-2 scope). This pins the
        // current "silent failure" contract explicitly. The forensic gap — an
        // attacker hammering suspend leaves no trail — is tracked for F-3 in
        // project_tenant_lifecycle_attempt_audit_gap.md; that slice will add a
        // TENANT_SUSPEND_ATTEMPT_REJECTED row via REQUIRES_NEW, at which point
        // THIS assertion flips to expect one row and the test doc-comment updates.
        List<String> attemptAudit = queryAuditActionsForTenant(tenantId, secondActor,
            List.of("TENANT_SUSPENDED", "JWT_KEY_GENERATION_BUMPED"));
        assertThat(attemptAudit).isEmpty();
    }

    @Test
    void unsuspend_restoresState_emitsAudit_doesNotReactivateKeys() {
        // Asymmetric with suspend: unsuspend must NOT auto-reactivate API keys.
        // Prove at the DB level, not just the mock level.
        Tenant tenant = saveActiveTenantWithKeyMaterial();
        UUID tenantId = tenant.getId();
        UUID key = seedActiveApiKey(tenantId);

        lifecycleService.suspend(tenantId, UUID.randomUUID(), "drill");
        assertThat(apiKeyRepository.findById(key).orElseThrow().isActive()).isFalse();
        int genAfterSuspend = queryJwtGen(tenantId);

        UUID actor = UUID.randomUUID();
        Tenant result = lifecycleService.unsuspend(tenantId, actor, "cleared");

        assertThat(result.getState()).isEqualTo(TenantState.ACTIVE);

        // API key stays deactivated — operator re-issues post-incident
        assertThat(apiKeyRepository.findById(key).orElseThrow().isActive())
            .as("unsuspend must NOT reactivate API keys (§D11 post-compromise hygiene)")
            .isFalse();

        // JWT gen stays at its post-suspend value — no rotation on unsuspend
        assertThat(queryJwtGen(tenantId))
            .as("unsuspend must NOT re-rotate JWT keys")
            .isEqualTo(genAfterSuspend);

        // TENANT_UNSUSPENDED audit row present (bind context to pass RLS).
        List<String> actions = queryAuditActionsForTenant(tenantId, actor,
            List.of("TENANT_UNSUSPENDED"));
        assertThat(actions).containsExactly("TENANT_UNSUSPENDED");
    }

    // NOTE: a negative-path integration test for mid-transaction rollback (e.g.
    // force bumpJwt to succeed but deactivateAllForTenant to throw, assert no
    // partial side effects) requires a DB-layer poison we can't easily inject
    // without test-only seams in the services. Left to the unit test (which
    // verifies never-save/never-publish when the FSM assertion fails BEFORE any
    // side effects); the @Transactional boundary is exercised by the assertion
    // above that both audit rows land atomically — if a rollback happens after
    // JWT bump but before TENANT_SUSPENDED, the joined tx would drop both rows.

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Tenant newTenant(TenantState state) {
        Tenant t = new Tenant();
        t.setName("Integration Tenant " + state);
        t.setSlug("int-f2-" + state.name().toLowerCase() + "-" + UUID.randomUUID());
        t.setConfig(JsonString.empty());
        t.setState(state);
        Instant now = Instant.now();
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return t;
    }

    /**
     * Creates an ACTIVE tenant and bootstraps its JWT key material, simulating the
     * lazy-bootstrap path that fires on first user login
     * ({@code JwtService:511 → KidRegistryService.findOrCreateActiveKid}). F-4 will
     * move this to an eager bootstrap inside {@code TenantLifecycleService.create};
     * until then, the test path mirrors production's on-first-login bootstrap.
     */
    private Tenant saveActiveTenantWithKeyMaterial() {
        Tenant tenant = tenantRepository.save(newTenant(TenantState.ACTIVE));
        kidRegistryService.findOrCreateActiveKid(tenant.getId());
        return tenant;
    }

    private UUID seedActiveApiKey(UUID tenantId) {
        ApiKey k = new ApiKey();
        k.setTenantId(tenantId);
        k.setKeyHash("test-hash-" + UUID.randomUUID());
        k.setKeySuffix(UUID.randomUUID().toString().substring(0, 4));
        k.setLabel("test");
        k.setRole("COC_ADMIN");
        k.setActive(true);
        k.setCreatedAt(Instant.now());
        return apiKeyRepository.save(k).getId();
    }

    private int queryJwtGen(UUID tenantId) {
        return jdbc.queryForObject(
            "SELECT jwt_key_generation FROM tenant WHERE id = ?",
            Integer.class, tenantId);
    }

    /**
     * Queries {@code audit_events} for a given tenant's actions, binding
     * {@code app.tenant_id} to the RLS-gated session first so FORCE RLS
     * admits the tenant's own rows. Matches how the service writes them
     * (via {@code set_config} inside its own transaction).
     *
     * <p>Uses {@code TransactionTemplate} so the {@code set_config(..., true)}
     * (tx-local) binding and the subsequent query share one JDBC transaction.
     * Without the tx, {@code is_local=true} scopes to a one-statement tx and
     * the next statement sees an empty GUC again.</p>
     */
    private List<String> queryAuditActionsForTenant(UUID tenantId, UUID actor, List<String> actionFilter) {
        org.springframework.transaction.support.TransactionTemplate tx =
            new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantId.toString());
            // Placeholder-safe IN clause via ANY(array)
            return jdbc.queryForList(
                "SELECT action FROM audit_events "
                + "WHERE actor_user_id = ? AND action = ANY(?) "
                + "ORDER BY timestamp",
                String.class, actor, actionFilter.toArray(new String[0]));
        });
    }

}
