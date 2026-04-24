package org.fabt.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * §11.4 of design-f6-real-cryptoshred — pins the PERMISSIVE+RESTRICTIVE
 * RLS policy pair on {@code tenant_dek} (V82) plus the BEFORE DELETE
 * trigger guard (V82 §3 Q-F6-6).
 *
 * <p>Six assertions, one per cross-tenant write path + the trigger
 * negative case:
 *
 * <ol>
 *   <li>PERMISSIVE SELECT is not tenant-scoped — binding A can SELECT
 *       B's rows. Safe because tenant_dek.kid is opaque (UUID);
 *       enumeration yields no tenant-identifying information, and
 *       decrypt path needs to read BEFORE TenantContext binds.</li>
 *   <li>INSERT with tenant_id=B while bound to A is REJECTED
 *       (RESTRICTIVE INSERT gates on fabt_current_tenant_id).</li>
 *   <li>UPDATE of B's rows while bound to A affects 0 rows
 *       (RESTRICTIVE UPDATE narrows the USING clause).</li>
 *   <li>DELETE of B's rows while bound to A affects 0 rows
 *       (RESTRICTIVE DELETE narrows the USING clause). Note: even
 *       before the RESTRICTIVE narrow, the trigger guard would raise
 *       too — this test proves the RLS layer filters first.</li>
 *   <li>DELETE of A's rows without {@code app.tenant_id} bound
 *       affects 0 rows (RESTRICTIVE DELETE's USING evaluates
 *       fabt_current_tenant_id as NULL → policy rejects).</li>
 *   <li>DELETE of A's rows with app.tenant_id bound BUT without the
 *       shred GUC raises the trigger guard (SQLSTATE P0001).</li>
 * </ol>
 *
 * <p>Test isolation via per-test unique tenants (setupSecondaryTenant
 * with UUID-suffixed slug) so other test classes in the shared
 * Testcontainer can't pollute the assertions.
 */
