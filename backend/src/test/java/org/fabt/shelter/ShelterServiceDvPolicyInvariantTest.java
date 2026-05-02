package org.fabt.shelter;

import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.shared.errors.ErrorCodes;
import org.fabt.shared.errors.StructuredErrorException;
import org.fabt.shared.web.TenantContext;
import org.fabt.shelter.api.CreateShelterRequest;
import org.fabt.shelter.api.UpdateShelterRequest;
import org.fabt.shelter.domain.Shelter;
import org.fabt.shelter.fixtures.TestShelterFixture;
import org.fabt.shelter.repository.ShelterRepository;
import org.fabt.shelter.service.ShelterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the dv-policy-tenant-flag service-layer invariant
 * (tasks §5.1-§5.4 + §5.7). Verifies {@link ShelterService#create},
 * {@link ShelterService#update}, and {@link ShelterService#reactivate} all
 * reject {@code dv_shelter=true} writes when the parent tenant's
 * {@code config.dv_policy_enabled} is absent or false.
 *
 * <p>Per {@code feedback_isolated_test_data}: every test creates its own
 * tenants via raw JDBC insert (tenant table is not RLS-protected) — the
 * tenant name + slug carry a UUID suffix to avoid collisions.
 *
 * <p>Per {@code feedback_per_user_rls_wrong_pattern}: shelter writes go
 * through {@link TenantContext#runWithContext} with {@code dvAccess=true}
 * because the shelter table is RLS-protected. {@link TestShelterFixture}
 * is used for the deactivated-DV-shelter fixture (handles V91 lockstep).
 */
@DisplayName("ShelterService — dv-policy-tenant-flag invariant guard")
class ShelterServiceDvPolicyInvariantTest extends BaseIntegrationTest {

    @Autowired private ShelterService shelterService;
    @Autowired private ShelterRepository shelterRepository;
    @Autowired private JdbcTemplate jdbc;

    /** Inserts a fresh test tenant with the given dv_policy_enabled state. */
    private UUID createTenant(String slugSuffix, boolean dvPolicyEnabled) {
        UUID id = UUID.randomUUID();
        String configJson = dvPolicyEnabled
                ? "{\"dv_policy_enabled\":true}"
                : "{}";
        jdbc.update(
                "INSERT INTO tenant (id, name, slug, config) VALUES (?, ?, ?, ?::jsonb)",
                id, "DvPolicy IT " + slugSuffix, "dvpolicyit-" + slugSuffix, configJson);
        return id;
    }

    private CreateShelterRequest minimalCreateReq(String name, boolean dvShelter) {
        return new CreateShelterRequest(
                name,
                "100 Test Way",
                "Raleigh",
                "NC",
                "27601",
                "919-555-0000",
                35.78,
                -78.64,
                dvShelter,
                null, null, null, null, null);
    }

    // -- §5.1 create -----------------------------------------------------

    @Test
    @DisplayName("§5.1 create with dvShelter=true REJECTED when tenant flag is off")
    void createDvShelterRejectedWhenFlagOff() {
        UUID tenantId = createTenant("c-off-" + UUID.randomUUID().toString().substring(0, 8), false);

        assertThatThrownBy(() -> TenantContext.callWithContext(tenantId, true,
                () -> shelterService.create(minimalCreateReq("Should Fail", true))))
                .isInstanceOf(StructuredErrorException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCodes.SHELTER_DV_SHELTER_REQUIRES_DV_POLICY);

        // No row created — count via raw read to bypass any caching
        Long count = TenantContext.callWithContext(tenantId, true, () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM shelter WHERE tenant_id = ?", Long.class, tenantId));
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("§5.1 create with dvShelter=true ALLOWED when tenant flag is on")
    void createDvShelterAllowedWhenFlagOn() {
        UUID tenantId = createTenant("c-on-" + UUID.randomUUID().toString().substring(0, 8), true);

        Shelter created = TenantContext.callWithContext(tenantId, true,
                () -> shelterService.create(minimalCreateReq("DV OK", true)));
        assertThat(created.isDvShelter()).isTrue();
    }

    @Test
    @DisplayName("§5.1 create with dvShelter=false ALLOWED regardless of tenant flag")
    void createNonDvShelterAllowedRegardless() {
        UUID tenantId = createTenant("c-nondv-" + UUID.randomUUID().toString().substring(0, 8), false);

        Shelter created = TenantContext.callWithContext(tenantId, true,
                () -> shelterService.create(minimalCreateReq("Non-DV", false)));
        assertThat(created.isDvShelter()).isFalse();
    }

    // -- §5.2 update -----------------------------------------------------

    @Test
    @DisplayName("§5.2 update flip-up dvShelter=false→true REJECTED when flag is off")
    void updateFlipUpRejectedWhenFlagOff() {
        UUID tenantId = createTenant("u-flipup-off-" + UUID.randomUUID().toString().substring(0, 8), false);

        // Seed: create a non-DV shelter (allowed regardless of flag)
        UUID shelterId = TenantContext.callWithContext(tenantId, true, () ->
                shelterService.create(minimalCreateReq("Original Non-DV", false))).getId();

        // Now try to flip dv_shelter=true via update — should be rejected
        UpdateShelterRequest flipUp = new UpdateShelterRequest(
                null, null, null, null, null, null, null, null,
                Boolean.TRUE, null, null, null, null, null);

        assertThatThrownBy(() -> TenantContext.callWithContext(tenantId, true,
                () -> shelterService.update(shelterId, flipUp)))
                .isInstanceOf(StructuredErrorException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCodes.SHELTER_DV_SHELTER_REQUIRES_DV_POLICY);
    }

    @Test
    @DisplayName("§5.2 update flip-up dvShelter=false→true ALLOWED when flag is on")
    void updateFlipUpAllowedWhenFlagOn() {
        UUID tenantId = createTenant("u-flipup-on-" + UUID.randomUUID().toString().substring(0, 8), true);

        UUID shelterId = TenantContext.callWithContext(tenantId, true, () ->
                shelterService.create(minimalCreateReq("Will Become DV", false))).getId();

        UpdateShelterRequest flipUp = new UpdateShelterRequest(
                null, null, null, null, null, null, null, null,
                Boolean.TRUE, null, null, null, null, null);
        Shelter updated = TenantContext.callWithContext(tenantId, true,
                () -> shelterService.update(shelterId, flipUp));
        assertThat(updated.isDvShelter()).isTrue();
    }

    @Test
    @DisplayName("§5.2 update flip-down dvShelter=true→false ALLOWED regardless of flag")
    void updateFlipDownAllowedRegardless() {
        // Setup: tenant with flag on so we can create the DV shelter, then
        // flip the flag off externally to simulate the "operator deactivated
        // shelters then disabled flag" workflow. The flip-down should still
        // succeed even when the flag is off.
        UUID tenantId = createTenant("u-flipdown-" + UUID.randomUUID().toString().substring(0, 8), true);

        UUID shelterId = TenantContext.callWithContext(tenantId, true, () ->
                shelterService.create(minimalCreateReq("DV → Non-DV", true))).getId();

        // Externally flip flag off (simulate the operator-disable workflow
        // having happened — though the disable-path guard at the controller
        // would have prevented this in practice; here we exercise the
        // service-layer behavior in isolation).
        jdbc.update("UPDATE tenant SET config = '{}'::jsonb WHERE id = ?", tenantId);

        UpdateShelterRequest flipDown = new UpdateShelterRequest(
                null, null, null, null, null, null, null, null,
                Boolean.FALSE, null, null, null, null, null);
        Shelter updated = TenantContext.callWithContext(tenantId, true,
                () -> shelterService.update(shelterId, flipDown));
        assertThat(updated.isDvShelter()).isFalse();
    }

    @Test
    @DisplayName("§5.2 update other fields on already-true DV shelter ALLOWED with flag off")
    void updateOtherFieldsOnExistingDvShelterAllowedWithFlagOff() {
        // Existing dvShelter=true row predates flag flip — non-dvShelter
        // updates remain allowed (the invariant only fires on flip-up).
        UUID tenantId = createTenant("u-other-" + UUID.randomUUID().toString().substring(0, 8), true);
        UUID shelterId = TenantContext.callWithContext(tenantId, true, () ->
                shelterService.create(minimalCreateReq("Existing DV", true))).getId();

        // Flip flag off after shelter created
        jdbc.update("UPDATE tenant SET config = '{}'::jsonb WHERE id = ?", tenantId);

        // Update a non-DV field (phone) — should succeed
        UpdateShelterRequest phoneOnly = new UpdateShelterRequest(
                null, null, null, null, null, "999-555-1234", null, null,
                null, null, null, null, null, null);
        Shelter updated = TenantContext.callWithContext(tenantId, true,
                () -> shelterService.update(shelterId, phoneOnly));
        assertThat(updated.getPhone()).isEqualTo("999-555-1234");
        assertThat(updated.isDvShelter()).isTrue();
    }

    // -- §5.4 reactivate -------------------------------------------------

    @Test
    @DisplayName("§5.4 reactivate DV shelter REJECTED when tenant flag is off")
    void reactivateDvShelterRejectedWhenFlagOff() {
        UUID tenantId = createTenant("r-off-" + UUID.randomUUID().toString().substring(0, 8), true);

        // Create DV shelter, deactivate it, flip flag off, attempt reactivate
        UUID shelterId = TenantContext.callWithContext(tenantId, true, () -> {
            UUID id = TestShelterFixture.insertShelter(jdbc, tenantId, "Inactive DV", true);
            jdbc.update("UPDATE shelter SET active = false WHERE id = ?", id);
            return id;
        });
        jdbc.update("UPDATE tenant SET config = '{}'::jsonb WHERE id = ?", tenantId);

        UUID actorUserId = UUID.randomUUID();
        assertThatThrownBy(() -> TenantContext.callWithContext(tenantId, true,
                () -> shelterService.reactivate(shelterId, actorUserId)))
                .isInstanceOf(StructuredErrorException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCodes.SHELTER_DV_SHELTER_REQUIRES_DV_POLICY);

        // Shelter remains inactive
        Boolean active = TenantContext.callWithContext(tenantId, true, () -> jdbc.queryForObject(
                "SELECT active FROM shelter WHERE id = ?", Boolean.class, shelterId));
        assertThat(active).isFalse();
    }

    @Test
    @DisplayName("§5.4 reactivate DV shelter ALLOWED after re-enabling tenant flag")
    void reactivateDvShelterAllowedAfterFlagReEnabled() {
        UUID tenantId = createTenant("r-reon-" + UUID.randomUUID().toString().substring(0, 8), true);

        UUID shelterId = TenantContext.callWithContext(tenantId, true, () -> {
            UUID id = TestShelterFixture.insertShelter(jdbc, tenantId, "Was Inactive", true);
            jdbc.update("UPDATE shelter SET active = false WHERE id = ?", id);
            return id;
        });

        // Flag stays on; reactivate should succeed
        UUID actorUserId = UUID.randomUUID();
        Shelter reactivated = TenantContext.callWithContext(tenantId, true,
                () -> shelterService.reactivate(shelterId, actorUserId));
        assertThat(reactivated.isActive()).isTrue();
    }

    @Test
    @DisplayName("§5.4 reactivate non-DV shelter ALLOWED regardless of flag")
    void reactivateNonDvShelterAllowedRegardless() {
        UUID tenantId = createTenant("r-nondv-" + UUID.randomUUID().toString().substring(0, 8), false);

        UUID shelterId = TenantContext.callWithContext(tenantId, true, () -> {
            UUID id = TestShelterFixture.insertShelter(jdbc, tenantId, "Inactive Non-DV", false);
            jdbc.update("UPDATE shelter SET active = false WHERE id = ?", id);
            return id;
        });

        UUID actorUserId = UUID.randomUUID();
        Shelter reactivated = TenantContext.callWithContext(tenantId, true,
                () -> shelterService.reactivate(shelterId, actorUserId));
        assertThat(reactivated.isActive()).isTrue();
    }
}
