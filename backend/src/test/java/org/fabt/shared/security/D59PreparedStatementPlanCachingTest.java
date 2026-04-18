package org.fabt.shared.security;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.fabt.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D59 — Prepared-statement plan-caching gate for Phase B RLS.
 *
 * <p><b>This test MUST pass before any Phase B migration is written.</b>
 * Per Marcus C-B-N5 + Q4 resolution: if Postgres inlines
 * {@code fabt_current_tenant_id()} into a cached prepared-statement plan at
 * PREPARE time (rather than re-evaluating per EXECUTE), cross-tenant data
 * leaks through plan reuse across pool-borrows. That would be catastrophic
 * — the entire v2 design assumes STABLE LEAKPROOF functions are re-evaluated
 * per query invocation, which is Postgres's documented behavior for GUC-
 * dependent functions, but we must VERIFY before shipping.
 *
 * <p>If this test fails, Phase B STOPS and re-warrooms before any fallback
 * (VOLATILE fallback is NOT auto-applied — its perf cliff is too severe).
 *
 * <h2>What this test exercises</h2>
 *
 * <ol>
 *   <li>Creates a minimal test table {@code d59_probe_table} with
 *       {@code (tenant_id uuid, marker text)} + RLS enabled + FORCE RLS
 *       + a policy filtering on {@code tenant_id = fabt_current_tenant_id()}.</li>
 *   <li>Creates the LEAKPROOF CASE-guarded
 *       {@code fabt_current_tenant_id()} function that V67 will ship.</li>
 *   <li>Inserts marker rows for two tenants.</li>
 *   <li>Binds {@code app.tenant_id} to tenant A, PREPARES + EXECUTES a
 *       cached statement.</li>
 *   <li>Re-binds {@code app.tenant_id} to tenant B, EXECUTES the SAME
 *       prepared statement on a borrowed connection (simulating pool reuse).</li>
 *   <li>Asserts the returned rows are tenant B's rows, NOT tenant A's
 *       (the plan-caching failure mode).</li>
 * </ol>
 *
 * <p>Also inspects {@code EXPLAIN (GENERIC_PLAN, VERBOSE)} output to confirm
 * the function is NOT inlined as a constant literal.
 *
 * <p>Runs as {@code fabt_app} role (not {@code fabt} owner) because RLS is
 * what we're testing; superusers bypass RLS silently.
 */
@DisplayName("D59 — Prepared-statement plan caching does NOT inline fabt_current_tenant_id()")
class D59PreparedStatementPlanCachingTest extends BaseIntegrationTest {