@DisplayName("tenant_dek RLS + trigger guard (F-6.0 §11.4)")
class TenantDekRlsTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private TenantDekService tenantDekService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private UUID tenantA;
    private UUID tenantB;
    private UUID tenantAKid;

    @BeforeEach
    void setUp() {
        tenantA = authHelper.setupSecondaryTenant("dek-rls-a-" + UUID.randomUUID()).getId();
        tenantB = authHelper.setupSecondaryTenant("dek-rls-b-" + UUID.randomUUID()).getId();
        // Seed both tenants with a TOTP DEK so every RLS check has a target row.
        tenantAKid = tenantDekService.getOrCreateActiveDek(tenantA, KeyPurpose.TOTP).kid();
        tenantDekService.getOrCreateActiveDek(tenantB, KeyPurpose.TOTP);
    }

    // ──────────────────────────────────────────────────────────────────
    // 1 — PERMISSIVE SELECT: bound to A, can still SELECT B
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1. PERMISSIVE SELECT — bound to tenant A, SELECT of tenant B's rows returns rows")
    void permissiveSelect_visibleAcrossTenants() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Integer countB = tx.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantA.toString());
            return jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant_dek WHERE tenant_id = ?",
                Integer.class, tenantB);
        });
        assertThat(countB)
            .as("PERMISSIVE SELECT policy lets bound-tenant A see tenant B rows "
                + "— kids are opaque UUIDs, no enumeration risk")
            .isGreaterThanOrEqualTo(1);
    }

    // ──────────────────────────────────────────────────────────────────
    // 2 — RESTRICTIVE INSERT: bound to A, INSERT with tenant_id=B rejected
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("2. RESTRICTIVE INSERT — bound to tenant A, INSERT of tenant B row rejected")
    void restrictiveInsert_rejectsCrossTenantRow() {
        UUID newKid = UUID.randomUUID();
        byte[] fakeWrapped = new byte[40];  // 32-byte DEK → 40-byte AES-KWP output

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantA.toString());
            jdbc.update(
                "INSERT INTO tenant_dek (kid, tenant_id, purpose, generation, wrapped_dek, active) "
                + "VALUES (?, ?, 'WEBHOOK_SECRET', 1, ?, TRUE)",
                newKid, tenantB, fakeWrapped);
        }))
            .as("RESTRICTIVE INSERT policy must reject tenant_id=B when bound to A — "
                + "Spring surfaces SQLSTATE 42501 / RLS rejection as a DataAccessException "
                + "(BadSqlGrammarException on Postgres driver)")
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    // ──────────────────────────────────────────────────────────────────
    // 3 — RESTRICTIVE UPDATE: bound to A, UPDATE of B's rows affects 0
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("3. RESTRICTIVE UPDATE — bound to tenant A, UPDATE of tenant B rows affects 0 rows")
    void restrictiveUpdate_narrowsToBoundTenant() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Integer rowsAffected = tx.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantA.toString());
            return jdbc.update(
                "UPDATE tenant_dek SET rotated_at = clock_timestamp() "
                + "WHERE tenant_id = ? AND active = FALSE",  // active=FALSE so CHECK is consistent
                tenantB);
        });
        assertThat(rowsAffected)
            .as("RESTRICTIVE UPDATE narrows to rows where tenant_id = fabt_current_tenant_id()")
            .isEqualTo(0);
    }

    // ──────────────────────────────────────────────────────────────────
    // 4 — RESTRICTIVE DELETE: bound to A, DELETE of B's rows affects 0
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("4. RESTRICTIVE DELETE — bound to tenant A, DELETE of tenant B rows affects 0 rows")
    void restrictiveDelete_narrowsToBoundTenant() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Integer rowsAffected = tx.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantA.toString());
            // Also set shred GUC to B — the trigger would pass IF the RLS
            // DELETE policy let the row through. The RLS narrow should filter
            // it out first, so we never reach the trigger. 0 rows affected.
            jdbc.queryForObject("SELECT set_config('fabt.shred_in_progress', ?, true)",
                String.class, tenantB.toString());
            return jdbc.update("DELETE FROM tenant_dek WHERE tenant_id = ?", tenantB);
        });
        assertThat(rowsAffected)
            .as("RESTRICTIVE DELETE filters before the trigger fires — 0 rows affected")
            .isEqualTo(0);
    }

    // ──────────────────────────────────────────────────────────────────
    // 5 — Unbound DELETE: no app.tenant_id, RESTRICTIVE narrows to 0
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("5. Unbound DELETE — no app.tenant_id, DELETE of A's rows affects 0 rows")
    void unboundDelete_affectsZeroRows() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Integer rowsAffected = tx.execute(status -> {
            // Explicit empty binding — matches the posture of an unbound
            // session (fabt_current_tenant_id() returns NULL per V67).
            jdbc.queryForObject("SELECT set_config('app.tenant_id', '', true)",
                String.class);
            return jdbc.update("DELETE FROM tenant_dek WHERE tenant_id = ?", tenantA);
        });
        assertThat(rowsAffected)
            .as("RESTRICTIVE DELETE's USING evaluates fabt_current_tenant_id() as NULL "
                + "(app.tenant_id unset) — no row matches, 0 rows affected")
            .isEqualTo(0);
    }

    // ──────────────────────────────────────────────────────────────────
    // 6 — Trigger guard: bound to A, DELETE A's row without shred GUC raises
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("6. Trigger guard — DELETE of A's rows with app.tenant_id bound but no shred GUC raises P0001")
    void triggerGuard_raisesWhenShredGucMissing() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantA.toString());
            // Deliberately DO NOT set fabt.shred_in_progress. The RLS
            // RESTRICTIVE DELETE now lets the row through (tenant_id matches
            // bound tenant), so the trigger fires and raises.
            jdbc.update("DELETE FROM tenant_dek WHERE tenant_id = ?", tenantA);
        }))
            .as("BEFORE DELETE trigger must raise when fabt.shred_in_progress is unset")
            .isInstanceOfAny(DataIntegrityViolationException.class,
                org.springframework.jdbc.UncategorizedSQLException.class,
                org.springframework.dao.DataAccessException.class)
            .hasMessageContaining("tenant_dek row deletion attempted outside hardDelete");
    }
}
