package org.fabt.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.sql.SQLException;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.shared.web.TenantContext;
import org.fabt.tenant.domain.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase B close-out integration tests — 3.19, 3.21, 3.22.
 *
 * <p>Consolidates three RLS-enforcement guards into a single test class so
 * the Spring context spins up once per CI run rather than three times.
 * Each test isolates a distinct invariant of the Phase B FORCE-RLS regime.
 *
 * <h2>Assertions</h2>
 * <ul>
 *   <li><b>3.19</b> — every connection borrow ends up under the
 *       {@code fabt_app} role (not the owner {@code fabt}), because RLS
 *       bypass semantics treat superusers + table owners as exempt.</li>
 *   <li><b>3.21</b> — RLS policy on {@code audit_events} hides rows from
 *       non-owning tenants. A row inserted under tenant A must be
 *       invisible when the session binds tenant B.</li>
 *   <li><b>3.22</b> — running under {@code fabt_app}, tenant B cannot
 *       UPDATE tenant A's {@code audit_events} row. The FORCE RLS flag
 *       (V69) blocks owner-bypass attempts at the statement level.</li>
 * </ul>
 */
@DisplayName("Phase B RLS enforcement — 3.19 + 3.21 + 3.22 (task #165)")
class PhaseBRlsEnforcementTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID tenantAId;
    private UUID tenantBId;
    private UUID actorUserId;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        tenantAId = authHelper.getTestTenantId();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Tenant tenantB = authHelper.setupSecondaryTenant("phase-b-rls-" + suffix);
        tenantBId = tenantB.getId();

        actorUserId = UUID.randomUUID();
    }

    @Test
    @DisplayName("3.19 — every connection borrow runs as fabt_app (not owner fabt)")
    void connectionBorrow_runsAsFabtAppRole() {
        TenantContext.runWithContext(tenantAId, false, () -> {
            String currentUser = jdbcTemplate.queryForObject(
                    "SELECT current_user", String.class);
            assertThat(currentUser)
                    .as("RlsDataSourceConfig.applyRlsContext must SET ROLE fabt_app on every borrow — "
                            + "running as the owner role would bypass RLS entirely (PostgreSQL semantics).")
                    .isEqualTo("fabt_app");
        });

        // Also assert for the null-tenant case (scheduled job / system work)
        String currentUserNoContext = jdbcTemplate.queryForObject(
                "SELECT current_user", String.class);
        assertThat(currentUserNoContext)
                .as("Null TenantContext still drops to fabt_app — SET ROLE is unconditional")
                .isEqualTo("fabt_app");
    }

    @Test
    @DisplayName("3.21 — cross-tenant SELECT on audit_events returns zero rows (RLS hides)")
    void crossTenantSelectOnAuditEvents_rlsHidesOtherTenantRows() {
        UUID rowIdA = UUID.randomUUID();

        TenantContext.runWithContext(tenantAId, false, () ->
                jdbcTemplate.update(
                        "INSERT INTO audit_events (id, actor_user_id, action, tenant_id) "
                                + "VALUES (?, ?, ?, ?)",
                        rowIdA, actorUserId, "phase-b-3-21-probe", tenantAId));

        // Under tenant A, the row IS visible.
        Integer visibleToA = TenantContext.callWithContext(tenantAId, null, false, () ->
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM audit_events WHERE id = ?",
                        Integer.class, rowIdA));
        assertThat(visibleToA)
                .as("Tenant A must still see its own audit row (sanity)")
                .isEqualTo(1);

        // Under tenant B, the row is hidden.
        Integer visibleToB = TenantContext.callWithContext(tenantBId, null, false, () ->
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM audit_events WHERE id = ?",
                        Integer.class, rowIdA));
        assertThat(visibleToB)
                .as("RLS policy tenant_isolation_audit_events must hide tenant A's row from tenant B — "
                        + "this is the load-bearing invariant of Phase B V68 + V69.")
                .isZero();
    }

    @Test
    @DisplayName("3.22 — owner-bypass prevention: UPDATE/DELETE on audit_events is revoked for fabt_app (V70)")
    void auditEvents_updateAndDeleteRevoked_defenseInDepth() {
        // V70 explicitly REVOKEs UPDATE + DELETE on the audit tables from
        // fabt_app, making them append-only at the GRANT level. FORCE RLS
        // on V69 is the secondary guard; for UPDATE+DELETE the primary
        // guard is the missing GRANT. Assert the primary guard holds.
        UUID rowId = UUID.randomUUID();

        TenantContext.runWithContext(tenantAId, false, () ->
                jdbcTemplate.update(
                        "INSERT INTO audit_events (id, actor_user_id, action, tenant_id) "
                                + "VALUES (?, ?, ?, ?)",
                        rowId, actorUserId, "phase-b-3-22-probe", tenantAId));

        // Attempted UPDATE under the same tenant — fails with SQLSTATE 42501
        // (insufficient_privilege) because V70 REVOKEs UPDATE from fabt_app.
        // Assert on SQLSTATE rather than message text so a future PG release
        // that rewords the English message doesn't break this test.
        Throwable updateFailure = catchExceptionInRlsContext(tenantAId, () ->
                jdbcTemplate.update(
                        "UPDATE audit_events SET action = 'modified' WHERE id = ?", rowId));
        assertRootCauseSqlState(updateFailure, "42501",
                "UPDATE on audit_events must fail with 42501 (GRANT revoked by V70)");

        // Attempted DELETE also blocked by GRANT — same SQLSTATE.
        Throwable deleteFailure = catchExceptionInRlsContext(tenantAId, () ->
                jdbcTemplate.update("DELETE FROM audit_events WHERE id = ?", rowId));
        assertRootCauseSqlState(deleteFailure, "42501",
                "DELETE on audit_events must fail with 42501 (GRANT revoked by V70)");
    }

    @Test
    @DisplayName("3.22 — fabt_app cannot INSERT an audit row claiming a different tenant (WITH CHECK enforcement)")
    void crossTenantInsertWithForeignTenantId_blockedByWithCheck() {
        UUID rowId = UUID.randomUUID();

        // Under tenant A, try to insert a row with tenant_id = B.
        // The RLS policy's WITH CHECK clause (tenant_id = fabt_current_tenant_id())
        // rejects this as a policy violation (SQLSTATE 42501). Asserting on
        // SQLSTATE is more durable than matching the English error message.
        Throwable failure = catchExceptionInRlsContext(tenantAId, () ->
                jdbcTemplate.update(
                        "INSERT INTO audit_events (id, actor_user_id, action, tenant_id) "
                                + "VALUES (?, ?, ?, ?)",
                        rowId, actorUserId, "phase-b-3-22-forged", tenantBId));
        assertRootCauseSqlState(failure, "42501",
                "INSERT with foreign tenant_id must fail with 42501 (RLS WITH CHECK)");
    }

    /**
     * Runs {@code action} under {@code TenantContext.runWithContext(tenantId)}
     * and returns the thrown exception for inspection. Using this helper
     * instead of AssertJ's {@code assertThatThrownBy} because the lambda
     * needs to run inside a ScopedValue binding and AssertJ's fluent API
     * cannot thread an arbitrary context through the call chain cleanly.
     */
    private Throwable catchExceptionInRlsContext(UUID tenantId, Runnable action) {
        try {
            TenantContext.runWithContext(tenantId, false, action);
            fail("Expected the action to throw, but it succeeded");
            return null; // unreachable
        } catch (RuntimeException e) {
            return e;
        }
    }

    /**
     * Asserts that {@code throwable}'s cause chain contains a
     * {@link SQLException} whose {@code SQLState} equals {@code expected}.
     */
    private void assertRootCauseSqlState(Throwable throwable, String expected, String message) {
        Throwable cur = throwable;
        while (cur != null) {
            if (cur instanceof SQLException se && expected.equals(se.getSQLState())) {
                return;
            }
            cur = cur.getCause();
        }
        fail(message + " — no SQLException with SQLState=" + expected
                + " found in cause chain of " + throwable);
    }
}