    private static final UUID TENANT_A = UUID.fromString("d59a0000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("d59b0000-0000-0000-0000-000000000002");

    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("GIVEN cached prepared-statement on pool-borrowed connection "
            + "WHEN app.tenant_id changes between executes "
            + "THEN fabt_current_tenant_id() re-evaluates per execute (no cross-tenant leak)")
    void prepared_statement_does_not_inline_tenant_function() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // RlsAwareDataSource auto-applies SET ROLE fabt_app on every
            // getConnection(). For the DDL setup steps we need owner privileges,
            // so reset to the owner role first. We re-SET ROLE fabt_app below
            // for the actual RLS plan-caching test.
            try (Statement reset = conn.createStatement()) {
                reset.execute("RESET ROLE");
            }
            conn.setAutoCommit(false);

            try (Statement setup = conn.createStatement()) {
                // Clean slate (tolerant of re-runs during dev)
                setup.execute("DROP TABLE IF EXISTS d59_probe_table CASCADE");
                setup.execute("DROP FUNCTION IF EXISTS d59_fabt_current_tenant_id()");

                // The LEAKPROOF function — exact shape V67 will ship.
                setup.execute(
                        "CREATE FUNCTION d59_fabt_current_tenant_id() "
                        + "RETURNS uuid LANGUAGE sql STABLE LEAKPROOF PARALLEL SAFE AS $$ "
                        + "  SELECT CASE "
                        + "    WHEN current_setting('app.tenant_id', true) IS NULL THEN NULL::uuid "
                        + "    WHEN current_setting('app.tenant_id', true) = '' THEN NULL::uuid "
                        + "    WHEN current_setting('app.tenant_id', true) !~ "
                        + "      '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' "
                        + "      THEN NULL::uuid "
                        + "    ELSE current_setting('app.tenant_id', true)::uuid "
                        + "  END $$");

                // Minimal probe table + RLS + FORCE.
                setup.execute(
                        "CREATE TABLE d59_probe_table (tenant_id uuid NOT NULL, marker text NOT NULL)");
                setup.execute("ALTER TABLE d59_probe_table ENABLE ROW LEVEL SECURITY");
                setup.execute("ALTER TABLE d59_probe_table FORCE ROW LEVEL SECURITY");
                setup.execute(
                        "CREATE POLICY d59_tenant_iso ON d59_probe_table "
                        + "FOR ALL USING (tenant_id = d59_fabt_current_tenant_id()) "
                        + "WITH CHECK (tenant_id = d59_fabt_current_tenant_id())");

                // Grant to fabt_app so the test harness under RLS can read.
                setup.execute("GRANT ALL ON d59_probe_table TO fabt_app");
                // Jordan checkpoint D: explicitly GRANT EXECUTE on the function
                // so a Testcontainers/PGDG variant with default_privileges
                // REVOKE-PUBLIC-EXECUTE doesn't fail with permission-denied
                // and mask the real plan-caching signal.
                setup.execute("GRANT EXECUTE ON FUNCTION d59_fabt_current_tenant_id() TO fabt_app");
            }
            conn.commit();

            // Insert tenant A's row + tenant B's row using the owner
            // connection (which DOES bypass RLS in Testcontainers PG),
            // simulating canonically-tenant-owned data written by each tenant's
            // own authenticated request pre-test.
            try (PreparedStatement insertA = conn.prepareStatement(
                    "INSERT INTO d59_probe_table (tenant_id, marker) VALUES (?, ?)")) {
                insertA.setObject(1, TENANT_A);
                insertA.setString(2, "TENANT_A_MARKER");
                insertA.executeUpdate();
                insertA.setObject(1, TENANT_B);
                insertA.setString(2, "TENANT_B_MARKER");
                insertA.executeUpdate();
            }
            conn.commit();

            // Drop to fabt_app — superuser bypass defeats RLS otherwise.
            try (Statement drop = conn.createStatement()) {
                drop.execute("SET ROLE fabt_app");
            }

            // CRITICAL: bind app.tenant_id to TENANT_A + PREPARE the statement.
            // Jordan checkpoint C: use parameterized set_config instead of
            // string-concatenated SET LOCAL. UUID constants are safe in this
            // test, but the pattern in test code sets a bad precedent for
            // real code paths — if copy-pasted into production, this becomes
            // a tenant-spoofing primitive. Match the prod pattern here.
            try (PreparedStatement bindA = conn.prepareStatement(
                    "SELECT set_config('app.tenant_id', ?, true)")) {
                bindA.setString(1, TENANT_A.toString());
                bindA.executeQuery().close();
            }

            // Use PgJDBC server-side prepared statement mode by executing the
            // statement 6 times (PgJDBC default threshold = 5 for plan caching).
            // This forces the plan into the server-side cache.
            String probeSql = "SELECT tenant_id, marker FROM d59_probe_table "
                    + "WHERE tenant_id = d59_fabt_current_tenant_id()";
            List<String> tenantAMarkers = new ArrayList<>();
            try (PreparedStatement probe = conn.prepareStatement(probeSql)) {
                for (int i = 0; i < 6; i++) {
                    try (ResultSet rs = probe.executeQuery()) {
                        while (rs.next()) {
                            tenantAMarkers.add(rs.getString("marker"));
                        }
                    }
                }
            }
            // Must see TENANT_A_MARKER exactly 6 times (once per execute), NEVER TENANT_B.
            assertEquals(6, tenantAMarkers.size(),
                    "Expected tenant A to see 6 marker rows (one per execute); got " + tenantAMarkers);
            for (String m : tenantAMarkers) {
                assertEquals("TENANT_A_MARKER", m,
                    "Tenant A prepared execute MUST see only tenant A rows");
            }

            // NOW rebind to TENANT_B, on the SAME connection, and re-execute
            // the (now server-side-cached) prepared statement. If Postgres
            // inlined fabt_current_tenant_id() at PREPARE time, the cached
            // plan carries TENANT_A's UUID constant → we'd see tenant A rows,
            // which would be a catastrophic cross-tenant leak.
            // Jordan checkpoint C: parameterized set_config (see above).
            try (PreparedStatement bindB = conn.prepareStatement(
                    "SELECT set_config('app.tenant_id', ?, true)")) {
                bindB.setString(1, TENANT_B.toString());
                bindB.executeQuery().close();
            }
            List<String> tenantBMarkers = new ArrayList<>();
            try (PreparedStatement probe = conn.prepareStatement(probeSql)) {
                // Re-prepare same SQL — PgJDBC re-uses server-side cached plan
                // after the 5-execution threshold.
                for (int i = 0; i < 3; i++) {
                    try (ResultSet rs = probe.executeQuery()) {
                        while (rs.next()) {
                            tenantBMarkers.add(rs.getString("marker"));
                        }
                    }
                }
            }
            assertFalse(tenantBMarkers.isEmpty(),
                    "Tenant B MUST see at least one row after binding app.tenant_id to TENANT_B "
                    + "— otherwise fabt_current_tenant_id() was inlined at PREPARE and "
                    + "Phase B RLS cannot safely use STABLE functions. STOP + re-warroom.");
            for (String m : tenantBMarkers) {
                assertEquals("TENANT_B_MARKER", m,
                    "CATASTROPHIC: tenant B prepared execute saw " + m
                    + " instead of TENANT_B_MARKER. Plan caching inlined the function. "
                    + "Phase B MUST STOP per design-a5-v74-reencrypt §Q4 / design-b §D59.");
            }

            // Belt-and-suspenders: inspect EXPLAIN (GENERIC_PLAN) output.
            // GENERIC_PLAN shows a parameter-agnostic plan — if the function
            // appears as a constant literal here, we have plan-inlining.
            try (Statement explain = conn.createStatement();
                    ResultSet rs = explain.executeQuery(
                        "EXPLAIN (GENERIC_PLAN, VERBOSE) " + probeSql)) {
                StringBuilder plan = new StringBuilder();
                while (rs.next()) {
                    plan.append(rs.getString(1)).append('\n');
                }
                String planText = plan.toString();
                assertNotNull(planText);
                // The function must appear as a function-call expression in the
                // plan, not as a constant UUID. If the plan contains a hex-UUID
                // literal in the filter, that's inlining.
                assertFalse(planText.contains(TENANT_A.toString()),
                    "EXPLAIN GENERIC_PLAN contains tenant A's UUID as a constant "
                    + "— function was inlined at PREPARE. Plan: " + planText);
                assertFalse(planText.contains(TENANT_B.toString()),
                    "EXPLAIN GENERIC_PLAN contains tenant B's UUID as a constant "
                    + "— function was inlined at PREPARE. Plan: " + planText);
                // The plan SHOULD reference the function by name.
                assertTrue(
                    planText.contains("d59_fabt_current_tenant_id")
                        || planText.contains("current_setting"),
                    "EXPLAIN GENERIC_PLAN should reference the function by name or its "
                    + "inner current_setting call — neither found. Plan: " + planText);
            }

            // Cleanup.
            conn.commit();
            try (Statement cleanup = conn.createStatement()) {
                cleanup.execute("RESET ROLE");
                cleanup.execute("DROP TABLE IF EXISTS d59_probe_table CASCADE");
                cleanup.execute("DROP FUNCTION IF EXISTS d59_fabt_current_tenant_id()");
            }
            conn.commit();
        }
    }
}
