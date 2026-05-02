package db.migration;

import java.util.UUID;

import org.fabt.Application;
import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.shared.web.TenantContext;
import org.fabt.shelter.fixtures.TestShelterFixture;
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
 * creates its own freshly-bootstrapped tenants via
 * {@link TestAuthHelper#setupSecondaryTenant(String)}. Does NOT piggyback on
 * {@code dev-coc*} seed tenants — their state is shared across tests and
 * would produce flaky assertions.
 *
 * <p><b>RLS context (per {@code feedback_per_user_rls_wrong_pattern} +
 * {@code feedback_rls_hides_dv_data}):</b> the {@code shelter} table is
 * RLS-protected and {@code RlsDataSourceConfig} auto-applies
 * {@code SET ROLE fabt_app} on every connection. All {@code shelter}
 * INSERTs / UPDATEs MUST run inside
 * {@link TenantContext#runWithContext(java.util.UUID, boolean, Runnable)}
 * with {@code dvAccess=true} (so DV shelters are not RLS-hidden during
 * fixture setup). The V97 SQL itself ALSO runs inside the per-tenant
 * context — its EXISTS subquery only sees shelters visible to the bound
 * tenant, which is the correct semantics here because we're verifying
 * per-tenant migration outcomes.
 *
 * <p>The {@code tenant} table is NOT RLS-protected (tenant isolation is
 * application-layer per {@link TestAuthHelper} javadoc), so the post-condition
 * read of {@code config ->> 'dv_policy_enabled'} runs without a context
 * binding.
 *
 * <p><b>V91 invariant note:</b> {@code dv_shelter=true} requires
 * {@code shelter_type='DV'} (V91 CHECK constraint).
 * {@link TestShelterFixture#insertShelter} handles this lockstep automatically.
 *
 * <p><b>V97 SQL mirroring:</b> the SQL constant is identical to the migration
 * file. If the file changes, this constant must change.
 */
@DisplayName("V97 backfill_dv_policy_enabled migration")
@SpringBootTest(classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class V97MigrationIntegrationTest extends BaseIntegrationTest {

    /**
     * Loaded from {@code db/migration/V97__backfill_dv_policy_enabled.sql} at
     * test class init time so the IT exercises the SAME SQL the production
     * migration runs. Eliminates the drift risk of mirroring the SQL in
     * a Java constant. (Riley warroom round 2, L1.)
     */
    private static final String V97_SQL = loadMigrationSql();

    private static String loadMigrationSql() {
        try (var stream = V97MigrationIntegrationTest.class.getResourceAsStream(
                "/db/migration/V97__backfill_dv_policy_enabled.sql")) {
            if (stream == null) {
                throw new IllegalStateException(
                        "V97__backfill_dv_policy_enabled.sql not found on classpath");
            }
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to load V97 migration SQL", e);
        }
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestAuthHelper authHelper;

    private UUID tenantWithActiveDv;
    private UUID tenantWithInactiveDvOnly;
    private UUID tenantWithNonDvOnly;
    private UUID tenantWithZeroShelters;

    @BeforeEach
    void setUp() {
        // Each test gets its own freshly-bootstrapped tenants. Slug suffix
        // includes a UUID to avoid collisions across test runs.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantWithActiveDv = authHelper.setupSecondaryTenant("v97-active-dv-" + suffix).getId();
        tenantWithInactiveDvOnly = authHelper.setupSecondaryTenant("v97-inactive-only-" + suffix).getId();
        tenantWithNonDvOnly = authHelper.setupSecondaryTenant("v97-non-dv-only-" + suffix).getId();
        tenantWithZeroShelters = authHelper.setupSecondaryTenant("v97-zero-" + suffix).getId();

        // Bootstrap above already created each tenant with default config.
        // Reset the dv_policy_enabled key (if a prior bootstrap path set it)
        // so we can observe V97 explicitly setting or NOT setting it.
        clearDvPolicyKey(tenantWithActiveDv);
        clearDvPolicyKey(tenantWithInactiveDvOnly);
        clearDvPolicyKey(tenantWithNonDvOnly);
        clearDvPolicyKey(tenantWithZeroShelters);

        // Insert shelters in each tenant's RLS context. dvAccess=true so the
        // dv_shelter=true rows are not hidden by RLS during fixture setup.
        TenantContext.runWithContext(tenantWithActiveDv, true, () -> {
            UUID activeDvId = TestShelterFixture.insertShelter(jdbc, tenantWithActiveDv, "Active DV", true);
            TestShelterFixture.insertShelter(jdbc, tenantWithActiveDv, "Active non-DV", false);
            // V97 SQL filters dv_shelter=true regardless of active flag for
            // backfill, so leave Active DV active=true (default).
        });

        TenantContext.runWithContext(tenantWithInactiveDvOnly, true, () -> {
            UUID inactiveDvId = TestShelterFixture.insertShelter(jdbc, tenantWithInactiveDvOnly, "Inactive DV", true);
            // V52 added 'active' column with default TRUE; flip to false here
            // so this tenant has only INACTIVE DV shelters. This exercises
            // case (b) of Riley's fixture.
            jdbc.update("UPDATE shelter SET active = false WHERE id = ?", inactiveDvId);
        });

        TenantContext.runWithContext(tenantWithNonDvOnly, true, () -> {
            TestShelterFixture.insertShelter(jdbc, tenantWithNonDvOnly, "Active non-DV A", false);
            TestShelterFixture.insertShelter(jdbc, tenantWithNonDvOnly, "Active non-DV B", false);
        });

        // tenantWithZeroShelters intentionally has no shelters
    }

    @Test
    @DisplayName("§3.2 tenant with active DV shelter backfills to true")
    void tenantWithActiveDvShelterBackfillsToTrue() {
        runV97For(tenantWithActiveDv);
        assertThat(readDvPolicyKey(tenantWithActiveDv)).isEqualTo("true");
    }

    @Test
    @DisplayName("§3.6(b) tenant with only inactive DV shelters still backfills to true")
    void tenantWithOnlyInactiveDvShelterBackfillsToTrue() {
        // Empirical evidence of prior DV-shelter responsibility — even if all
        // DV shelters are deactivated, the historical commitment exists, so
        // the backfill includes them. (See design D6.)
        runV97For(tenantWithInactiveDvOnly);
        assertThat(readDvPolicyKey(tenantWithInactiveDvOnly)).isEqualTo("true");
    }

    @Test
    @DisplayName("§3.3 tenant with no DV shelters does NOT have the key written")
    void tenantWithNoDvSheltersIsNotModified() {
        runV97For(tenantWithNonDvOnly);
        assertThat(readDvPolicyKey(tenantWithNonDvOnly)).isNull();
    }

    @Test
    @DisplayName("§3.6(d) tenant with zero shelters does NOT have the key written")
    void tenantWithZeroSheltersIsNotModified() {
        runV97For(tenantWithZeroShelters);
        assertThat(readDvPolicyKey(tenantWithZeroShelters)).isNull();
    }

    @Test
    @DisplayName("§3.4 idempotency — re-running V97 produces identical state")
    void migrationIsIdempotent() {
        runV97For(tenantWithActiveDv);
        String firstReading = readDvPolicyKey(tenantWithActiveDv);
        runV97For(tenantWithActiveDv);
        String secondReading = readDvPolicyKey(tenantWithActiveDv);
        assertThat(secondReading).isEqualTo(firstReading);
        assertThat(secondReading).isEqualTo("true");
    }

    @Test
    @DisplayName("§3.6(a) preserves other config keys when adding dv_policy_enabled")
    void preservesOtherConfigKeys() {
        // Prime config with pre-existing keys (tenant table is NOT RLS-protected,
        // so this UPDATE works without TenantContext binding).
        jdbc.update(
                "UPDATE tenant SET config = '{\"hold_duration_minutes\":120,\"default_locale\":\"en\"}'::jsonb WHERE id = ?",
                tenantWithActiveDv);

        runV97For(tenantWithActiveDv);

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

    /**
     * Run V97_SQL inside the given tenant's RLS context. The migration's
     * EXISTS subquery only sees shelters visible to fabt_app + the bound
     * tenant — which is the correct semantics: the production migration
     * runs as the schema-migration role (full access), but the per-tenant
     * outcome is the same because each tenant's UPDATE is independent of
     * other tenants' shelter inventory.
     */
    private void runV97For(UUID tenantId) {
        TenantContext.runWithContext(tenantId, true, () -> {
            jdbc.execute(V97_SQL);
        });
    }

    /**
     * Returns the raw text representation of {@code config -> 'dv_policy_enabled'}
     * — {@code "true"}, {@code "false"}, or {@code null} if the key is absent.
     * The tenant table is not RLS-protected, so this read needs no context.
     */
    private String readDvPolicyKey(UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT config ->> 'dv_policy_enabled' FROM tenant WHERE id = ?",
                String.class, tenantId);
    }

    /**
     * Removes the {@code dv_policy_enabled} key from a tenant's config so
     * the test can observe V97 explicitly setting it (or not).
     * {@code setupSecondaryTenant} may bootstrap the tenant with the key
     * already present (depending on bootstrap defaults), so we normalize.
     */
    private void clearDvPolicyKey(UUID tenantId) {
        jdbc.update(
                "UPDATE tenant SET config = config - 'dv_policy_enabled' WHERE id = ?",
                tenantId);
    }
}
