package org.fabt.shelter;

import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.shared.web.TenantContext;
import org.fabt.shelter.api.CreateShelterRequest;
import org.fabt.shelter.api.UpdateShelterRequest;
import org.fabt.shelter.domain.Shelter;
import org.fabt.shelter.domain.ShelterType;
import org.fabt.shelter.repository.ShelterRepository;
import org.fabt.shelter.service.ShelterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ShelterService lockstep contract — task 4.3 partial / warroom H2.
 *
 * <p>The V91 CHECK constraint ({@code dv_shelter=TRUE → shelter_type='DV'})
 * is the load-bearing invariant. {@link ShelterService#create} and
 * {@link ShelterService#update} are responsible for keeping the two fields in
 * lockstep so the constraint can never reject a service-layer write. Without
 * these tests, the lockstep code is asserted only by the absence of failure
 * elsewhere — a future regression that removes the {@code setShelterType}
 * call would either trip the constraint with a confusing error
 * ({@code DataIntegrityViolationException} instead of a clear contract
 * failure) or — worse, on the {@code update} path — silently produce a
 * divergent in-memory entity that gets reconciled only on the next read.
 *
 * <p>These tests assert the lockstep at the contract layer:
 * <ol>
 *   <li>Service write actually persists the matching {@code shelter_type}
 *       to DB (verified via raw JDBC bypassing the cache).</li>
 *   <li>Update lockstep flips both directions (DV → non-DV and non-DV → DV).</li>
 * </ol>
 *
 * <p>Tests use {@link TenantContext#runWithContext} because
 * {@link ShelterService} reads {@code TenantContext.getTenantId()} for
 * tenant scoping (see service line 141 + 223). Without this, the service
 * would throw {@link NullPointerException} or silently bypass tenant scope.
 */
class ShelterServiceLockstepTest extends BaseIntegrationTest {

    @Autowired
    private ShelterService shelterService;

    @Autowired
    private ShelterRepository shelterRepository;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Creates a tenant directly via JDBC (tenant table has no RLS) and binds
     * the test's TenantContext to it. ShelterService.create() reads the
     * tenantId from context.
     */
    private UUID createTenant(String slugSuffix) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO tenant (id, name, slug, config) VALUES (?, ?, ?, '{}'::jsonb)",
            id, "Lockstep Test Tenant " + slugSuffix, "lockstep-" + slugSuffix);
        return id;
    }

    private CreateShelterRequest minimalCreateReq(String name, boolean dv) {
        return new CreateShelterRequest(
            name,
            "100 Test Way",
            "Raleigh",
            "NC",
            "27601",
            "919-555-0000",
            35.78,
            -78.64,
            dv,
            null,   // constraints
            null,   // capacities
            null,   // county
            null    // requiresVerificationCall
        );
    }

    @Test
    void create_with_dvShelter_true_persists_shelter_type_DV_to_database() {
        UUID tenantId = createTenant("create-dv-" + UUID.randomUUID().toString().substring(0, 8));

        Shelter created = TenantContext.callWithContext(tenantId, true,
            () -> shelterService.create(minimalCreateReq("DV Lockstep Shelter", true)));

        // Direct DB read bypasses any caching; the constraint guarantees this
        // can only succeed if shelter_type='DV' was actually written. But we
        // assert the value explicitly so a future cache-of-truth swap can't
        // hide a regression.
        String dbShelterType = TenantContext.callWithContext(tenantId, true, () ->
            jdbc.queryForObject(
                "SELECT shelter_type FROM shelter WHERE id = ?",
                String.class, created.getId()));

        assertThat(dbShelterType)
            .as("ShelterService.create with dvShelter=true MUST write shelter_type='DV' to DB "
                + "(per V91 lockstep contract; without this, the V91 CHECK would reject the INSERT)")
            .isEqualTo("DV");
    }

    @Test
    void create_with_dvShelter_false_persists_shelter_type_EMERGENCY_to_database() {
        UUID tenantId = createTenant("create-em-" + UUID.randomUUID().toString().substring(0, 8));

        Shelter created = TenantContext.callWithContext(tenantId, true,
            () -> shelterService.create(minimalCreateReq("Regular Lockstep Shelter", false)));

        String dbShelterType = TenantContext.callWithContext(tenantId, true, () ->
            jdbc.queryForObject(
                "SELECT shelter_type FROM shelter WHERE id = ?",
                String.class, created.getId()));

        assertThat(dbShelterType)
            .as("ShelterService.create with dvShelter=false MUST write shelter_type='EMERGENCY' to DB")
            .isEqualTo("EMERGENCY");
    }

    @Test
    void update_flipping_dvShelter_false_to_true_flips_shelter_type_to_DV() {
        UUID tenantId = createTenant("update-to-dv-" + UUID.randomUUID().toString().substring(0, 8));

        Shelter created = TenantContext.callWithContext(tenantId, true,
            () -> shelterService.create(minimalCreateReq("Will Become DV", false)));

        // Pre-condition: starts as EMERGENCY
        String preType = TenantContext.callWithContext(tenantId, true, () ->
            jdbc.queryForObject("SELECT shelter_type FROM shelter WHERE id = ?",
                String.class, created.getId()));
        assertThat(preType).isEqualTo("EMERGENCY");

        // UpdateShelterRequest record signature: (name, addressStreet, addressCity, addressState,
        // addressZip, phone, latitude, longitude, dvShelter Boolean, constraints, capacities,
        // county, requiresVerificationCall, eligibilityCriteria)
        UpdateShelterRequest req = new UpdateShelterRequest(
            null, null, null, null, null, null, null, null,
            Boolean.TRUE,  // flip dvShelter to true
            null, null,
            null, null);  // county / requiresVerificationCall / eligibilityCriteria

        TenantContext.runWithContext(tenantId, true, () ->
            shelterService.update(created.getId(), req));

        String postType = TenantContext.callWithContext(tenantId, true, () ->
            jdbc.queryForObject("SELECT shelter_type FROM shelter WHERE id = ?",
                String.class, created.getId()));

        assertThat(postType)
            .as("ShelterService.update flipping dvShelter false→true MUST also flip shelter_type to 'DV' "
                + "(without lockstep, the V91 CHECK would reject the UPDATE)")
            .isEqualTo("DV");
    }

    @Test
    void update_flipping_dvShelter_true_to_false_flips_shelter_type_to_EMERGENCY() {
        UUID tenantId = createTenant("update-to-em-" + UUID.randomUUID().toString().substring(0, 8));

        Shelter created = TenantContext.callWithContext(tenantId, true,
            () -> shelterService.create(minimalCreateReq("Will Become EMERGENCY", true)));

        String preType = TenantContext.callWithContext(tenantId, true, () ->
            jdbc.queryForObject("SELECT shelter_type FROM shelter WHERE id = ?",
                String.class, created.getId()));
        assertThat(preType).isEqualTo("DV");

        UpdateShelterRequest req = new UpdateShelterRequest(
            null, null, null, null, null, null, null, null,
            Boolean.FALSE,  // flip dvShelter to false
            null, null,
            null, null);

        TenantContext.runWithContext(tenantId, true, () ->
            shelterService.update(created.getId(), req));

        String postType = TenantContext.callWithContext(tenantId, true, () ->
            jdbc.queryForObject("SELECT shelter_type FROM shelter WHERE id = ?",
                String.class, created.getId()));

        assertThat(postType)
            .as("ShelterService.update flipping dvShelter true→false MUST flip shelter_type to 'EMERGENCY'")
            .isEqualTo("EMERGENCY");
    }

    @Test
    void update_with_null_dvShelter_preserves_existing_shelter_type() {
        // Critical: when an UPDATE doesn't touch the dvShelter field, the
        // lockstep code is intentionally NOT triggered. The existing
        // shelter_type must be preserved through Spring Data JDBC's full-
        // entity UPDATE (which writes shelter_type back from the loaded
        // value, not from null).
        UUID tenantId = createTenant("update-passthrough-" + UUID.randomUUID().toString().substring(0, 8));

        Shelter created = TenantContext.callWithContext(tenantId, true,
            () -> shelterService.create(minimalCreateReq("DV Passthrough", true)));

        // Update only the name — leave dvShelter null
        UpdateShelterRequest req = new UpdateShelterRequest(
            "DV Passthrough Renamed",
            null, null, null, null, null, null, null,
            null,  // dvShelter omitted
            null, null,
            null, null);

        TenantContext.runWithContext(tenantId, true, () ->
            shelterService.update(created.getId(), req));

        String postType = TenantContext.callWithContext(tenantId, true, () ->
            jdbc.queryForObject("SELECT shelter_type FROM shelter WHERE id = ?",
                String.class, created.getId()));

        assertThat(postType)
            .as("ShelterService.update with null dvShelter MUST preserve existing shelter_type "
                + "(if Spring Data JDBC ever switches to dirty-only writes that drop the field, "
                + "this test catches the silent reset-to-EMERGENCY regression)")
            .isEqualTo("DV");
    }
}
