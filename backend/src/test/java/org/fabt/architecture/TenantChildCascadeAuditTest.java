package org.fabt.architecture;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.fabt.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway CI check — §11.5 of design-f6-real-cryptoshred. Pins the child-
 * table CASCADE invariant that {@code TenantLifecycleService.hardDelete}
 * (task 7.8f) relies on to crypto-shred a tenant via a single
 * {@code DELETE FROM tenant WHERE id = ?}.
 *
 * <p>The test scans {@code pg_catalog.pg_constraint} for every FK that
 * references {@code tenant(id)} and compares against two allowlists
 * derived from Appendix A of the design doc:
 *
 * <ul>
 *   <li>{@link #MUST_CASCADE_FROM_TENANT} — tables whose per-tenant rows
 *       must be destroyed alongside the tenant row. Their FK
 *       {@code confdeltype} must be {@code 'c'} (CASCADE).</li>
 *   <li>{@link #MUST_NOT_FK_TO_TENANT} — tables that must NOT have a FK
 *       to tenant, by design. Currently just {@code audit_events} per
 *       Q-F6-5 Riley: its nullable {@code tenant_id} column survives
 *       hard-delete as a platform-owned tombstone.</li>
 * </ul>
 *
 * <p>Uses {@code pg_catalog} (not {@code information_schema}) deliberately
 * per Jordan pass-2: {@code information_schema.referential_constraints}
 * filters rows by current-user grants and can miss entries under RLS;
 * {@code pg_catalog.pg_constraint} sees every constraint regardless of
 * privilege.
 *
 * <p><b>Failure modes this test catches:</b>
 *
 * <ul>
 *   <li>A future developer adds a new per-tenant child table with a FK
 *       to tenant but forgets the CASCADE clause — caught at build time,
 *       not at the first prod shred.</li>
 *   <li>A future migration flips one of the existing 18 CASCADE FKs back
 *       to RESTRICT — caught the instant the regression hits main.</li>
 *   <li>A future developer adds a tenant FK to {@code audit_events} —
 *       caught; the tombstone contract explicitly forbids it.</li>
 * </ul>
 *
 * <p>Adding a new per-tenant child table post-V84:
 *
 * <ol>
 *   <li>Add the new table in its own migration with
 *       {@code REFERENCES tenant(id) ON DELETE CASCADE} inline.</li>
 *   <li>Add the table name to {@link #MUST_CASCADE_FROM_TENANT}.</li>
 *   <li>Also add to V84's DO-block's {@code target_tables} array IF the
 *       new migration forgot CASCADE (the test will catch this too,
 *       with a diff-style error message).</li>
 * </ol>
 *
 * @see org.fabt.architecture.MigrationLintTest
 * @see <a href="file:../../../main/resources/db/migration/V84__tenant_fk_cascade.sql">V84__tenant_fk_cascade.sql</a>
 */
@DisplayName("Tenant child-table CASCADE audit (F-6.0 task 7.8e — design-f6-real-cryptoshred §11.5)")
class TenantChildCascadeAuditTest extends BaseIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    /**
     * Tables whose per-tenant rows MUST cascade on tenant row removal.
     * Synced with V84's {@code target_tables} array + V61 (tenant_key_material,
     * kid_to_tenant_key) + V80 (tenant_audit_chain_head) + V82 (tenant_dek).
     *
     * <p>Adding a per-tenant child table post-V84 requires adding it here
     * AND ensuring the migration uses {@code ON DELETE CASCADE}.
     */
    private static final Set<String> MUST_CASCADE_FROM_TENANT = Set.of(
            // V84 flipped these 18 from NO ACTION → CASCADE
            "app_user", "api_key", "shelter", "import_log",
            "tenant_oauth2_provider", "subscription", "bed_availability",
            "reservation", "surge_event", "referral_token",
            "hmis_outbox", "hmis_audit_log", "bed_search_log",
            "daily_utilization_summary", "one_time_access_code",
            "notification", "password_reset_token", "escalation_policy",
            // V61 + V80 + V82 landed CASCADE in their own schema migrations
            "tenant_key_material", "kid_to_tenant_key",
            "tenant_audit_chain_head", "tenant_dek"
    );

    /**
     * Tables that MUST NOT have a FK to tenant — their {@code tenant_id}
     * column (if any) is deliberately FK-less to preserve rows across
     * hard-delete. Currently just audit_events per Q-F6-5 Riley: the
     * shred tombstone row lives here with NULL tenant_id after the
     * parent tenant is deleted, so compliance auditors can prove
     * "tenant X was deleted on Y by Z" post-shred.
     */
    private static final Set<String> MUST_NOT_FK_TO_TENANT = Set.of(
            "audit_events"
    );

    @Test
    @DisplayName("Every FK to tenant(id) is CASCADE; audit_events has no FK to tenant")
    void allTenantChildFksMustCascade() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT "
                + "    conname, "
                + "    conrelid::regclass::text AS owning_table, "
                + "    confdeltype "
                + "FROM pg_catalog.pg_constraint "
                + "WHERE confrelid = 'public.tenant'::regclass "
                + "  AND contype = 'f' "
                + "ORDER BY owning_table");

        Map<String, String> actual = new HashMap<>();
        for (Map<String, Object> row : rows) {
            // conrelid cast yields "public.<table>" or just "<table>" depending on
            // search_path. Strip the schema prefix for allowlist comparison.
            String owningTable = ((String) row.get("owning_table")).replaceFirst("^public\\.", "");
            String deleteRule = row.get("confdeltype").toString();  // 'c', 'r', 'a', 'n', 'd'
            actual.put(owningTable, deleteRule);
        }

        // ─── Check 1: every MUST_CASCADE entry has confdeltype = 'c' ───
        Map<String, String> nonCascadeViolations = new HashMap<>();
        Set<String> missingFks = new HashSet<>();
        for (String expectedTable : MUST_CASCADE_FROM_TENANT) {
            String actualRule = actual.get(expectedTable);
            if (actualRule == null) {
                missingFks.add(expectedTable);
            } else if (!"c".equals(actualRule)) {
                nonCascadeViolations.put(expectedTable, humanReadableRule(actualRule));
            }
        }

        // ─── Check 2: no MUST_NOT_FK table has a FK to tenant ───
        Set<String> forbiddenFks = new HashSet<>();
        for (String forbiddenTable : MUST_NOT_FK_TO_TENANT) {
            if (actual.containsKey(forbiddenTable)) {
                forbiddenFks.add(forbiddenTable);
            }
        }

        // ─── Check 3: every FK we observed is on one of our two lists ───
        //    Catches the "developer added a new per-tenant child table but
        //    forgot to update the test" case — drift the other direction.
        Set<String> undeclaredFks = new HashSet<>(actual.keySet());
        undeclaredFks.removeAll(MUST_CASCADE_FROM_TENANT);
        undeclaredFks.removeAll(MUST_NOT_FK_TO_TENANT);

        // ─── Assertions with actionable error messages ───
        assertThat(missingFks)
                .as("These tables are on MUST_CASCADE_FROM_TENANT but have NO FK to tenant(id) in pg_constraint. "
                    + "Either the table was dropped (remove from the test constant) or its migration forgot "
                    + "REFERENCES tenant(id) — fix the migration.")
                .isEmpty();

        assertThat(nonCascadeViolations)
                .as("These tables have a FK to tenant(id) but NOT with ON DELETE CASCADE. "
                    + "hardDelete's single-statement shred will fail on the FK. "
                    + "Fix: write a new migration with ALTER TABLE <table> DROP/ADD CONSTRAINT to flip it to CASCADE.")
                .isEmpty();

        assertThat(forbiddenFks)
                .as("These tables are on MUST_NOT_FK_TO_TENANT but now HAVE a FK to tenant(id). "
                    + "Adding such a FK breaks the shred-tombstone contract from Q-F6-5 (audit trail "
                    + "must survive hard-delete). Fix: drop the FK; keep the tenant_id column nullable.")
                .isEmpty();

        assertThat(undeclaredFks)
                .as("These tables have a FK to tenant(id) but are not declared in either allowlist. "
                    + "Decide whether per-tenant data in this table should cascade on hard-delete: "
                    + "YES → add the table to MUST_CASCADE_FROM_TENANT and ensure the FK is CASCADE. "
                    + "NO (platform-level, like audit_events) → drop the FK and add to MUST_NOT_FK_TO_TENANT.")
                .isEmpty();
    }

    /**
     * Translates {@code pg_constraint.confdeltype} codes to human-readable
     * SQL names for error messages.
     */
    private static String humanReadableRule(String code) {
        return switch (code) {
            case "a" -> "NO ACTION";
            case "r" -> "RESTRICT";
            case "c" -> "CASCADE";
            case "n" -> "SET NULL";
            case "d" -> "SET DEFAULT";
            default -> "UNKNOWN(" + code + ")";
        };
    }
}
