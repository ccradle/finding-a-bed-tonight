package org.fabt.shelter;

import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.shared.web.TenantContext;
import org.fabt.shelter.api.ShelterResponse;
import org.fabt.tenant.domain.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class UserShelterControllerTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private HttpHeaders adminHeaders;
    private User coordinator;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        adminHeaders = authHelper.adminHeaders();
        coordinator = authHelper.setupCoordinatorUser();

        // Clean up any prior assignments for this coordinator to avoid test pollution
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            jdbcTemplate.update("DELETE FROM coordinator_assignment WHERE user_id = ?",
                    coordinator.getId());
        });
    }

    // =========================================================================
    // Positive Tests
    // =========================================================================

    @Test
    @DisplayName("T-2: Coordinator with 2 assigned shelters returns 2 shelter objects")
    void getUserShelters_coordinatorWith2Shelters_returns2() {
        UUID shelter1 = createShelter("Shelter Alpha");
        UUID shelter2 = createShelter("Shelter Beta");

        assignCoordinator(shelter1, coordinator.getId());
        assignCoordinator(shelter2, coordinator.getId());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/" + coordinator.getId() + "/shelters",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Shelter Alpha");
        assertThat(response.getBody()).contains("Shelter Beta");
        // Ordered by name
        assertThat(response.getBody().indexOf("Shelter Alpha"))
                .isLessThan(response.getBody().indexOf("Shelter Beta"));
    }

    @Test
    @DisplayName("T-3: User with no assignments returns empty array")
    void getUserShelters_noAssignments_returnsEmpty() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/" + coordinator.getId() + "/shelters",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("[]");
    }

    // =========================================================================
    // Authorization Tests
    // =========================================================================

    @Test
    @DisplayName("Outreach worker cannot access user shelters endpoint")
    void getUserShelters_asOutreachWorker_returns403() {
        authHelper.setupOutreachWorkerUser();
        HttpHeaders outreachHeaders = authHelper.outreachWorkerHeaders();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/" + coordinator.getId() + "/shelters",
                HttpMethod.GET,
                new HttpEntity<>(outreachHeaders),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // =========================================================================
    // Cross-Tenant Isolation (Marcus Webb — security review)
    // =========================================================================

    @Test
    @DisplayName("Cross-tenant: admin cannot see another tenant's coordinator assignments")
    void getUserShelters_crossTenant_returnsEmpty() {
        // Setup: Tenant B with a coordinator assigned to a shelter
        Tenant tenantB = authHelper.setupTestTenant("tenant-b-shelter-isolation");
        User tenantBAdmin = authHelper.setupUserWithDvAccess(
                "admin-b-shelter@test.fabt.org", "Tenant B Admin",
                new String[]{"PLATFORM_ADMIN"});
        HttpHeaders tenantBHeaders = authHelper.headersForUser(tenantBAdmin);

        UUID tenantBShelter = createShelterWithHeaders("Tenant B Shelter", tenantBHeaders);
        User tenantBCoord = authHelper.setupUserWithDvAccess(
                "coord-b-shelter@test.fabt.org", "Tenant B Coord",
                new String[]{"COORDINATOR"});
        assignCoordinatorWithHeaders(tenantBShelter, tenantBCoord.getId(), tenantBHeaders);

        // Switch back to tenant A and try to query tenant B's coordinator
        authHelper.setupTestTenant(); // reset to default tenant
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/" + tenantBCoord.getId() + "/shelters",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                String.class);

        // Tenant A admin should see empty results for tenant B's coordinator
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("[]");
    }

    // =========================================================================
    // Invalid Input Tests (Riley Cho — negative testing)
    // =========================================================================

    @Test
    @DisplayName("Non-existent user UUID returns empty array (not 404)")
    void getUserShelters_nonExistentUser_returnsEmpty() {
        UUID fakeUserId = UUID.randomUUID();
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/" + fakeUserId + "/shelters",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("[]");
    }

    @Test
    @DisplayName("Invalid UUID format returns 4xx or 5xx error")
    void getUserShelters_invalidUuid_returnsError() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/not-a-uuid/shelters",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                String.class);

        assertThat(response.getStatusCode().is4xxClientError() || response.getStatusCode().is5xxServerError())
                .describedAs("Invalid UUID should produce an error, got: %s", response.getStatusCode())
                .isTrue();
    }

    // =========================================================================
    // DV Shelter Visibility (Elena Vasquez — RLS review)
    // =========================================================================

    @Test
    @DisplayName("Admin with dvAccess sees DV shelter assignments")
    void getUserShelters_adminWithDvAccess_seesDvShelters() {
        User dvAdmin = authHelper.setupUserWithDvAccess(
                "dv-admin-shelter@test.fabt.org", "DV Admin",
                new String[]{"PLATFORM_ADMIN"});
        HttpHeaders dvAdminHeaders = authHelper.headersForUser(dvAdmin);

        // Create DV shelter (needs dvAccess admin)
        UUID dvShelterId = createDvShelterWithHeaders(dvAdminHeaders);

        User dvCoord = authHelper.setupUserWithDvAccess(
                "dv-coord-shelter@test.fabt.org", "DV Coordinator",
                new String[]{"COORDINATOR"});
        assignCoordinatorWithHeaders(dvShelterId, dvCoord.getId(), dvAdminHeaders);

        // Query as admin WITH dvAccess — should see the DV shelter
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/" + dvCoord.getId() + "/shelters",
                HttpMethod.GET,
                new HttpEntity<>(dvAdminHeaders),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("DV Shelter");
    }

    @Test
    @DisplayName("Admin without dvAccess does NOT see DV shelter assignments (RLS)")
    void getUserShelters_adminWithoutDvAccess_doesNotSeeDvShelters() {
        User dvAdmin = authHelper.setupUserWithDvAccess(
                "dv-admin-shelter2@test.fabt.org", "DV Admin 2",
                new String[]{"PLATFORM_ADMIN"});
        HttpHeaders dvAdminHeaders = authHelper.headersForUser(dvAdmin);

        // Create DV shelter (needs dvAccess admin)
        UUID dvShelterId = createDvShelterWithHeaders(dvAdminHeaders);

        User dvCoord = authHelper.setupUserWithDvAccess(
                "dv-coord-shelter2@test.fabt.org", "DV Coordinator 2",
                new String[]{"COORDINATOR"});
        assignCoordinatorWithHeaders(dvShelterId, dvCoord.getId(), dvAdminHeaders);

        // Query as admin WITHOUT dvAccess — RLS should hide the DV shelter
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/" + dvCoord.getId() + "/shelters",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),  // adminHeaders has dvAccess=false
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .describedAs("Admin without dvAccess should not see DV shelters due to RLS")
                .isEqualTo("[]");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UUID createShelter(String name) {
        return createShelterWithHeaders(name, adminHeaders);
    }

    private UUID createShelterWithHeaders(String name, HttpHeaders headers) {
        String body = String.format("""
                {
                  "name": "%s %s",
                  "addressStreet": "123 Test St",
                  "addressCity": "Raleigh",
                  "addressState": "NC",
                  "addressZip": "27601",
                  "phone": "919-555-%04d",
                  "dvShelter": false,
                  "constraints": { "populationTypesServed": ["SINGLE_ADULT"] },
                  "capacities": [{"populationType": "SINGLE_ADULT", "bedsTotal": 10}]
                }
                """, name, UUID.randomUUID().toString().substring(0, 8),
                (int) (Math.random() * 9999));

        ResponseEntity<ShelterResponse> resp = restTemplate.exchange(
                "/api/v1/shelters", HttpMethod.POST,
                new HttpEntity<>(body, headers),
                ShelterResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody().id();
    }

    private UUID createDvShelterWithHeaders(HttpHeaders headers) {
        String body = String.format("""
                {
                  "name": "DV Shelter %s",
                  "addressStreet": "456 Safe St",
                  "addressCity": "Raleigh",
                  "addressState": "NC",
                  "addressZip": "27601",
                  "phone": "919-555-%04d",
                  "dvShelter": true,
                  "constraints": { "populationTypesServed": ["DV_SURVIVOR"] },
                  "capacities": [{"populationType": "DV_SURVIVOR", "bedsTotal": 5}]
                }
                """, UUID.randomUUID().toString().substring(0, 8),
                (int) (Math.random() * 9999));

        ResponseEntity<ShelterResponse> resp = restTemplate.exchange(
                "/api/v1/shelters", HttpMethod.POST,
                new HttpEntity<>(body, headers),
                ShelterResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody().id();
    }

    private void assignCoordinator(UUID shelterId, UUID userId) {
        assignCoordinatorWithHeaders(shelterId, userId, adminHeaders);
    }

    private void assignCoordinatorWithHeaders(UUID shelterId, UUID userId, HttpHeaders headers) {
        String body = String.format("{\"userId\": \"%s\"}", userId);
        restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/coordinators",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);
    }
}
