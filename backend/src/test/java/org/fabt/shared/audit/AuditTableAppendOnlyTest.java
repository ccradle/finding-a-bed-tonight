package org.fabt.shared.audit;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.fabt.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * V70 + V72 append-only audit posture regression guard (Marcus + Jordan
 * checkpoint-2 warroom).
 *
 * <p>V70 revokes UPDATE, DELETE from {@code fabt_app}; V72 adds TRUNCATE +
 * REFERENCES. These tests prove the privilege layer actually blocks each
 * operation — a passing test means a compromised fabt_app credential
 * cannot mutate or wipe the audit tables even with tenant context bound.
 *
 * <p><b>Why positive tests matter:</b> Marcus flagged that the evidence V70
 * worked was INDIRECT — removed cleanup DELETEs in unrelated tests. That's
 * "tests fail if the revoke is broken" which reads as a green test today
 * but a silent regression tomorrow if someone adds a fresh test with a
 * cleanup that happens to work under a different role binding. A dedicated
 * append-only test documents the invariant explicitly.
 *
 * <p><b>SQLState 42501</b> is Postgres's "insufficient_privilege" code.
 * That's what fabt_app sees when the REVOKE blocks it — distinct from
 * RLS-filtered zero-rows (which would appear as a no-op UPDATE/DELETE
 * returning 0 rows affected).
 */
@DisplayName("Audit tables are APPEND-ONLY for fabt_app — V70 + V72 regression guards")
class AuditTableAppendOnlyTest extends BaseIntegrationTest {

    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("fabt_app cannot DELETE from audit_events (V70 revoke)")
    void fabtApp_cannotDelete_auditEvents() throws SQLException {
        assertSqlState42501("DELETE FROM audit_events WHERE action = 'nonexistent'");
    }

    @Test
    @DisplayName("fabt_app cannot UPDATE audit_events (V70 revoke)")
    void fabtApp_cannotUpdate_auditEvents() throws SQLException {
        assertSqlState42501("UPDATE audit_events SET action = 'tampered' WHERE action = 'nonexistent'");
    }

    @Test
    @DisplayName("fabt_app cannot TRUNCATE audit_events (V72 revoke — bypasses RLS!)")
    void fabtApp_cannotTruncate_auditEvents() throws SQLException {
        // TRUNCATE is particularly important: it's NOT implied by DELETE
        // and it BYPASSES RLS policies entirely. Without the V72 revoke,
        // a compromised fabt_app credential could wipe every audit row
        // in one statement.
        assertSqlState42501("TRUNCATE audit_events");
    }

    @Test
    @DisplayName("fabt_app cannot DELETE from hmis_audit_log (V70 revoke)")
    void fabtApp_cannotDelete_hmisAuditLog() throws SQLException {
        assertSqlState42501("DELETE FROM hmis_audit_log WHERE tenant_id IS NULL");
    }

    @Test
    @DisplayName("fabt_app cannot TRUNCATE hmis_audit_log (V72 revoke)")
    void fabtApp_cannotTruncate_hmisAuditLog() throws SQLException {
        assertSqlState42501("TRUNCATE hmis_audit_log");
    }

    @Test
    @DisplayName("fabt_app CAN INSERT into audit_events (append-only means append IS allowed)")
    void fabtApp_canInsert_auditEvents() throws SQLException {
        // Sanity: make sure the revoke didn't accidentally block INSERT too.
        // Use a no-op tenant_id + action that won't collide with any real data.
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            // RlsDataSourceConfig has already set SET ROLE fabt_app on borrow.
            // Binding app.tenant_id to SYSTEM_TENANT_ID so FORCE RLS accepts
            // this smoke INSERT.
            try (Statement bind = conn.createStatement()) {
                bind.execute("SELECT set_config('app.tenant_id', "
                        + "'00000000-0000-0000-0000-000000000001', false)");
            }
            int rows = stmt.executeUpdate(
                    "INSERT INTO audit_events (action, tenant_id) VALUES "
                    + "('PB_APPENDONLY_SMOKE', '00000000-0000-0000-0000-000000000001')");
            assertEquals(1, rows, "INSERT must still succeed under fabt_app post-V70/V72");
        }
    }

    /**
     * Asserts that executing {@code sql} as {@code fabt_app} raises a
     * SQLException with SQLState {@code 42501} (insufficient_privilege).
     * A different error (e.g., 42601 syntax, 25006 invalid tx state) or
     * a successful execution both fail the test — we need to confirm the
     * privilege layer is what blocks, not some incidental error.
     */
    private void assertSqlState42501(String sql) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            // Connection comes pre-SET-ROLE'd to fabt_app by RlsDataSourceConfig.
            stmt.execute(sql);
            fail("Expected SQLState 42501 (insufficient_privilege) on: " + sql
                    + "; but the statement succeeded, which means the revoke is broken");
        } catch (SQLException e) {
            String sqlState = e.getSQLState();
            assertTrue("42501".equals(sqlState),
                    "Expected SQLState 42501 (insufficient_privilege) on: " + sql
                    + "; got SQLState=" + sqlState + ", message=" + e.getMessage());
        }
    }
}
