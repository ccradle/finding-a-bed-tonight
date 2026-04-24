package org.fabt.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.shared.audit.AuditEventType;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.domain.TenantState;
import org.fabt.tenant.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * End-to-end Testcontainers test for F-4: {@code TenantLifecycleService.create}
 * bootstraps a new tenant atomically with key material, an audit_chain_head row,
 * and a {@code TENANT_CREATED} audit event — all visible in the DB after the
 * single {@code @Transactional} commits.
 */
@TestPropertySource(properties = "fabt.tenant.lifecycle.enabled=true")
class TenantLifecycleCreateIntegrationTest extends BaseIntegrationTest {

    @Autowired private TenantLifecycleService lifecycleService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void create_landsTenantKeyMaterialChainHeadAndAuditInOneTransaction() {
        String slug = "f4-create-" + UUID.randomUUID();
        UUID actor = UUID.randomUUID();

        Tenant created = lifecycleService.create("F-4 Create CoC", slug, actor);
        UUID tenantId = created.getId();

        // Tenant row
        assertThat(tenantRepository.findById(tenantId))
            .as("tenant row persisted with ACTIVE state")
            .isPresent()
            .get()
            .satisfies(t -> {
                assertThat(t.getState()).isEqualTo(TenantState.ACTIVE);
                assertThat(t.getSlug()).isEqualTo(slug);
            });

        // Key material — gen 1 active row exists
        Integer gen = jdbc.queryForObject(
            "SELECT generation FROM tenant_key_material WHERE tenant_id = ? AND active = TRUE",
            Integer.class, tenantId);
        assertThat(gen)
            .as("eager bootstrap must produce gen-1 key material")
            .isEqualTo(1);

        // Kid registry — exactly one kid for this tenant at gen 1
        Integer kidCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM kid_to_tenant_key WHERE tenant_id = ? AND generation = 1",
            Integer.class, tenantId);
        assertThat(kidCount).isEqualTo(1);

        // audit_chain_head — one row, 32 zero bytes
        Integer chainHeadCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM tenant_audit_chain_head WHERE tenant_id = ?",
            Integer.class, tenantId);
        assertThat(chainHeadCount).isEqualTo(1);

        byte[] hash = jdbc.queryForObject(
            "SELECT last_hash FROM tenant_audit_chain_head WHERE tenant_id = ?",
            byte[].class, tenantId);
        assertThat(hash)
            .as("chain head seeded with 32 zero bytes (chain-start sentinel)")
            .hasSize(32)
            .containsOnly((byte) 0);

        // TENANT_CREATED audit row — query under tenant context so RLS admits it
        List<String> actions = queryAuditActionsForTenant(tenantId, actor);
        assertThat(actions)
            .as("TENANT_CREATED audit lands in the same tx as the tenant + key material")
            .contains(AuditEventType.TENANT_CREATED.name());
    }

    @Test
    void create_duplicateSlug_rejectedWithoutKeyMaterialOrAuditLeak() {
        String slug = "f4-dup-" + UUID.randomUUID();
        UUID firstActor = UUID.randomUUID();
        Tenant first = lifecycleService.create("First", slug, firstActor);

        // Record key-material + chain-head count BEFORE the failing second create
        int keyMaterialCountBefore = countKeyMaterialRows();
        int chainHeadCountBefore = countChainHeadRows();

        UUID secondActor = UUID.randomUUID();
        assertThatThrownBy(() -> lifecycleService.create("Second", slug, secondActor))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(slug);

        // No second key_material row, no second chain_head row, no TENANT_CREATED
        // audit for the rejected attempt (slug-check fires BEFORE any of that work).
        assertThat(countKeyMaterialRows()).isEqualTo(keyMaterialCountBefore);
        assertThat(countChainHeadRows()).isEqualTo(chainHeadCountBefore);

        // The FIRST actor's audit row still exists; the second actor's has nothing.
        // Query by secondActor to isolate.
        List<String> secondActorActions = queryAuditActionsForTenant(first.getId(), secondActor);
        assertThat(secondActorActions)
            .as("second actor's failed create has no audit rows")
            .doesNotContain(AuditEventType.TENANT_CREATED.name());
    }

    @Test
    void create_bootstrapsAreIndependentAcrossTenants() {
        // Create two tenants back-to-back; each must land its own key-material
        // row, chain-head row, and audit row. Regression guard for a bug class
        // where a cached kid from tenant A leaks into tenant B's bootstrap.
        UUID actor = UUID.randomUUID();
        Tenant a = lifecycleService.create("Alpha", "f4-alpha-" + UUID.randomUUID(), actor);
        Tenant b = lifecycleService.create("Beta",  "f4-beta-"  + UUID.randomUUID(), actor);

        assertThat(a.getId()).isNotEqualTo(b.getId());

        UUID kidA = jdbc.queryForObject(
            "SELECT kid FROM kid_to_tenant_key WHERE tenant_id = ? AND generation = 1",
            UUID.class, a.getId());
        UUID kidB = jdbc.queryForObject(
            "SELECT kid FROM kid_to_tenant_key WHERE tenant_id = ? AND generation = 1",
            UUID.class, b.getId());
        assertThat(kidA).isNotEqualTo(kidB);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private List<String> queryAuditActionsForTenant(UUID tenantId, UUID actor) {
        // audit_events is FORCE-RLS'd; bind tenant context so the SELECT admits
        // the tenant's own rows.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantId.toString());
            return jdbc.queryForList(
                "SELECT action FROM audit_events WHERE actor_user_id = ? AND tenant_id = ?",
                String.class, actor, tenantId);
        });
    }

    private int countKeyMaterialRows() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM tenant_key_material", Integer.class);
        return count == null ? 0 : count;
    }

    private int countChainHeadRows() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM tenant_audit_chain_head", Integer.class);
        return count == null ? 0 : count;
    }
}
