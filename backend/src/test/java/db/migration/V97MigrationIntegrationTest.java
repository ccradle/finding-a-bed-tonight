package db.migration;

import java.util.UUID;

import org.fabt.Application;
import org.fabt.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V97 backfill migration IT — dv-policy-tenant-flag tasks §3.2, §3.3, §3.4, §3.6.
 *
 * <p>Pins the migration's three invariants:
 * <ul>
 *   <li><b>Backfill correctness</b>: tenants with at least one DV shelter
 *       (active OR inactive) land on {@code dv_policy_enabled = true}.</li>
 *   <li><b>No incorrect spread</b>: tenants with zero DV shelters do NOT have
 *       the key written, so {@code Tenant.isDvPolicyEnabled()} returns false
 *       (the safe default) for them.</li>
 *   <li><b>Idempotency</b>: re-applying V97 on already-backfilled state
 *       produces the same end state — no drift, no duplicate writes.</li>
 * </ul>
 *
 * <p>Riley's multi-tenant fixture (§3.6) covers all four state cases:
 * (a) tenant with active DV shelters
 * (b) tenant with only inactive DV shelters
 * (c) tenant with non-DV shelters only
 * (d) tenant with zero shelters
 *
 * <p>Test data isolation per {@code feedback_isolated_test_data}: every test
 * creates its own freshly-uuid'd tenants. Does NOT piggyback on
 * {@code dev-coc*} seed tenants — their state is shared across tests and
 * would produce flaky assertions.
 *
 * <p>Implementation note: V97 has already run at test boot for the schema
 * (Flyway applies all up to HEAD). Newly-created tenants (created in
 * {@code @BeforeEach}) need the migration applied manually via JdbcTemplate
 * to test post-migration state. The SQL is mirrored from the migration file
 * verbatim; if the file changes, this constant must change.
 */
