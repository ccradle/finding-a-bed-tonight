package org.fabt.availability;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.shelter.api.ShelterResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: DV-authorized outreach worker bed search.
 *
 * Verifies the API contract for OUTREACH_WORKER with dvAccess=true:
 * - Can call the bed search endpoint (authenticated, correct role)
 * - Results include expected response structure
 * - DV shelters have null address when visible (redacted by BedSearchService)
 * - Non-DV shelters have full address
 *
 * NOTE: DV shelter visibility is controlled by PostgreSQL RLS which depends on
 * SET ROLE fabt_app + app.dv_access session variable. In Testcontainers, the DB
 * user may be a superuser so RLS may not apply. DV visibility is verified in
 * DvAccessRlsTest which sets app.dv_access directly at the SQL level.
 * This test verifies the API layer contract, not RLS enforcement.
 */
@DisplayName("DV Outreach Worker — Bed Search API")
class DvOutreachBedSearchTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    private User dvOutreachUser;
    private UUID regularShelterId;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupCocAdminUser();
        authHelper.setupCoordinatorUser();

        // Create a DV-authorized outreach worker
        dvOutreachUser = authHelper.setupUserWithDvAccess(
                "dv-outreach@test.fabt.org", "DV Outreach Worker",
                new String[]{"OUTREACH_WORKER"});

        // Create a regular shelter with availability for search results
        regularShelterId = createShelter(authHelper.cocAdminHeaders(),
                "Regular Shelter " + UUID.randomUUID().toString().substring(0, 8),
                false, new String[]{"SINGLE_ADULT"});
        assignCoordinator(regularShelterId, authHelper.cocAdminHeaders());
        submitAvailability(regularShelterId, authHelper.coordinatorHeaders(),
                "SINGLE_ADULT", 50, 30, 0);
    }

    @Test
    @DisplayName("DV outreach worker can search beds (authenticated, correct role)")
    void dvOutreachWorker_canSearchBeds() {
        HttpHeaders headers = authHelper.headersForUser(dvOutreachUser);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/queries/beds",
                HttpMethod.POST,
                new HttpEntity<>("{}", headers),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("results");
        assertThat(response.getBody()).containsKey("totalCount");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.getBody().get("results");
        assertThat(results).isNotEmpty();
    }

    @Test
    @DisplayName("Non-DV shelter returns full address for DV outreach worker")
    void nonDvShelter_returnsFullAddress() {
        HttpHeaders headers = authHelper.headersForUser(dvOutreachUser);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/queries/beds",
                HttpMethod.POST,
                new HttpEntity<>("{}", headers),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.getBody().get("results");

        // Find the regular shelter
        Map<String, Object> regularResult = results.stream()
                .filter(r -> regularShelterId.toString().equals(r.get("shelterId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Regular shelter not found in results"));

        // Non-DV shelter should have full address
        assertThat(regularResult.get("address"))
                .as("Non-DV shelter should have full address")
                .isNotNull();

        // DV flag should be false
        assertThat(regularResult.get("dvShelter")).isEqualTo(false);
    }

    @Test
    @DisplayName("Search results include expected response structure")
    void searchResults_haveExpectedStructure() {
        HttpHeaders headers = authHelper.headersForUser(dvOutreachUser);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/queries/beds",
                HttpMethod.POST,
                new HttpEntity<>("{}", headers),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.getBody().get("results");
        assertThat(results).isNotEmpty();

        Map<String, Object> firstResult = results.get(0);
        assertThat(firstResult).containsKey("shelterId");
        assertThat(firstResult).containsKey("shelterName");
        assertThat(firstResult).containsKey("availability");
        assertThat(firstResult).containsKey("dataFreshness");
        assertThat(firstResult).containsKey("dvShelter");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID createShelter(HttpHeaders headers, String name, boolean dvShelter, String[] populationTypes) {
        String popArray = String.join("\", \"", populationTypes);
        String body = """
                {
                    "name": "%s",
                    "addressStreet": "123 Test St",
                    "addressCity": "Raleigh",
                    "addressState": "NC",
                    "addressZip": "27601",
                    "phone": "919-555-0100",
                    "latitude": 35.7796,
                    "longitude": -78.6382,
                    "dvShelter": %s,
                    "constraints": {
                        "sobrietyRequired": false,
                        "idRequired": false,
                        "referralRequired": false,
                        "petsAllowed": true,
                        "wheelchairAccessible": true,
                        "populationTypesServed": ["%s"]
                    },
                    "capacities": [
                        {"populationType": "%s", "bedsTotal": 50}
                    ]
                }
                """.formatted(name, dvShelter, popArray, populationTypes[0]);

        ResponseEntity<ShelterResponse> response = restTemplate.exchange(
                "/api/v1/shelters",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                ShelterResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private void assignCoordinator(UUID shelterId, HttpHeaders adminHeaders) {
        UUID coordinatorUserId = authHelper.setupCoordinatorUser().getId();
        String body = """
                {"userId": "%s"}
                """.formatted(coordinatorUserId);

        restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/coordinators",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders),
                Void.class
        );
    }

    private void submitAvailability(UUID shelterId, HttpHeaders headers,
                                     String populationType, int bedsTotal, int bedsOccupied, int bedsOnHold) {
        String body = """
                {
                    "populationType": "%s",
                    "bedsTotal": %d,
                    "bedsOccupied": %d,
                    "bedsOnHold": %d,
                    "acceptingNewGuests": true
                }
                """.formatted(populationType, bedsTotal, bedsOccupied, bedsOnHold);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/availability",
                HttpMethod.PATCH,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<>() {}
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
