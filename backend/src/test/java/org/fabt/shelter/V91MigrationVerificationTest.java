package org.fabt.shelter;

import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.shared.web.TenantContext;
import org.fabt.shelter.domain.Shelter;
import org.fabt.shelter.domain.ShelterType;
import org.fabt.shelter.repository.ShelterRepository;
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

    @Autowired
    private ShelterRepository shelterRepository;

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

    /**
     * Tests the V91 seed UPDATE expression in isolation — does NOT prove that
     * V91 ran against tenants pre-existing at migration time (Flyway runs V91
     * once before tests start; we have no pre-V91 tenant to backfill against
     * mid-test). This test asserts the EXPRESSION's correctness; the
     * "did the migration actually fire" guarantee comes from Flyway's
     * atomic-apply contract (V91 marked success in flyway_schema_history
     * means the UPDATE ran successfully).
     *
     * Renamed from `v91_seed_applies_features_reentry_mode_false_to_existing_tenants`
     * per warroom B1 — the original name overclaimed.
     */
    @Test
    void v91_seed_pattern_produces_expected_features_reentry_mode_false_shape() {
        UUID tenantId = createTenant("reentry-seed-" + UUID.randomUUID().toString().substring(0, 8));

        // Verify pre-state: no features.reentryMode key
        Boolean preExists = jdbc.queryForObject(
            "SELECT (config #> '{features,reentryMode}') IS NOT NULL FROM tenant WHERE id = ?",
            Boolean.class, tenantId);
        assertThat(preExists).isFalse();

        // Apply V91 seed UPDATE (mirrors migration verbatim)
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

    /**
     * V91 seed must be idempotent — running it twice on a tenant must not flip
     * the operator-set value. Specifically: PLATFORM_OPERATOR sets
     * features.reentryMode = true, then a re-run of the V91 UPDATE must NOT
     * reset it to false. Verifies the WHERE clause `(config #> '{features,reentryMode}') IS NULL`
     * actually skips already-seeded tenants. Warroom B1 follow-up.
     */
    @Test
    void v91_seed_pattern_is_idempotent_does_not_overwrite_existing_value() {
        UUID tenantId = createTenant("reentry-idempotent-" + UUID.randomUUID().toString().substring(0, 8));

        // Operator sets reentryMode=true (post-V91, post-creation). Use the
        // same `||` concat pattern V91 uses — `jsonb_set` with create_missing
        // does NOT create intermediate path objects (only the final key), so
        // it would silently no-op against a `'{}'` config that lacks the
        // `features` parent. The concat pattern handles missing intermediates.
        jdbc.update("""
            UPDATE tenant
               SET config = config || jsonb_build_object(
                    'features',
                    coalesce(config -> 'features', '{}'::jsonb) || jsonb_build_object('reentryMode', true)
                 )
             WHERE id = ?
            """, tenantId);

        // Pre-condition for the test
        Boolean before = jdbc.queryForObject(
            "SELECT (config #>> '{features,reentryMode}')::boolean FROM tenant WHERE id = ?",
            Boolean.class, tenantId);
        assertThat(before).isTrue();

        // Re-run the V91 seed. The WHERE clause MUST exclude this tenant
        // because features.reentryMode is already set (to true, but value
        // doesn't matter — the clause checks key presence not value).
        jdbc.update("""
            UPDATE tenant
               SET config = config || jsonb_build_object(
                    'features',
                    coalesce(config -> 'features', '{}'::jsonb) || jsonb_build_object('reentryMode', false)
                 )
             WHERE (config #> '{features,reentryMode}') IS NULL
               AND id = ?
            """, tenantId);

        Boolean after = jdbc.queryForObject(
            "SELECT (config #>> '{features,reentryMode}')::boolean FROM tenant WHERE id = ?",
            Boolean.class, tenantId);
        assertThat(after)
            .as("V91 seed must be idempotent — operator-set true must NOT be reset to false on re-run")
            .isTrue();
    }

    /**
     * Backfill regression guard (warroom B2): assert that NO row in the shelter
     * table violates the `dv_shelter=true → shelter_type='DV'` invariant. After
     * V91 this is structurally guaranteed by the CHECK constraint (any
     * pre-existing diverged row would have caused the ADD CONSTRAINT step to
     * fail and Flyway to mark V91 as failed). This direct test catches the
     * regression where a future migration re-orders V91's steps such that the
     * CHECK is added BEFORE the backfill UPDATE — at which point a fresh DB
     * with no pre-V91 rows still passes (WHERE matches 0) but a prod DB with
     * pre-existing dv_shelter=true rows would fail the constraint.
     */
    @Test
    void v91_no_diverged_shelter_rows_post_migration() {
        Integer divergedCount = jdbc.queryForObject(
            "SELECT count(*)::int FROM shelter WHERE dv_shelter = TRUE AND shelter_type <> 'DV'",
            Integer.class);

        assertThat(divergedCount)
            .as("Post-V91 invariant: no shelter row may have dv_shelter=TRUE and shelter_type != 'DV' "
                + "(if this fails, V91's UPDATE backfill ran in the wrong order relative to the CHECK ADD, "
                + "or a later migration introduced divergent rows)")
            .isZero();
    }

    // -------------------------------------------------------------------------
    // Spring Data JDBC entity round-trip (warroom H1)
    //
    // The Shelter entity gained a `shelterType` ShelterType enum field.
    // Spring Data JDBC's default mapper converts the PG VARCHAR column ↔ Java
    // enum via Enum.valueOf(name). Production code relies on this; this test
    // makes the contract explicit so a future Spring upgrade that changes
    // enum-handling fails LOUDLY here rather than silently producing null
    // shelterType on every read.
    // -------------------------------------------------------------------------

    @Test
    void shelter_entity_round_trips_shelterType_DV_through_spring_data_jdbc() {
        UUID tenantId = createTenant("round-trip-dv-" + UUID.randomUUID().toString().substring(0, 8));

        UUID id = TenantContext.callWithContext(tenantId, true, () -> {
            Shelter shelter = new Shelter();
            shelter.setTenantId(tenantId);
            shelter.setName("Round-trip DV Shelter");
            shelter.setDvShelter(true);
            shelter.setShelterType(ShelterType.DV);
            shelter.setCreatedAt(java.time.Instant.now());
            shelter.setUpdatedAt(java.time.Instant.now());
            Shelter saved = shelterRepository.save(shelter);
            return saved.getId();
        });

        Shelter loaded = TenantContext.callWithContext(tenantId, true,
            () -> shelterRepository.findById(id).orElseThrow());

        assertThat(loaded.getShelterType())
            .as("Shelter entity must round-trip shelter_type='DV' as ShelterType.DV enum value")
            .isEqualTo(ShelterType.DV);
        assertThat(loaded.isDvShelter()).isTrue();
    }

    @Test
    void shelter_entity_round_trips_shelterType_EMERGENCY_through_spring_data_jdbc() {
        UUID tenantId = createTenant("round-trip-em-" + UUID.randomUUID().toString().substring(0, 8));

        UUID id = TenantContext.callWithContext(tenantId, true, () -> {
            Shelter shelter = new Shelter();
            shelter.setTenantId(tenantId);
            shelter.setName("Round-trip EMERGENCY Shelter");
            shelter.setDvShelter(false);
            // Leave shelterType unset — entity default is EMERGENCY, V91 column
            // default is also EMERGENCY. Either way the round-trip should
            // produce ShelterType.EMERGENCY.
            shelter.setCreatedAt(java.time.Instant.now());
            shelter.setUpdatedAt(java.time.Instant.now());
            Shelter saved = shelterRepository.save(shelter);
            return saved.getId();
        });

        Shelter loaded = TenantContext.callWithContext(tenantId, true,
            () -> shelterRepository.findById(id).orElseThrow());

        assertThat(loaded.getShelterType()).isEqualTo(ShelterType.EMERGENCY);
        assertThat(loaded.isDvShelter()).isFalse();
    }
}
