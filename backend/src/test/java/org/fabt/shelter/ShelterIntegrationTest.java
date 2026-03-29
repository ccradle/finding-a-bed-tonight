package org.fabt.shelter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.shelter.api.ShelterDetailResponse;
import org.fabt.shelter.api.ShelterResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ShelterIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        authHelper.setupCocAdminUser();
        authHelper.setupCoordinatorUser();
        authHelper.setupOutreachWorkerUser();
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Creates a shelter via POST and returns the parsed response so callers can
     * grab the generated ID.
     */
    private ShelterResponse createTestShelter(HttpHeaders headers) {
        String body = """
                {
                    "name": "Test Shelter %s",
                    "addressStreet": "123 Main St",
                    "addressCity": "Raleigh",
                    "addressState": "NC",
                    "addressZip": "27601",
                    "phone": "919-555-0100",
                    "latitude": 35.7796,
                    "longitude": -78.6382,
                    "dvShelter": false,
                    "constraints": {
                        "sobrietyRequired": false,
                        "idRequired": false,
                        "referralRequired": false,
                        "petsAllowed": true,
                        "wheelchairAccessible": true,
                        "populationTypesServed": ["SINGLE_ADULT", "FAMILY_WITH_CHILDREN"]
                    },
                    "capacities": [
                        {"populationType": "SINGLE_ADULT", "bedsTotal": 20},
                        {"populationType": "FAMILY_WITH_CHILDREN", "bedsTotal": 10}
                    ]
                }
                """.formatted(UUID.randomUUID().toString().substring(0, 8));

        ResponseEntity<ShelterResponse> response = restTemplate.exchange(
                "/api/v1/shelters",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                ShelterResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void test_createShelter_asCocAdmin_returnsCreated() {
        HttpHeaders headers = authHelper.cocAdminHeaders();

        String body = """
                {
                    "name": "Downtown Emergency Shelter",
                    "addressStreet": "123 Main St",
                    "addressCity": "Raleigh",
                    "addressState": "NC",
                    "addressZip": "27601",
                    "phone": "919-555-0100",
                    "latitude": 35.7796,
                    "longitude": -78.6382,
                    "dvShelter": false,
                    "constraints": {
                        "sobrietyRequired": false,
                        "idRequired": false,
                        "referralRequired": false,
                        "petsAllowed": true,
                        "wheelchairAccessible": true,
                        "populationTypesServed": ["SINGLE_ADULT", "FAMILY_WITH_CHILDREN"]
                    },
                    "capacities": [
                        {"populationType": "SINGLE_ADULT", "bedsTotal": 20},
                        {"populationType": "FAMILY_WITH_CHILDREN", "bedsTotal": 10}
                    ]
                }
                """;

        ResponseEntity<ShelterResponse> response = restTemplate.exchange(
                "/api/v1/shelters",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                ShelterResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ShelterResponse shelter = response.getBody();
        assertThat(shelter).isNotNull();
        assertThat(shelter.id()).isNotNull();
        assertThat(shelter.name()).isEqualTo("Downtown Emergency Shelter");
        assertThat(shelter.addressStreet()).isEqualTo("123 Main St");
        assertThat(shelter.addressCity()).isEqualTo("Raleigh");
        assertThat(shelter.addressState()).isEqualTo("NC");
        assertThat(shelter.addressZip()).isEqualTo("27601");
        assertThat(shelter.phone()).isEqualTo("919-555-0100");
        assertThat(shelter.latitude()).isEqualTo(35.7796);
        assertThat(shelter.longitude()).isEqualTo(-78.6382);
        assertThat(shelter.dvShelter()).isFalse();
        assertThat(shelter.createdAt()).isNotNull();
    }

    @Test
    void test_createShelter_withConstraints() {
        HttpHeaders headers = authHelper.cocAdminHeaders();

        String body = """
                {
                    "name": "Constrained Shelter",
                    "addressStreet": "456 Elm St",
                    "addressCity": "Raleigh",
                    "addressState": "NC",
                    "addressZip": "27601",
                    "phone": "919-555-0200",
                    "latitude": 35.78,
                    "longitude": -78.64,
                    "dvShelter": false,
                    "constraints": {
                        "sobrietyRequired": true,
                        "idRequired": false,
                        "referralRequired": false,
                        "petsAllowed": false,
                        "wheelchairAccessible": true,
                        "populationTypesServed": ["SINGLE_ADULT", "VETERAN"]
                    },
                    "capacities": [
                        {"populationType": "SINGLE_ADULT", "bedsTotal": 15}
                    ]
                }
                """;

        ResponseEntity<ShelterResponse> createResponse = restTemplate.exchange(
                "/api/v1/shelters",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                ShelterResponse.class
        );

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID shelterId = createResponse.getBody().id();

        // Fetch the detail to verify constraints were saved
        ResponseEntity<ShelterDetailResponse> detailResponse = restTemplate.exchange(
                "/api/v1/shelters/" + shelterId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                ShelterDetailResponse.class
        );

        assertThat(detailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        ShelterDetailResponse detail = detailResponse.getBody();
        assertThat(detail).isNotNull();
        assertThat(detail.constraints()).isNotNull();
        assertThat(detail.constraints().sobrietyRequired()).isTrue();
        assertThat(detail.constraints().petsAllowed()).isFalse();
        assertThat(detail.constraints().wheelchairAccessible()).isTrue();
        assertThat(detail.constraints().populationTypesServed())
                .containsExactlyInAnyOrder("SINGLE_ADULT", "VETERAN");
    }

    @Test
    void test_getShelterDetail() {
        HttpHeaders headers = authHelper.cocAdminHeaders();
        ShelterResponse created = createTestShelter(headers);

        ResponseEntity<ShelterDetailResponse> response = restTemplate.exchange(
                "/api/v1/shelters/" + created.id(),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                ShelterDetailResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ShelterDetailResponse detail = response.getBody();
        assertThat(detail).isNotNull();
        assertThat(detail.shelter()).isNotNull();
        assertThat(detail.shelter().id()).isEqualTo(created.id());
        assertThat(detail.shelter().name()).isEqualTo(created.name());
        assertThat(detail.constraints()).isNotNull();
        assertThat(detail.capacities()).isNotNull();
        assertThat(detail.capacities()).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void test_listShelters() {
        HttpHeaders headers = authHelper.cocAdminHeaders();

        // Create two shelters
        ShelterResponse shelter1 = createTestShelter(headers);
        ShelterResponse shelter2 = createTestShelter(headers);

        // List all shelters — response format is now ShelterListResponse with shelter + availabilitySummary
        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "/api/v1/shelters",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> shelters = response.getBody();
        assertThat(shelters).isNotNull();

        // Verify both shelters are in the result (nested under "shelter" key)
        @SuppressWarnings("unchecked")
        List<UUID> returnedIds = shelters.stream()
                .map(item -> {
                    Map<String, Object> shelter = (Map<String, Object>) item.get("shelter");
                    return UUID.fromString((String) shelter.get("id"));
                })
                .toList();
        assertThat(returnedIds).contains(shelter1.id(), shelter2.id());
    }

    @Test
    void test_updateShelter_asCocAdmin() {
        HttpHeaders headers = authHelper.cocAdminHeaders();
        ShelterResponse created = createTestShelter(headers);

        String updateBody = """
                {
                    "name": "Updated Shelter Name"
                }
                """;

        ResponseEntity<ShelterResponse> response = restTemplate.exchange(
                "/api/v1/shelters/" + created.id(),
                HttpMethod.PUT,
                new HttpEntity<>(updateBody, headers),
                ShelterResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ShelterResponse updated = response.getBody();
        assertThat(updated).isNotNull();
        assertThat(updated.name()).isEqualTo("Updated Shelter Name");
        assertThat(updated.id()).isEqualTo(created.id());
    }

    @Test
    void test_filterByConstraints() {
        HttpHeaders headers = authHelper.cocAdminHeaders();

        // Shelter A: pets allowed
        String shelterABody = """
                {
                    "name": "Pet-Friendly Shelter %s",
                    "addressStreet": "100 Pet Lane",
                    "addressCity": "Raleigh",
                    "addressState": "NC",
                    "addressZip": "27601",
                    "phone": "919-555-0300",
                    "latitude": 35.78,
                    "longitude": -78.64,
                    "dvShelter": false,
                    "constraints": {
                        "sobrietyRequired": false,
                        "idRequired": false,
                        "referralRequired": false,
                        "petsAllowed": true,
                        "wheelchairAccessible": true,
                        "populationTypesServed": ["SINGLE_ADULT"]
                    },
                    "capacities": [
                        {"populationType": "SINGLE_ADULT", "bedsTotal": 10}
                    ]
                }
                """.formatted(UUID.randomUUID().toString().substring(0, 8));

        ResponseEntity<ShelterResponse> shelterAResponse = restTemplate.exchange(
                "/api/v1/shelters",
                HttpMethod.POST,
                new HttpEntity<>(shelterABody, headers),
                ShelterResponse.class
        );
        assertThat(shelterAResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID shelterAId = shelterAResponse.getBody().id();

        // Shelter B: no pets
        String shelterBBody = """
                {
                    "name": "No-Pets Shelter %s",
                    "addressStreet": "200 No Pet Ave",
                    "addressCity": "Raleigh",
                    "addressState": "NC",
                    "addressZip": "27601",
                    "phone": "919-555-0400",
                    "latitude": 35.77,
                    "longitude": -78.63,
                    "dvShelter": false,
                    "constraints": {
                        "sobrietyRequired": false,
                        "idRequired": false,
                        "referralRequired": false,
                        "petsAllowed": false,
                        "wheelchairAccessible": true,
                        "populationTypesServed": ["SINGLE_ADULT"]
                    },
                    "capacities": [
                        {"populationType": "SINGLE_ADULT", "bedsTotal": 10}
                    ]
                }
                """.formatted(UUID.randomUUID().toString().substring(0, 8));

        ResponseEntity<ShelterResponse> shelterBResponse = restTemplate.exchange(
                "/api/v1/shelters",
                HttpMethod.POST,
                new HttpEntity<>(shelterBBody, headers),
                ShelterResponse.class
        );
        assertThat(shelterBResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID shelterBId = shelterBResponse.getBody().id();

        // Filter for pets allowed = true — response format is ShelterListResponse
        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "/api/v1/shelters?petsAllowed=true",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> filtered = response.getBody();
        assertThat(filtered).isNotNull();

        @SuppressWarnings("unchecked")
        List<UUID> filteredIds = filtered.stream()
                .map(item -> {
                    Map<String, Object> shelter = (Map<String, Object>) item.get("shelter");
                    return UUID.fromString((String) shelter.get("id"));
                })
                .toList();
        assertThat(filteredIds).contains(shelterAId);
        assertThat(filteredIds).doesNotContain(shelterBId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void test_hsdsExport() {
        HttpHeaders headers = authHelper.cocAdminHeaders();
        ShelterResponse created = createTestShelter(headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/shelters/" + created.id() + "?format=hsds",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> hsds = response.getBody();
        assertThat(hsds).isNotNull();

        // HSDS 3.0 structure: organization, service, location, fabt: extensions
        assertThat(hsds).containsKey("organization");
        assertThat(hsds).containsKey("service");
        assertThat(hsds).containsKey("location");

        @SuppressWarnings("unchecked")
        Map<String, Object> org = (Map<String, Object>) hsds.get("organization");
        assertThat(org.get("name")).isEqualTo(created.name());

        // Verify fabt: extension fields are present
        boolean hasFabtExtension = hsds.keySet().stream()
                .anyMatch(key -> key.startsWith("fabt:"));
        assertThat(hasFabtExtension)
                .as("HSDS export should include fabt: extension fields")
                .isTrue();
    }

    @Test
    void test_coordinatorCanUpdateAssignedShelter() {
        HttpHeaders adminHeaders = authHelper.cocAdminHeaders();

        // Create a shelter as admin
        ShelterResponse created = createTestShelter(adminHeaders);

        // Assign the coordinator to this shelter
        User coordinatorUser = authHelper.setupCoordinatorUser();
        String assignBody = """
                {"userId": "%s"}
                """.formatted(coordinatorUser.getId());

        ResponseEntity<Void> assignResponse = restTemplate.exchange(
                "/api/v1/shelters/" + created.id() + "/coordinators",
                HttpMethod.POST,
                new HttpEntity<>(assignBody, adminHeaders),
                Void.class
        );
        assertThat(assignResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Now update with coordinator token
        HttpHeaders coordinatorHeaders = authHelper.coordinatorHeaders();
        String updateBody = """
                {"name": "Coordinator Updated Name"}
                """;

        ResponseEntity<ShelterResponse> updateResponse = restTemplate.exchange(
                "/api/v1/shelters/" + created.id(),
                HttpMethod.PUT,
                new HttpEntity<>(updateBody, coordinatorHeaders),
                ShelterResponse.class
        );

        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.getBody()).isNotNull();
        assertThat(updateResponse.getBody().name()).isEqualTo("Coordinator Updated Name");
    }

    @Test
    void test_coordinatorCannotUpdateUnassignedShelter() {
        HttpHeaders adminHeaders = authHelper.cocAdminHeaders();

        // Create a shelter as admin, do NOT assign the coordinator
        ShelterResponse created = createTestShelter(adminHeaders);

        // Try to update with coordinator token
        HttpHeaders coordinatorHeaders = authHelper.coordinatorHeaders();
        String updateBody = """
                {"name": "Should Not Work"}
                """;

        ResponseEntity<String> updateResponse = restTemplate.exchange(
                "/api/v1/shelters/" + created.id(),
                HttpMethod.PUT,
                new HttpEntity<>(updateBody, coordinatorHeaders),
                String.class
        );

        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void test_outreachWorkerCannotCreateShelter() {
        HttpHeaders headers = authHelper.outreachWorkerHeaders();

        String body = """
                {
                    "name": "Should Not Be Created",
                    "addressStreet": "999 Blocked Rd",
                    "addressCity": "Raleigh",
                    "addressState": "NC",
                    "addressZip": "27601",
                    "phone": "919-555-9999",
                    "latitude": 35.78,
                    "longitude": -78.64,
                    "dvShelter": false
                }
                """;

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/shelters",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // -------------------------------------------------------------------------
    // Shelter Edit — DV Safeguard Tests (T-4 through T-7)
    // -------------------------------------------------------------------------

    private ShelterResponse createDvTestShelter(HttpHeaders headers) {
        String body = """
                {
                    "name": "DV Test Shelter %s",
                    "addressStreet": "100 Safe Haven Rd",
                    "addressCity": "Raleigh",
                    "addressState": "NC",
                    "addressZip": "27601",
                    "phone": "919-555-0777",
                    "latitude": 35.78,
                    "longitude": -78.64,
                    "dvShelter": true,
                    "constraints": {
                        "sobrietyRequired": false,
                        "idRequired": false,
                        "referralRequired": true,
                        "petsAllowed": false,
                        "wheelchairAccessible": true,
                        "populationTypesServed": ["DV_SURVIVOR"]
                    },
                    "capacities": [
                        {"populationType": "DV_SURVIVOR", "bedsTotal": 12}
                    ]
                }
                """.formatted(UUID.randomUUID().toString().substring(0, 8));

        ResponseEntity<ShelterResponse> response = restTemplate.exchange(
                "/api/v1/shelters",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                ShelterResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    @Test
    void test_coordinatorCanUpdateAssignedShelterPhone() {
        HttpHeaders adminHeaders = authHelper.cocAdminHeaders();
        ShelterResponse created = createTestShelter(adminHeaders);

        // Assign coordinator
        User coordinatorUser = authHelper.setupCoordinatorUser();
        restTemplate.exchange(
                "/api/v1/shelters/" + created.id() + "/coordinators",
                HttpMethod.POST,
                new HttpEntity<>("""
                        {"userId": "%s"}
                        """.formatted(coordinatorUser.getId()), adminHeaders),
                Void.class
        );

        // Coordinator updates phone — should succeed
        HttpHeaders coordinatorHeaders = authHelper.coordinatorHeaders();
        String updateBody = """
                {"phone": "919-555-9999"}
                """;

        ResponseEntity<ShelterResponse> response = restTemplate.exchange(
                "/api/v1/shelters/" + created.id(),
                HttpMethod.PUT,
                new HttpEntity<>(updateBody, coordinatorHeaders),
                ShelterResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().phone()).isEqualTo("919-555-9999");
    }

    @Test
    void test_coordinatorCannotChangeDvFlag() {
        HttpHeaders adminHeaders = authHelper.cocAdminHeaders();
        ShelterResponse created = createTestShelter(adminHeaders); // dvShelter=false

        // Assign coordinator
        User coordinatorUser = authHelper.setupCoordinatorUser();
        restTemplate.exchange(
                "/api/v1/shelters/" + created.id() + "/coordinators",
                HttpMethod.POST,
                new HttpEntity<>("""
                        {"userId": "%s"}
                        """.formatted(coordinatorUser.getId()), adminHeaders),
                Void.class
        );

        // Coordinator tries to change dvShelter flag — should return 403
        HttpHeaders coordinatorHeaders = authHelper.coordinatorHeaders();
        String updateBody = """
                {"dvShelter": true}
                """;

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/shelters/" + created.id(),
                HttpMethod.PUT,
                new HttpEntity<>(updateBody, coordinatorHeaders),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void test_cocAdminCanChangeDvFlag() {
        // DV flag change requires dvAccess=true to satisfy RLS policy
        User dvAdmin = authHelper.setupUserWithDvAccess(
                "dvadmin@test.com", "DV Admin", new String[]{"COC_ADMIN"});
        HttpHeaders dvAdminHeaders = authHelper.headersForUser(dvAdmin);

        ShelterResponse created = createTestShelter(dvAdminHeaders); // dvShelter=false
        assertThat(created.dvShelter()).isFalse();

        // COC_ADMIN with dvAccess sets dvShelter to true — should succeed
        String updateBody = """
                {"dvShelter": true}
                """;

        ResponseEntity<ShelterResponse> response = restTemplate.exchange(
                "/api/v1/shelters/" + created.id(),
                HttpMethod.PUT,
                new HttpEntity<>(updateBody, dvAdminHeaders),
                ShelterResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().dvShelter()).isTrue();
    }

    @Test
    void test_coordinatorCannotUpdateUnassignedShelter_edit() {
        HttpHeaders adminHeaders = authHelper.cocAdminHeaders();
        ShelterResponse created = createTestShelter(adminHeaders);
        // Do NOT assign the coordinator

        HttpHeaders coordinatorHeaders = authHelper.coordinatorHeaders();
        String updateBody = """
                {"phone": "919-555-0000"}
                """;

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/shelters/" + created.id(),
                HttpMethod.PUT,
                new HttpEntity<>(updateBody, coordinatorHeaders),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void test_invalidPopulationType_returns400() {
        HttpHeaders headers = authHelper.cocAdminHeaders();

        String body = """
                {
                    "name": "Bad Population Type Shelter",
                    "addressStreet": "111 Bad Type Rd",
                    "addressCity": "Raleigh",
                    "addressState": "NC",
                    "addressZip": "27601",
                    "phone": "919-555-0500",
                    "latitude": 35.78,
                    "longitude": -78.64,
                    "dvShelter": false,
                    "constraints": {
                        "sobrietyRequired": false,
                        "idRequired": false,
                        "referralRequired": false,
                        "petsAllowed": true,
                        "wheelchairAccessible": true,
                        "populationTypesServed": ["INVALID_TYPE"]
                    },
                    "capacities": [
                        {"populationType": "INVALID_TYPE", "bedsTotal": 10}
                    ]
                }
                """;

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/shelters",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // HSDS Export — DV Redaction (T-48)
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void test_hsdsExport_redactsDvShelterAddress_forNonDvUser() {
        // Create a DV shelter (requires dvAccess user)
        User dvAdmin = authHelper.setupUserWithDvAccess(
                "dvhsds@test.com", "DV HSDS Admin", new String[]{"COC_ADMIN"});
        HttpHeaders dvHeaders = authHelper.headersForUser(dvAdmin);
        ShelterResponse dvShelter = createDvTestShelter(dvHeaders);

        // Fetch HSDS export as a non-dvAccess user (cocadmin, dvAccess=false)
        HttpHeaders nonDvHeaders = authHelper.cocAdminHeaders();
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/shelters/" + dvShelter.id() + "?format=hsds",
                HttpMethod.GET,
                new HttpEntity<>(nonDvHeaders),
                Map.class
        );

        // DV shelters are hidden from non-dvAccess users by RLS,
        // so this should return 404 (shelter not visible)
        assertThat(response.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.OK);

        if (response.getStatusCode() == HttpStatus.OK) {
            // If RLS allows visibility (e.g., via policy), address should be redacted
            Map<String, Object> hsds = response.getBody();
            assertThat(hsds).isNotNull();
            Map<String, Object> location = (Map<String, Object>) hsds.get("location");
            if (location != null) {
                // physical_address should be removed for non-dvAccess users
                assertThat(location).doesNotContainKey("physical_address");
            }
        }
    }

    // -------------------------------------------------------------------------
    // HIC/PIT CSV Format Validation (T-49)
    // -------------------------------------------------------------------------

    @Test
    void test_hicExport_hasValidCsvFormat() {
        HttpHeaders headers = authHelper.cocAdminHeaders();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/analytics/hic?date=" + java.time.LocalDate.now(),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String csv = response.getBody();
        assertThat(csv).isNotNull();

        String[] lines = csv.split("\n");
        assertThat(lines.length).isGreaterThan(0);

        // Verify header matches HUD Inventory.csv schema (FY2024+)
        String header = lines[0];
        assertThat(header).contains("InventoryID");
        assertThat(header).contains("ProjectID");
        assertThat(header).contains("CoCCode");
        assertThat(header).contains("HouseholdType");
        assertThat(header).contains("BedInventory");
        assertThat(header).contains("VetBedInventory");
        assertThat(header).contains("ESBedType");

        // Verify data rows have same number of columns as header
        int headerCols = header.split(",", -1).length;
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                // Count commas accounting for quoted fields
                int dataCols = lines[i].split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1).length;
                assertThat(dataCols)
                        .as("Row %d should have %d columns like the header", i, headerCols)
                        .isEqualTo(headerCols);
            }
        }
    }
}
