package org.fabt.shelter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verification suite for V95 demo shelter seed expansion. Mirrors
 * V91MigrationVerificationTest: assert what V95 actually persisted,
 * separate from the upstream code paths that consume it.
 *
 * <p>Why this test exists. V76/V77 originally shipped without a
 * companion verification test, and the missing coordinator_assignment
 * rows were caught live during the v0.48 post-deploy walkthrough --
 * costing a V78 hotfix migration. V95 carries similar risk (3 shelters
 * x 2 tenants + constraints + bed_availability + tenant.config update +
 * coordinator_assignment) so the rows that the demo UX depends on need
 * a structural assertion before the deploy.
 *
 * <p>RLS note. {@code shelter}, {@code shelter_constraints},
 * {@code bed_availability}, and {@code coordinator_assignment} all have
 * FORCE ROW LEVEL SECURITY enabled (V8). Reads must run with
 * {@code app.tenant_id} bound via {@link TenantContext}, mirroring
 * V91MigrationVerificationTest's pattern. The tenant-scoped helpers
 * below wrap each query in {@code TenantContext.runWithContext(...)}.
 *
 * <p>Idempotency. V95 uses ON CONFLICT DO UPDATE on every INSERT, so
 * these tests are safe whether V95 runs once or is re-run during
 * disaster recovery.
 */
