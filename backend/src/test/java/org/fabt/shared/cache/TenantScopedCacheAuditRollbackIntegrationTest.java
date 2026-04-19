package org.fabt.shared.cache;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.shared.audit.AuditEventEntity;
import org.fabt.shared.audit.AuditEventTypes;
import org.fabt.shared.audit.repository.AuditEventRepository;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Task 4.9g — cross-tenant-read audit row persists across caller rollback.
 *
 * <p>Validates design-c D-C-9 + spec {@code tenant-scoped-cache-value-verification}
 * "Cross-tenant-read audit survives caller rollback" scenario. Marcus Webb's
 * warroom concern: an attacker who triggers a cross-tenant read inside a
 * transactional endpoint should NOT be able to erase the audit evidence by
 * forcing a rollback. The {@link org.fabt.shared.audit.DetachedAuditPersister}
 * with {@code PROPAGATION_REQUIRES_NEW} cuts the audit row loose from the
 * caller's transaction fate.
 *
 * <p>This is an integration test (not unit) because REQUIRES_NEW semantics
 * require a real Spring transaction manager + proxy. Extends {@link BaseIntegrationTest}
 * for Testcontainers Postgres.
 */
@DisplayName("TenantScopedCacheService — CROSS_TENANT_CACHE_READ audit survives caller rollback")
class TenantScopedCacheAuditRollbackIntegrationTest extends BaseIntegrationTest {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");

    @Autowired
    private TenantScopedCacheService wrapper;

    @Autowired
    private CacheService rawCache;

    @Autowired
    private TransactionTemplate txTemplate;

    @Autowired
    private AuditEventRepository auditRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("4.9g — CROSS_TENANT_CACHE_READ audit row remains committed after caller rollback")
    void crossTenantAuditSurvivesCallerRollback() {
        // Ensure the test tenants exist in the tenant table — both CROSS_TENANT_CACHE_READ
        // audits AND the outer transaction's set_config rely on FKs to real tenants.
        jdbc.update("INSERT INTO tenant (id, slug, name, state) VALUES (?, ?, ?, 'ACTIVE') "
                + "ON CONFLICT (id) DO NOTHING",
                TENANT_A, "audit-rollback-a", "Audit Rollback A");
        jdbc.update("INSERT INTO tenant (id, slug, name, state) VALUES (?, ?, ?, 'ACTIVE') "
                + "ON CONFLICT (id) DO NOTHING",
                TENANT_B, "audit-rollback-b", "Audit Rollback B");

        // Prime the cache directly with a payload stamped tenantA under tenantB's
        // prefixed key — the write-side-poisoning failure mode that the wrapper's
        // stamp/verify defends against.
        String tenantBScopedKey = TENANT_B + "|s1";
        rawCache.put(CacheNames.SHELTER_PROFILE, tenantBScopedKey,
                new TenantScopedValue<>(TENANT_A, "poisoned-value"),
                Duration.ofSeconds(60));

        long beforeCount = countCrossTenantAudits();

        // Execute the cross-tenant read inside a caller transaction that rolls back.
        // TransactionTemplate is used explicitly so the rollback happens inside a
        // real Spring transaction (not just a bare uncommitted connection).
        assertThatIllegalStateException()
                .isThrownBy(() ->
                        TenantContext.runWithContext(TENANT_B, false, () ->
                                txTemplate.executeWithoutResult(status -> {
                                    // Bind app.tenant_id for the caller's tx (Phase B B11 ordering)
                                    jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                                            String.class, TENANT_B.toString());
                                    // Trigger cross-tenant read — wrapper throws, tx rolls back
                                    wrapper.get(CacheNames.SHELTER_PROFILE, "s1", String.class);
                                })
                        )
                )
                .withMessage("CROSS_TENANT_CACHE_READ");

        // The outer tx has rolled back. The DetachedAuditPersister row should still
        // be present because it ran under PROPAGATION_REQUIRES_NEW.
        long afterCount = countCrossTenantAudits();
        assertThat(afterCount)
                .as("CROSS_TENANT_CACHE_READ audit row must survive the caller's rollback")
                .isEqualTo(beforeCount + 1);

        // Verify the row's tenant + action are correct
        List<AuditEventEntity> rows = findCrossTenantAuditsForTenant(TENANT_B);
        assertThat(rows).isNotEmpty();
        AuditEventEntity latest = rows.get(rows.size() - 1);
        assertThat(latest.getAction()).isEqualTo(AuditEventTypes.CROSS_TENANT_CACHE_READ);
        assertThat(latest.getTenantId()).isEqualTo(TENANT_B);
    }

    private long countCrossTenantAudits() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = ?",
                Long.class,
                AuditEventTypes.CROSS_TENANT_CACHE_READ);
        return count != null ? count : 0L;
    }

    private List<AuditEventEntity> findCrossTenantAuditsForTenant(UUID tenantId) {
        // Use the owner role so RLS doesn't filter for us; test contexts run under
        // fabt_test owner which has BYPASSRLS per Phase B setup.
        return jdbc.query(
                "SELECT id, timestamp, tenant_id, actor_user_id, target_user_id, action, "
                + "details, ip_address FROM audit_events "
                + "WHERE tenant_id = ? AND action = ? ORDER BY timestamp",
                (rs, rowNum) -> {
                    AuditEventEntity e = new AuditEventEntity();
                    e.setId((UUID) rs.getObject("id"));
                    e.setTimestamp(rs.getTimestamp("timestamp").toInstant());
                    e.setTenantId((UUID) rs.getObject("tenant_id"));
                    e.setAction(rs.getString("action"));
                    return e;
                },
                tenantId, AuditEventTypes.CROSS_TENANT_CACHE_READ);
    }
}