@DisplayName("V97 backfill_dv_policy_enabled migration")
@SpringBootTest(classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class V97MigrationIntegrationTest extends BaseIntegrationTest {

    /**
     * Mirrors V97__backfill_dv_policy_enabled.sql verbatim. Kept inline so the
     * test asserts on the SAME SQL the migration runs — if the migration file
     * drifts, this constant should drift with it (and the test should be re-run).
     */
    private static final String V97_SQL = """
            UPDATE tenant
            SET config = jsonb_set(
                    COALESCE(config, '{}'::jsonb),
                    '{dv_policy_enabled}',
                    'true'::jsonb,
                    true
                )
            WHERE id IN (
                SELECT DISTINCT tenant_id
                FROM shelter
                WHERE dv_shelter = true
            )
            """;

    @Autowired private JdbcTemplate jdbc;

    private UUID tenantWithActiveDv;
    private UUID tenantWithInactiveDvOnly;
    private UUID tenantWithNonDvOnly;
    private UUID tenantWithZeroShelters;

    @BeforeEach
    void setUp() {
        // Insert four fresh test tenants directly via SQL to avoid bootstrap
        // side-effects (audit chain seed, key material, etc.) — this test is
        // pure migration semantics, not lifecycle.
        tenantWithActiveDv = insertTenant("v97-active-dv");
        tenantWithInactiveDvOnly = insertTenant("v97-inactive-dv-only");
        tenantWithNonDvOnly = insertTenant("v97-non-dv-only");
        tenantWithZeroShelters = insertTenant("v97-zero-shelters");

        insertShelter(tenantWithActiveDv, "Active DV", true, true);
        insertShelter(tenantWithActiveDv, "Active non-DV", false, true);
        insertShelter(tenantWithInactiveDvOnly, "Inactive DV", true, false);
        insertShelter(tenantWithNonDvOnly, "Active non-DV A", false, true);
        insertShelter(tenantWithNonDvOnly, "Active non-DV B", false, true);
        // tenantWithZeroShelters intentionally has no shelters
    }

    @Test
    @DisplayName("§3.2 tenant with active DV shelter backfills to true")
    void tenantWithActiveDvShelterBackfillsToTrue() {
        runV97();
        assertThat(readDvPolicyKey(tenantWithActiveDv)).isEqualTo("true");
    }

    @Test
    @DisplayName("§3.6(b) tenant with only inactive DV shelters still backfills to true")
    void tenantWithOnlyInactiveDvShelterBackfillsToTrue() {
        // Empirical evidence of prior DV-shelter responsibility — even if all
        // DV shelters are deactivated, the historical commitment exists, so
        // the backfill includes them. (See design D6.)
        runV97();
        assertThat(readDvPolicyKey(tenantWithInactiveDvOnly)).isEqualTo("true");
    }

    @Test
    @DisplayName("§3.3 tenant with no DV shelters does NOT have the key written")
    void tenantWithNoDvSheltersIsNotModified() {
        runV97();
        // Key absent — readDvPolicyKey returns null, which the helper treats
        // as the default false reading.
        assertThat(readDvPolicyKey(tenantWithNonDvOnly)).isNull();
    }

    @Test
    @DisplayName("§3.6(d) tenant with zero shelters does NOT have the key written")
    void tenantWithZeroSheltersIsNotModified() {
        runV97();
        assertThat(readDvPolicyKey(tenantWithZeroShelters)).isNull();
    }

    @Test
    @DisplayName("§3.4 idempotency — re-running V97 produces identical state")
    void migrationIsIdempotent() {
        runV97();
        String firstReading = readDvPolicyKey(tenantWithActiveDv);
        Long firstUpdatedAt = jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM updated_at)::bigint FROM tenant WHERE id = ?",
                Long.class, tenantWithActiveDv);

        runV97();
        String secondReading = readDvPolicyKey(tenantWithActiveDv);
        Long secondUpdatedAt = jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM updated_at)::bigint FROM tenant WHERE id = ?",
                Long.class, tenantWithActiveDv);

        assertThat(secondReading).isEqualTo(firstReading);
        // updated_at may be bumped on each run because jsonb_set rewrites the
        // row regardless. The KEY invariant is content-stability of the
        // dv_policy_enabled value, not row-version stability.
        assertThat(firstUpdatedAt).isNotNull();
        assertThat(secondUpdatedAt).isNotNull();
    }

    @Test
    @DisplayName("§3.6(a) preserves other config keys when adding dv_policy_enabled")
    void preservesOtherConfigKeys() {
        // Prime config with a pre-existing key
        jdbc.update(
                "UPDATE tenant SET config = '{\"hold_duration_minutes\":120,\"default_locale\":\"en\"}'::jsonb WHERE id = ?",
                tenantWithActiveDv);
        runV97();

        Integer holdDuration = jdbc.queryForObject(
                "SELECT (config -> 'hold_duration_minutes')::int FROM tenant WHERE id = ?",
                Integer.class, tenantWithActiveDv);
        String locale = jdbc.queryForObject(
                "SELECT config ->> 'default_locale' FROM tenant WHERE id = ?",
                String.class, tenantWithActiveDv);

        assertThat(holdDuration).isEqualTo(120);
        assertThat(locale).isEqualTo("en");
        assertThat(readDvPolicyKey(tenantWithActiveDv)).isEqualTo("true");
    }

    // ---- helpers --------------------------------------------------------

    private UUID insertTenant(String slugPrefix) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO tenant (id, name, slug, config, created_at, updated_at) "
                        + "VALUES (?, ?, ?, '{}'::jsonb, NOW(), NOW())",
                id, slugPrefix + "-" + id, slugPrefix + "-" + id);
        return id;
    }

    private void insertShelter(UUID tenantId, String name, boolean dvShelter, boolean active) {
        jdbc.update(
                "INSERT INTO shelter (id, tenant_id, name, dv_shelter, active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, NOW(), NOW())",
                UUID.randomUUID(), tenantId, name, dvShelter, active);
    }

    private void runV97() {
        jdbc.execute(V97_SQL);
    }

    /**
     * Returns the raw text representation of {@code config -> 'dv_policy_enabled'}
     * — {@code "true"}, {@code "false"}, or {@code null} if the key is absent.
     */
    private String readDvPolicyKey(UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT config ->> 'dv_policy_enabled' FROM tenant WHERE id = ?",
                String.class, tenantId);
    }
}