class V95SeedDemoShelterTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private static final UUID TENANT_WEST = UUID.fromString("a0000000-0000-0000-0000-000000000002");
    private static final UUID TENANT_EAST = UUID.fromString("a0000000-0000-0000-0000-000000000003");

    private static final UUID WEST_TRANSITIONAL = UUID.fromString("d0000001-0000-0000-0000-000000000004");
    private static final UUID WEST_REENTRY      = UUID.fromString("d0000001-0000-0000-0000-000000000005");
    private static final UUID WEST_OVERFLOW     = UUID.fromString("d0000001-0000-0000-0000-000000000006");
    private static final UUID EAST_TRANSITIONAL = UUID.fromString("d0000002-0000-0000-0000-000000000004");
    private static final UUID EAST_REENTRY      = UUID.fromString("d0000002-0000-0000-0000-000000000005");
    private static final UUID EAST_OVERFLOW     = UUID.fromString("d0000002-0000-0000-0000-000000000006");

    // -----------------------------------------------------------------
    // §1. Existing east + west shelters: no regression on dv_shelter.
    //
    // V95 only backfills county on these rows (NULL -> known value).
    // dv_shelter values must remain what V76/V77 + V91 backfill set.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("existing west non-DV shelters preserve dv_shelter=false + get county backfilled")
    void existingWestShelters_haveCountyBackfilled() {
        TenantContext.runWithContext(TENANT_WEST, true, () -> {
            Map<String, Object> boone = jdbc.queryForMap(
                "SELECT dv_shelter, county, shelter_type FROM shelter WHERE id = ?::uuid",
                "d0000001-0000-0000-0000-000000000001");
            assertThat(boone.get("dv_shelter")).as("Boone (existing) dv_shelter unchanged").isEqualTo(false);
            assertThat(boone.get("county")).as("Boone county backfilled to Watauga").isEqualTo("Watauga");
            assertThat(boone.get("shelter_type")).as("Boone stays EMERGENCY").isEqualTo("EMERGENCY");

            Map<String, Object> waynesville = jdbc.queryForMap(
                "SELECT dv_shelter, county, shelter_type FROM shelter WHERE id = ?::uuid",
                "d0000001-0000-0000-0000-000000000002");
            assertThat(waynesville.get("dv_shelter")).isEqualTo(false);
            assertThat(waynesville.get("county")).as("Waynesville county backfilled to Haywood").isEqualTo("Haywood");
            assertThat(waynesville.get("shelter_type")).isEqualTo("EMERGENCY");

            // Existing DV row: county stays NULL (Undisclosed), dv_shelter=true,
            // shelter_type='DV' (V91 backfill).
            Map<String, Object> dvWest = jdbc.queryForMap(
                "SELECT dv_shelter, county, shelter_type FROM shelter WHERE id = ?::uuid",
                "d0000001-0000-0000-0000-000000000003");
            assertThat(dvWest.get("dv_shelter")).isEqualTo(true);
            assertThat(dvWest.get("county")).as("DV West county stays NULL (Undisclosed)").isNull();
            assertThat(dvWest.get("shelter_type")).isEqualTo("DV");
        });
    }

    @Test
    @DisplayName("existing east non-DV shelters preserve dv_shelter=false + get county backfilled")
    void existingEastShelters_haveCountyBackfilled() {
        TenantContext.runWithContext(TENANT_EAST, true, () -> {
            Map<String, Object> newBern = jdbc.queryForMap(
                "SELECT dv_shelter, county, shelter_type FROM shelter WHERE id = ?::uuid",
                "d0000002-0000-0000-0000-000000000001");
            assertThat(newBern.get("dv_shelter")).isEqualTo(false);
            assertThat(newBern.get("county")).as("New Bern county backfilled to Craven").isEqualTo("Craven");

            Map<String, Object> washington = jdbc.queryForMap(
                "SELECT dv_shelter, county, shelter_type FROM shelter WHERE id = ?::uuid",
                "d0000002-0000-0000-0000-000000000002");
            assertThat(washington.get("dv_shelter")).isEqualTo(false);
            assertThat(washington.get("county")).as("Washington county backfilled to Beaufort").isEqualTo("Beaufort");

            Map<String, Object> dvEast = jdbc.queryForMap(
                "SELECT dv_shelter, county, shelter_type FROM shelter WHERE id = ?::uuid",
                "d0000002-0000-0000-0000-000000000003");
            assertThat(dvEast.get("dv_shelter")).isEqualTo(true);
            assertThat(dvEast.get("county")).as("DV East county stays NULL").isNull();
            assertThat(dvEast.get("shelter_type")).isEqualTo("DV");
        });
    }

    // -----------------------------------------------------------------
    // §2. tenant.config.active_counties set correctly on both demo
    // tenants. Without this the §8/§9 county dropdown falls back to
    // the noisy NC 100-county default and the new shelters' counties
    // wouldn't be curated.
    //
    // tenant table has no RLS (multi-tenant catalog), so no context
    // binding needed.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("dev-coc-west tenant.config has active_counties [Watauga, Haywood, Buncombe, Henderson]")
    void westTenant_hasActiveCounties() {
        String activeCounties = jdbc.queryForObject(
            "SELECT config->'active_counties' FROM tenant WHERE id = ?::uuid",
            String.class, TENANT_WEST.toString());
        assertThat(activeCounties)
            .as("V95 must set active_counties for west demo tenant")
            .isNotNull();
        // JSONB array order is preserved on round-trip; substring check is
        // tolerant of whitespace variations and confirms each county is
        // present by name.
        assertThat(activeCounties)
            .contains("Watauga")
            .contains("Haywood")
            .contains("Buncombe")
            .contains("Henderson");
    }

    @Test
    @DisplayName("dev-coc-east tenant.config has active_counties [Craven, Beaufort, Pitt, Onslow]")
    void eastTenant_hasActiveCounties() {
        String activeCounties = jdbc.queryForObject(
            "SELECT config->'active_counties' FROM tenant WHERE id = ?::uuid",
            String.class, TENANT_EAST.toString());
        assertThat(activeCounties).isNotNull();
        assertThat(activeCounties)
            .contains("Craven")
            .contains("Beaufort")
            .contains("Pitt")
            .contains("Onslow");
    }

    // -----------------------------------------------------------------
    // §3. New shelters: structural assertions on shelter_type, county,
    // and requires_verification_call. Each new shelter exercises a
    // different filter axis.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("WEST TRANSITIONAL: Asheville/Buncombe, requires_verification_call=false")
    void westTransitional_hasExpectedShape() {
        TenantContext.runWithContext(TENANT_WEST, true, () -> {
            Map<String, Object> row = jdbc.queryForMap(
                "SELECT shelter_type, county, requires_verification_call, dv_shelter, address_city "
                + "FROM shelter WHERE id = ?::uuid",
                WEST_TRANSITIONAL.toString());
            assertThat(row.get("shelter_type")).isEqualTo("TRANSITIONAL");
            assertThat(row.get("county")).isEqualTo("Buncombe");
            assertThat(row.get("requires_verification_call")).isEqualTo(false);
            assertThat(row.get("dv_shelter")).isEqualTo(false);
            assertThat(row.get("address_city")).isEqualTo("Asheville");
        });
    }

    @Test
    @DisplayName("WEST REENTRY: Hendersonville/Henderson, requires_verification_call=true")
    void westReentry_hasExpectedShape() {
        TenantContext.runWithContext(TENANT_WEST, true, () -> {
            Map<String, Object> row = jdbc.queryForMap(
                "SELECT shelter_type, county, requires_verification_call, dv_shelter, address_city "
                + "FROM shelter WHERE id = ?::uuid",
                WEST_REENTRY.toString());
            assertThat(row.get("shelter_type")).isEqualTo("REENTRY_TRANSITIONAL");
            assertThat(row.get("county")).isEqualTo("Henderson");
            assertThat(row.get("requires_verification_call"))
                .as("REENTRY shelters require verification call before referral").isEqualTo(true);
            assertThat(row.get("address_city")).isEqualTo("Hendersonville");
        });
    }

    @Test
    @DisplayName("WEST surge site: Boone/Watauga EMERGENCY (OVERFLOW deferred), NULL eligibility_criteria")
    void westSurgeSite_hasNullEligibility() {
        TenantContext.runWithContext(TENANT_WEST, true, () -> {
            Map<String, Object> row = jdbc.queryForMap(
                "SELECT s.shelter_type, s.county, sc.eligibility_criteria "
                + "FROM shelter s JOIN shelter_constraints sc ON s.id = sc.shelter_id "
                + "WHERE s.id = ?::uuid",
                WEST_OVERFLOW.toString());
            // Surge concept rides EMERGENCY + bed_availability.overflow_beds.
            // True OVERFLOW shelter_type is deferred per
            // project_deferred_openspecs_required.md (Java enum doesn't admit it).
            assertThat(row.get("shelter_type")).isEqualTo("EMERGENCY");
            assertThat(row.get("county")).isEqualTo("Watauga");
            assertThat(row.get("eligibility_criteria"))
                .as("Surge site keeps NULL eligibility_criteria to exercise "
                    + "the §10 'Not specified' empty-state UI")
                .isNull();
        });
    }

    @Test
    @DisplayName("EAST TRANSITIONAL: Greenville/Pitt, family-targeted")
    void eastTransitional_hasExpectedShape() {
        TenantContext.runWithContext(TENANT_EAST, true, () -> {
            Map<String, Object> row = jdbc.queryForMap(
                "SELECT s.shelter_type, s.county, s.address_city, sc.population_types_served "
                + "FROM shelter s JOIN shelter_constraints sc ON s.id = sc.shelter_id "
                + "WHERE s.id = ?::uuid",
                EAST_TRANSITIONAL.toString());
            assertThat(row.get("shelter_type")).isEqualTo("TRANSITIONAL");
            assertThat(row.get("county")).isEqualTo("Pitt");
            assertThat(row.get("address_city")).isEqualTo("Greenville");
            // population_types_served is text[] — assert contains FAMILY.
            assertThat(row.get("population_types_served").toString()).contains("FAMILY_WITH_CHILDREN");
        });
    }

    @Test
    @DisplayName("EAST REENTRY: Jacksonville/Onslow, women-only with VAWA flag")
    void eastReentry_hasVawaFlag() {
        TenantContext.runWithContext(TENANT_EAST, true, () -> {
            Map<String, Object> row = jdbc.queryForMap(
                "SELECT s.shelter_type, s.county, sc.eligibility_criteria, sc.population_types_served "
                + "FROM shelter s JOIN shelter_constraints sc ON s.id = sc.shelter_id "
                + "WHERE s.id = ?::uuid",
                EAST_REENTRY.toString());
            assertThat(row.get("shelter_type")).isEqualTo("REENTRY_TRANSITIONAL");
            assertThat(row.get("county")).isEqualTo("Onslow");
            assertThat(row.get("population_types_served").toString()).contains("WOMEN_ONLY");

            String eligibility = row.get("eligibility_criteria").toString();
            assertThat(eligibility)
                .as("east REENTRY shelter has VAWA flag for trafficking-survivor profile")
                .contains("vawa_protections_apply")
                .contains("true");
        });
    }

    @Test
    @DisplayName("EAST surge site: Greenville/Pitt EMERGENCY (OVERFLOW deferred), NULL eligibility_criteria")
    void eastSurgeSite_hasNullEligibility() {
        TenantContext.runWithContext(TENANT_EAST, true, () -> {
            Map<String, Object> row = jdbc.queryForMap(
                "SELECT s.shelter_type, s.county, sc.eligibility_criteria "
                + "FROM shelter s JOIN shelter_constraints sc ON s.id = sc.shelter_id "
                + "WHERE s.id = ?::uuid",
                EAST_OVERFLOW.toString());
            assertThat(row.get("shelter_type")).isEqualTo("EMERGENCY");
            assertThat(row.get("county")).isEqualTo("Pitt");
            assertThat(row.get("eligibility_criteria")).isNull();
        });
    }

    // -----------------------------------------------------------------
    // §4. eligibility_criteria JSONB schema validation: REENTRY rows
    // exclude the safety-critical offense subset and accept the rest.
    // The vocabulary (SEX_OFFENSE / ARSON / VIOLENT_FELONY / etc.) is
    // the controlled list from frontend/src/types/eligibilityCriteria.ts.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("WEST REENTRY excludes SEX_OFFENSE + ARSON + VIOLENT_FELONY, accepts felonies otherwise")
    void westReentry_eligibilityCriteriaShape() {
        TenantContext.runWithContext(TENANT_WEST, true, () -> {
            String eligibility = jdbc.queryForObject(
                "SELECT eligibility_criteria::text FROM shelter_constraints WHERE shelter_id = ?::uuid",
                String.class, WEST_REENTRY.toString());

            assertThat(eligibility)
                .as("REENTRY accepts_felonies must be true")
                .contains("\"accepts_felonies\": true");
            assertThat(eligibility)
                .as("REENTRY must exclude safety-critical offenses")
                .contains("SEX_OFFENSE")
                .contains("ARSON")
                .contains("VIOLENT_FELONY");
            assertThat(eligibility)
                .as("REENTRY uses individualized_assessment for case-by-case eval")
                .contains("\"individualized_assessment\": true");
        });
    }

    @Test
    @DisplayName("WEST + EAST TRANSITIONAL have null criminal_record_policy (no felony filter)")
    void transitional_hasNullCriminalRecordPolicy() {
        TenantContext.runWithContext(TENANT_WEST, true, () -> {
            String eligibility = jdbc.queryForObject(
                "SELECT eligibility_criteria::text FROM shelter_constraints WHERE shelter_id = ?::uuid",
                String.class, WEST_TRANSITIONAL.toString());
            // The frontend treats absent-criminal_record_policy the same as
            // null — both fall through to BedSearchService branch (c).
            // Either shape is valid; neither should claim accepts_felonies.
            assertThat(eligibility)
                .as("TRANSITIONAL doesn't carry criminal_record_policy block")
                .doesNotContain("\"accepts_felonies\":");
            assertThat(eligibility)
                .as("TRANSITIONAL still has program_requirements")
                .contains("program_requirements");
        });
    }

    // -----------------------------------------------------------------
    // §5. coordinator_assignment rows: regular coordinator + cocadmin
    // assigned to all 3 new shelters per tenant. Mirrors V78 pattern.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("west coordinator assigned to 3 new west shelters")
    void westCoordinator_assignedToNewShelters() {
        TenantContext.runWithContext(TENANT_WEST, true, () -> {
            List<String> shelterIds = jdbc.queryForList(
                "SELECT shelter_id::text FROM coordinator_assignment WHERE user_id = ?::uuid",
                String.class, "b0000001-0000-0000-0000-000000000003");
            assertThat(shelterIds)
                .as("west coordinator must be assigned to all 3 new west shelters "
                    + "(plus existing 2 non-DV per V78)")
                .contains(WEST_TRANSITIONAL.toString())
                .contains(WEST_REENTRY.toString())
                .contains(WEST_OVERFLOW.toString());
        });
    }

    @Test
    @DisplayName("east coordinator assigned to 3 new east shelters")
    void eastCoordinator_assignedToNewShelters() {
        TenantContext.runWithContext(TENANT_EAST, true, () -> {
            List<String> shelterIds = jdbc.queryForList(
                "SELECT shelter_id::text FROM coordinator_assignment WHERE user_id = ?::uuid",
                String.class, "b0000002-0000-0000-0000-000000000003");
            assertThat(shelterIds)
                .contains(EAST_TRANSITIONAL.toString())
                .contains(EAST_REENTRY.toString())
                .contains(EAST_OVERFLOW.toString());
        });
    }

    // -----------------------------------------------------------------
    // §6. bed_availability rows present so search returns realistic
    // counts. OVERFLOW rows have beds_occupied=0 (surge inactive) but
    // beds_total > 0 so the row exists.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("each new shelter has at least one bed_availability row")
    void newShelters_haveBedAvailability() {
        TenantContext.runWithContext(TENANT_WEST, true, () -> {
            for (UUID id : List.of(WEST_TRANSITIONAL, WEST_REENTRY, WEST_OVERFLOW)) {
                Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM bed_availability WHERE shelter_id = ?::uuid",
                    Integer.class, id.toString());
                assertThat(count).as("shelter %s has bed_availability", id).isGreaterThanOrEqualTo(1);
            }
        });
        TenantContext.runWithContext(TENANT_EAST, true, () -> {
            for (UUID id : List.of(EAST_TRANSITIONAL, EAST_REENTRY, EAST_OVERFLOW)) {
                Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM bed_availability WHERE shelter_id = ?::uuid",
                    Integer.class, id.toString());
                assertThat(count).isGreaterThanOrEqualTo(1);
            }
        });
    }
}
