package org.fabt.shelter;

import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V91 migration verification — task 2.5 of transitional-reentry-support.
 *
 * <p>Asserts the post-V91 schema invariants:
 * <ol>
 *   <li>{@code shelter.shelter_type} column exists with default {@code 'EMERGENCY'}</li>
 *   <li>{@code shelter.county} column exists, is nullable, and stores the value</li>
 *   <li>{@code shelter_dv_implies_dv_type} CHECK constraint exists in
 *       {@code pg_constraint} (schema-level proof) AND rejects writes that
 *       would diverge {@code dv_shelter}/{@code shelter_type} (behavioral
 *       proof, exercised inside {@link TenantContext#runWithContext} so RLS
 *       policies on {@code shelter} pass and the CHECK is reached)</li>
 *   <li>Backfill correctness: any {@code dv_shelter=true} row produced via INSERT
 *       must have {@code shelter_type='DV'} (by the constraint, post-V91)</li>
 *   <li>{@code tenant.config.features.reentryMode} defaults to {@code false} for
 *       tenants seeded by the V91 UPDATE</li>
 * </ol>
 *
 * <p>Tests run against the post-migration schema: Flyway has already applied
 * V91 by the time {@code @SpringBootTest} starts.
 *
 * <p>Why {@code TenantContext.runWithContext}: the {@code shelter} table has
 * {@code FORCE ROW LEVEL SECURITY} (V8). Without binding {@code app.tenant_id}
 * via {@code TenantContext}, the RESTRICTIVE policy rejects writes BEFORE the
 * CHECK constraint is reached — so we can't observe the constraint's behavior
 * via raw {@link JdbcTemplate}. This test binds context the same way real
 * service code does (e.g., {@code AnalyticsIntegrationTest}).
 */
class V91MigrationVerificationTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Insert a tenant directly via JDBC (the {@code tenant} table itself has
     * no RLS; the bypass concern is shelter only). Returns the new tenant's UUID.
     */
    private UUID createTenant(String slugSuffix) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO tenant (id, name, slug, config) VALUES (?, ?, ?, '{}'::jsonb)",
            id, "V91 Test Tenant " + slugSuffix, "v91-" + slugSuffix);
        return id;
    }

    // -------------------------------------------------------------------------
    // Schema introspection — proves the migration ran without behavior coupling
    // -------------------------------------------------------------------------

    @Test
    void shelter_type_column_exists_with_emergency_default() {
        // information_schema is the authoritative source for column metadata.
        // No RLS on system catalogs.
        String defaultExpr = jdbc.queryForObject("""
            SELECT column_default
              FROM information_schema.columns
             WHERE table_name = 'shelter' AND column_name = 'shelter_type'
            """, String.class);

        assertThat(defaultExpr)
            .as("V91 must define shelter.shelter_type with default 'EMERGENCY'")
            .contains("EMERGENCY");

        String dataType = jdbc.queryForObject("""
            SELECT data_type
              FROM information_schema.columns
             WHERE table_name = 'shelter' AND column_name = 'shelter_type'
            """, String.class);

        assertThat(dataType).isEqualTo("character varying");
    }

    @Test
    void shelter_county_column_exists_and_is_nullable() {
        String isNullable = jdbc.queryForObject("""
            SELECT is_nullable
              FROM information_schema.columns
             WHERE table_name = 'shelter' AND column_name = 'county'
            """, String.class);

        assertThat(isNullable)
            .as("V91 must define shelter.county as nullable (no DB enum per design D3)")
            .isEqualTo("YES");
    }

    @Test
    void shelter_dv_implies_dv_type_check_constraint_exists() {
        // pg_constraint is the source of truth for table constraints.
        Integer constraintCount = jdbc.queryForObject("""
            SELECT count(*)::int
              FROM pg_constraint
             WHERE conname = 'shelter_dv_implies_dv_type'
               AND contype = 'c'
            """, Integer.class);

        assertThat(constraintCount)
            .as("V91 must register the shelter_dv_implies_dv_type CHECK constraint in pg_constraint")
            .isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Behavioral assertions — proves the CHECK constraint actually rejects
    // diverged writes (exercised through TenantContext so RLS lets us reach
    // the CHECK)
    // -------------------------------------------------------------------------

    @Test
    void check_constraint_rejects_dv_shelter_true_with_non_dv_shelter_type() {
        UUID tenantId = createTenant("reject-insert-" + UUID.randomUUID().toString().substring(0, 8));
        UUID shelterId = UUID.randomUUID();

        TenantContext.runWithContext(tenantId, true, () -> {
            // Attempt INSERT with explicit shelter_type='EMERGENCY' but
            // dv_shelter=true. RLS allows (matching tenant context); CHECK
            // constraint should reject.
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO shelter (id, tenant_id, name, dv_shelter, shelter_type) "
                    + "VALUES (?, ?, ?, TRUE, 'EMERGENCY')",
                    shelterId, tenantId, "Bad DV Shelter"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("shelter_dv_implies_dv_type");
        });
    }

    @Test
    void check_constraint_rejects_update_setting_dv_shelter_without_updating_type() {
        UUID tenantId = createTenant("reject-update-" + UUID.randomUUID().toString().substring(0, 8));
        UUID shelterId = UUID.randomUUID();

        TenantContext.runWithContext(tenantId, true, () -> {
            // Insert as a regular EMERGENCY shelter
            jdbc.update("INSERT INTO shelter (id, tenant_id, name, dv_shelter) VALUES (?, ?, ?, FALSE)",
                shelterId, tenantId, "Regular Shelter");

            // Attempt UPDATE flipping dv_shelter=true without flipping shelter_type
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE shelter SET dv_shelter = TRUE WHERE id = ?", shelterId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("shelter_dv_implies_dv_type");
        });
    }

    @Test
    void check_constraint_accepts_consistent_dv_shelter_and_dv_type() {
        UUID tenantId = createTenant("accept-" + UUID.randomUUID().toString().substring(0, 8));
        UUID shelterId = UUID.randomUUID();

        TenantContext.runWithContext(tenantId, true, () -> {
            // Insert with both flags consistent — should succeed
            jdbc.update(
                "INSERT INTO shelter (id, tenant_id, name, dv_shelter, shelter_type) "
                + "VALUES (?, ?, ?, TRUE, 'DV')",
                shelterId, tenantId, "Valid DV Shelter");

            String shelterType = jdbc.queryForObject(
                "SELECT shelter_type FROM shelter WHERE id = ?", String.class, shelterId);
            assertThat(shelterType).isEqualTo("DV");
        });
    }

    @Test
    void emergency_default_applies_when_shelter_type_omitted() {
        UUID tenantId = createTenant("default-" + UUID.randomUUID().toString().substring(0, 8));
        UUID shelterId = UUID.randomUUID();

        TenantContext.runWithContext(tenantId, true, () -> {
            jdbc.update("INSERT INTO shelter (id, tenant_id, name, dv_shelter) VALUES (?, ?, ?, FALSE)",
                shelterId, tenantId, "Default Type Shelter");

            String shelterType = jdbc.queryForObject(
                "SELECT shelter_type FROM shelter WHERE id = ?", String.class, shelterId);
            assertThat(shelterType).isEqualTo("EMERGENCY");
        });
    }

    @Test
    void county_value_round_trips() {
        UUID tenantId = createTenant("county-" + UUID.randomUUID().toString().substring(0, 8));
        UUID shelterId = UUID.randomUUID();

        TenantContext.runWithContext(tenantId, true, () -> {
            jdbc.update(
                "INSERT INTO shelter (id, tenant_id, name, dv_shelter, county) VALUES (?, ?, ?, FALSE, 'Johnston')",
                shelterId, tenantId, "Johnston County Shelter");

            String county = jdbc.queryForObject(
                "SELECT county FROM shelter WHERE id = ?", String.class, shelterId);
            assertThat(county).isEqualTo("Johnston");
        });
    }

    // -------------------------------------------------------------------------
    // tenant.config.features.reentryMode seed (no RLS on tenant table)
    // -------------------------------------------------------------------------

    @Test
    void v91_seed_applies_features_reentry_mode_false_to_existing_tenants() {
        // The V91 UPDATE seeds every tenant that lacks a features.reentryMode
        // value. Insert a fresh tenant with empty config, then re-apply the
        // exact V91 seed expression — the post-state should have
        // features.reentryMode = false.
        UUID tenantId = createTenant("reentry-seed-" + UUID.randomUUID().toString().substring(0, 8));

        // Verify pre-state: no features.reentryMode key
        Boolean preExists = jdbc.queryForObject(
            "SELECT (config #> '{features,reentryMode}') IS NOT NULL FROM tenant WHERE id = ?",
            Boolean.class, tenantId);
        assertThat(preExists).isFalse();

        // Apply V91 seed UPDATE (mirrors migration line 109 verbatim)
        jdbc.update("""
            UPDATE tenant
               SET config = config || jsonb_build_object(
                    'features',
                    coalesce(config -> 'features', '{}'::jsonb) || jsonb_build_object('reentryMode', false)
                 )
             WHERE id = ?
            """, tenantId);

        Boolean reentryMode = jdbc.queryForObject(
            "SELECT (config #>> '{features,reentryMode}')::boolean FROM tenant WHERE id = ?",
            Boolean.class, tenantId);

        assertThat(reentryMode)
            .as("V91 seed expression must produce config.features.reentryMode = false")
            .isFalse();
    }
}
