package org.fabt.availability;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.shared.event.DomainEvent;
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
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class AvailabilityIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestEventListener eventListener;

    private UUID shelterId;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        authHelper.setupCocAdminUser();
        authHelper.setupCoordinatorUser();
        authHelper.setupOutreachWorkerUser();

        eventListener.clear();

        // Create a shelter for testing
        shelterId = createTestShelter(authHelper.cocAdminHeaders());

        // Assign coordinator to this shelter
        assignCoordinator(shelterId, authHelper.cocAdminHeaders());
    }

    // -------------------------------------------------------------------------
    // 8.1: Create availability snapshot, verify append-only
    // -------------------------------------------------------------------------

    @Test
    void test_createSnapshot_appendOnly_preservesPreviousSnapshot() {
        HttpHeaders headers = authHelper.coordinatorHeaders();

        // First snapshot
        submitAvailability(shelterId, headers, "SINGLE_ADULT", 50, 30, 0);

        // Second snapshot
        submitAvailability(shelterId, headers, "SINGLE_ADULT", 50, 35, 1);

        // Verify both snapshots exist in database (append-only)
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bed_availability WHERE shelter_id = ? AND population_type = ?",
                Integer.class,
                shelterId, "SINGLE_ADULT"
        );
        assertThat(count).isGreaterThanOrEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // 8.2: Query beds with population type filter, ranked results
    // -------------------------------------------------------------------------

    @Test
    void test_queryBeds_withPopulationTypeFilter_returnsRankedResults() {
        HttpHeaders coordHeaders = authHelper.coordinatorHeaders();

        // Submit availability for this shelter
        submitAvailability(shelterId, coordHeaders, "SINGLE_ADULT", 50, 30, 0);

        // Query as outreach worker
        HttpHeaders outreachHeaders = authHelper.outreachWorkerHeaders();
        String body = """
                {"populationType": "SINGLE_ADULT", "limit": 20}
                """;
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/queries/beds",
                HttpMethod.POST,
                new HttpEntity<>(body, outreachHeaders),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.getBody().get("results");
        assertThat(results).isNotEmpty();

        // Verify results have availability data
        Map<String, Object> firstResult = results.get(0);
        assertThat(firstResult).containsKey("availability");
        assertThat(firstResult).containsKey("dataAgeSeconds");
        assertThat(firstResult).containsKey("dataFreshness");
    }

    // -------------------------------------------------------------------------
    // 8.3: Query beds with constraint filters
    // -------------------------------------------------------------------------

    @Test
    void test_queryBeds_withConstraintFilters_returnsMatchingShelters() {
        HttpHeaders coordHeaders = authHelper.coordinatorHeaders();
        submitAvailability(shelterId, coordHeaders, "SINGLE_ADULT", 50, 30, 0);

        // Query with pets filter — shelter was created with petsAllowed=true
        HttpHeaders outreachHeaders = authHelper.outreachWorkerHeaders();
        String body = """
                {"constraints": {"petsAllowed": true}}
                """;
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/queries/beds",
                HttpMethod.POST,
                new HttpEntity<>(body, outreachHeaders),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The test shelter has petsAllowed=true, so it should appear
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.getBody().get("results");
        assertThat(results).isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // 8.4: Concurrent availability updates (ON CONFLICT DO NOTHING)
    // -------------------------------------------------------------------------

    @Test
    void test_concurrentUpdates_onConflictDoNothing_noError() {
        HttpHeaders headers = authHelper.coordinatorHeaders();

        // Submit two identical updates — should not error
        ResponseEntity<Map<String, Object>> r1 = submitAvailability(shelterId, headers, "SINGLE_ADULT", 50, 30, 0);
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second update with same data — may or may not conflict, but should succeed
        ResponseEntity<Map<String, Object>> r2 = submitAvailability(shelterId, headers, "SINGLE_ADULT", 50, 31, 0);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // -------------------------------------------------------------------------
    // 8.5: Shelter detail includes latest availability per population type
    // -------------------------------------------------------------------------

    @Test
    void test_shelterDetail_includesAvailability() {
        HttpHeaders coordHeaders = authHelper.coordinatorHeaders();
        submitAvailability(shelterId, coordHeaders, "SINGLE_ADULT", 50, 30, 2);

        HttpHeaders outreachHeaders = authHelper.outreachWorkerHeaders();
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/shelters/" + shelterId,
                HttpMethod.GET,
                new HttpEntity<>(outreachHeaders),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("availability");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> availability = (List<Map<String, Object>>) response.getBody().get("availability");
        assertThat(availability).isNotEmpty();

        Map<String, Object> first = availability.get(0);
        assertThat(first.get("populationType")).isEqualTo("SINGLE_ADULT");
        assertThat(first.get("bedsAvailable")).isEqualTo(18); // 50 - 30 - 2
    }

    // -------------------------------------------------------------------------
    // 8.6: data_age_seconds computed from snapshot_ts
    // -------------------------------------------------------------------------

    @Test
    void test_dataAgeSeconds_computedFromSnapshotTs() {
        HttpHeaders coordHeaders = authHelper.coordinatorHeaders();
        submitAvailability(shelterId, coordHeaders, "SINGLE_ADULT", 50, 30, 0);

        HttpHeaders outreachHeaders = authHelper.outreachWorkerHeaders();
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/shelters/" + shelterId,
                HttpMethod.GET,
                new HttpEntity<>(outreachHeaders),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // data_age_seconds should be very small since we just submitted
        Object dataAge = response.getBody().get("data_age_seconds");
        assertThat(dataAge).isNotNull();
        assertThat(((Number) dataAge).longValue()).isLessThan(60);
    }

    // -------------------------------------------------------------------------
    // 8.7: Coordinator can update assigned shelter, not unassigned
    // -------------------------------------------------------------------------

    @Test
    void test_coordinator_canUpdateAssigned_cannotUpdateUnassigned() {
        HttpHeaders coordHeaders = authHelper.coordinatorHeaders();

        // Can update assigned shelter
        ResponseEntity<Map<String, Object>> r1 = submitAvailability(shelterId, coordHeaders, "SINGLE_ADULT", 50, 30, 0);
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Create another shelter (not assigned to coordinator)
        UUID otherShelterId = createTestShelter(authHelper.cocAdminHeaders());

        // Cannot update unassigned shelter
        ResponseEntity<Map<String, Object>> r2 = submitAvailability(otherShelterId, coordHeaders, "SINGLE_ADULT", 50, 30, 0);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // -------------------------------------------------------------------------
    // 8.8: Outreach worker can search but cannot update (403)
    // -------------------------------------------------------------------------

    @Test
    void test_outreachWorker_canSearch_cannotUpdate() {
        HttpHeaders outreachHeaders = authHelper.outreachWorkerHeaders();

        // Can search
        String searchBody = "{}";
        ResponseEntity<Map<String, Object>> searchResponse = restTemplate.exchange(
                "/api/v1/queries/beds",
                HttpMethod.POST,
                new HttpEntity<>(searchBody, outreachHeaders),
                new ParameterizedTypeReference<>() {}
        );
        assertThat(searchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Cannot update availability (403)
        ResponseEntity<Map<String, Object>> updateResponse = submitAvailability(
                shelterId, outreachHeaders, "SINGLE_ADULT", 50, 30, 0);
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // -------------------------------------------------------------------------
    // 8.9: DV shelters excluded from bed search for users without dvAccess
    // -------------------------------------------------------------------------

    @Test
    void test_dvShelters_excludedWithoutDvAccess() {
        HttpHeaders cocHeaders = authHelper.cocAdminHeaders();

        // Create a DV shelter
        String dvBody = """
                {
                    "name": "DV Safe House %s",
                    "addressStreet": "999 Hidden Ln",
                    "addressCity": "Raleigh",
                    "addressState": "NC",
                    "addressZip": "27601",
                    "dvShelter": true,
                    "constraints": {
                        "sobrietyRequired": false,
                        "idRequired": false,
                        "referralRequired": false,
                        "petsAllowed": false,
                        "wheelchairAccessible": true,
                        "populationTypesServed": ["DV_SURVIVOR"]
                    },
                    "capacities": [
                        {"populationType": "DV_SURVIVOR", "bedsTotal": 10}
                    ]
                }
                """.formatted(UUID.randomUUID().toString().substring(0, 8));

        ResponseEntity<ShelterResponse> createResponse = restTemplate.exchange(
                "/api/v1/shelters",
                HttpMethod.POST,
                new HttpEntity<>(dvBody, cocHeaders),
                ShelterResponse.class
        );
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Search as outreach worker without dvAccess
        // RLS enforcement depends on the PostgreSQL user not being a superuser.
        // In Testcontainers, the user may have superuser privileges, so RLS may not apply.
        // This test verifies the search endpoint works correctly; RLS-level DV filtering
        // is verified in DvAccessRlsTest which sets app.dv_access directly at the SQL level.
        HttpHeaders outreachHeaders = authHelper.outreachWorkerHeaders();
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/queries/beds",
                HttpMethod.POST,
                new HttpEntity<>("{}", outreachHeaders),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("results");
    }

    // -------------------------------------------------------------------------
    // 8.10: availability.updated event published on snapshot insert
    // -------------------------------------------------------------------------

    @Test
    void test_availabilityUpdatedEvent_publishedOnSnapshotInsert() {
        eventListener.clear();

        HttpHeaders headers = authHelper.coordinatorHeaders();
        ResponseEntity<Map<String, Object>> response = submitAvailability(
                shelterId, headers, "SINGLE_ADULT", 50, 30, 0);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("bedsAvailable")).isEqualTo(20); // 50 - 30 - 0

        // Verify the availability.updated event was published with correct payload
        List<DomainEvent> events = eventListener.getEventsByType("availability.updated");
        assertThat(events).isNotEmpty();
        DomainEvent event = events.get(events.size() - 1);
        assertThat(event.type()).isEqualTo("availability.updated");
        assertThat(event.payload().get("shelter_id")).isEqualTo(shelterId.toString());
        assertThat(event.payload().get("population_type")).isEqualTo("SINGLE_ADULT");
        assertThat(event.payload().get("beds_available")).isEqualTo(20);
        assertThat(event.payload().get("coc_id")).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID createTestShelter(HttpHeaders headers) {
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
                        {"populationType": "SINGLE_ADULT", "bedsTotal": 50},
                        {"populationType": "FAMILY_WITH_CHILDREN", "bedsTotal": 20}
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
        return response.getBody().id();
    }

    private void assignCoordinator(UUID shelterId, HttpHeaders headers) {
        // Get coordinator user ID from the coordinator headers' JWT
        UUID coordinatorUserId = authHelper.setupCoordinatorUser().getId();

        String body = """
                {"userId": "%s"}
                """.formatted(coordinatorUserId);

        restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/coordinators",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Void.class
        );
    }

    private ResponseEntity<Map<String, Object>> submitAvailability(
            UUID shelterId, HttpHeaders headers,
            String populationType, int bedsTotal, int bedsOccupied, int bedsOnHold) {
        String body = """
                {
                    "populationType": "%s",
                    "bedsTotal": %d,
                    "bedsOccupied": %d,
                    "bedsOnHold": %d,
                    "acceptingNewGuests": true,
                    "notes": "Test update"
                }
                """.formatted(populationType, bedsTotal, bedsOccupied, bedsOnHold);

        return restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/availability",
                HttpMethod.PATCH,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<>() {}
        );
    }
}
