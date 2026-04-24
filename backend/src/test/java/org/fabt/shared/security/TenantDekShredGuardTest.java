package org.fabt.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * §11.7 of design-f6-real-cryptoshred — trigger-guard semantics for
 * {@code tenant_dek_shred_guard} (V82 §3).
 *
 * <p>Three cases:
 *
 * <ol>
 *   <li><b>Positive</b> — {@code hardDelete} succeeds: tenant row deleted,
 *       CASCADE fires, trigger guard allows the tenant_dek DELETE because
 *       hardDelete bound {@code fabt.shred_in_progress} before firing.</li>
 *   <li><b>No GUC</b> — DELETE attempted as {@code fabt_app} without
 *       setting {@code fabt.shred_in_progress}: trigger raises SQLSTATE
 *       {@code P0001} with the expected message.</li>
 *   <li><b>Cross-tenant GUC poisoning</b> — attacker sets
 *       {@code fabt.shred_in_progress} to tenant A but tries to DELETE
 *       tenant B's rows: trigger's {@code IS DISTINCT FROM} equality check
 *       still raises.</li>
 * </ol>
 *
 * <p>The happy-path already has coverage via
 * {@link org.fabt.tenant.service.TenantLifecycleHardDeleteIntegrationTest}
 * T1 — this class focuses on the adversarial paths Jordan's pass-2
 * warroom flagged.
 *
 * <p>Retention window overridden to 0 days so the positive case can exercise
 * a complete lifecycle without time-travel.
 */
@TestPropertySource(properties = {
        "fabt.tenant.lifecycle.enabled=true",
        "fabt.tenant.hard-delete.retention-days=0"
})
@DisplayName("tenant_dek trigger-guard — positive + no-GUC + cross-tenant-poisoning (F-6.0 §11.7)")
class TenantDekShredGuardTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private TenantLifecycleService lifecycleService;
    @Autowired private TenantDekService tenantDekService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        tenantA = authHelper.setupSecondaryTenant(
            "dek-guard-a-" + UUID.randomUUID()).getId();
        tenantB = authHelper.setupSecondaryTenant(
            "dek-guard-b-" + UUID.randomUUID()).getId();
        // Seed a DEK for each so every case has a row to operate on.
        tenantDekService.getOrCreateActiveDek(tenantA, KeyPurpose.TOTP);
        tenantDekService.getOrCreateActiveDek(tenantB, KeyPurpose.TOTP);
    }

    // ──────────────────────────────────────────────────────────────────
    // 1 — Positive: hardDelete succeeds, CASCADE fires through the guard
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1. POSITIVE — hardDelete binds fabt.shred_in_progress, CASCADE to tenant_dek passes the guard")
    void positive_hardDelete_cascadeFiresThroughGuard() {
        // Build tenant C (fresh per-test) and drive through the full
        // lifecycle. tenantA / tenantB are seeded for negative cases only;
        // we don't want to delete them here.
        UUID tenantC = lifecycleService.create("Shred Guard Positive", "guard-" + UUID.randomUUID(), UUID.randomUUID()).getId();
        UUID actor = UUID.randomUUID();

        tenantDekService.getOrCreateActiveDek(tenantC, KeyPurpose.TOTP);
        assertThat(countTenantDekRows(tenantC)).isGreaterThanOrEqualTo(1);

        lifecycleService.offboard(tenantC, actor, "test-offboard");
        lifecycleService.archive(tenantC, actor, "test-archive");
        lifecycleService.hardDelete(tenantC, actor, "test-shred");

        // Trigger allowed the CASCADE; rows are gone.
        assertThat(countTenantDekRows(tenantC))
            .as("positive path: trigger guard permits CASCADE DELETE when "
                + "fabt.shred_in_progress matches OLD.tenant_id")
            .isEqualTo(0);
    }

    // ──────────────────────────────────────────────────────────────────
    // 2 — No GUC: bound to A, DELETE A's row without shred GUC → P0001
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("2. NO-GUC — bound to A, DELETE A's tenant_dek row without shred GUC raises P0001")
    void negative_noShredGuc_raisesP0001() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            // Bind app.tenant_id so the RLS RESTRICTIVE DELETE allows the row
            // through. WITHOUT binding fabt.shred_in_progress, the trigger
            // should raise when it fires BEFORE DELETE.
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantA.toString());
            // Shred GUC explicitly left unset here.
            jdbc.update("DELETE FROM tenant_dek WHERE tenant_id = ?", tenantA);
        }))
            .as("trigger must raise when fabt.shred_in_progress is unset "
                + "even though RLS would otherwise permit the DELETE")
            .hasMessageContaining("tenant_dek row deletion attempted outside hardDelete");

        // Tenant A's row must still exist — the tx rolled back on the raise.
        assertThat(countTenantDekRows(tenantA))
            .as("failed DELETE must not remove the row")
            .isGreaterThanOrEqualTo(1);
    }

    // ──────────────────────────────────────────────────────────────────
    // 3 — Cross-tenant GUC poisoning: shred GUC=A, DELETE B → P0001
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("3. CROSS-TENANT GUC POISONING — shred GUC set to A but DELETE of B's row still raises")
    void negative_crossTenantGucPoisoning_raisesP0001() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            // Bind RLS to B so the RESTRICTIVE DELETE lets B's row through.
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantB.toString());
            // But set the shred GUC to A (the poisoning). Trigger's
            // IS DISTINCT FROM equality check must reject because
            // current_setting('fabt.shred_in_progress') != OLD.tenant_id.
            jdbc.queryForObject("SELECT set_config('fabt.shred_in_progress', ?, true)",
                String.class, tenantA.toString());
            jdbc.update("DELETE FROM tenant_dek WHERE tenant_id = ?", tenantB);
        }))
            .as("trigger must raise when fabt.shred_in_progress is set to a "
                + "different tenant than OLD.tenant_id (cross-tenant GUC poisoning)")
            .hasMessageContaining("tenant_dek row deletion attempted outside hardDelete");

        // Tenant B's row must still exist.
        assertThat(countTenantDekRows(tenantB))
            .as("failed DELETE must not remove the row")
            .isGreaterThanOrEqualTo(1);
    }

    private int countTenantDekRows(UUID tenantId) {
        Integer c = jdbc.queryForObject(
            "SELECT COUNT(*) FROM tenant_dek WHERE tenant_id = ?",
            Integer.class, tenantId);
        return c == null ? 0 : c;
    }
}
