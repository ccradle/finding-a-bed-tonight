package org.fabt.reservation;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
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
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the shelter-scoped reservation read endpoint
 * (transitional-reentry-support slice 4 §11.5).
 *
 * <p>Covers four invariants:
 * <ol>
 *   <li>Assigned coordinator can read their shelter's HELD reservations.</li>
 *   <li>Unassigned coordinator gets 403 (per the in-controller fine-pass).</li>
 *   <li>COC_ADMIN bypasses the assignment check (any shelter in tenant).</li>
 *   <li>Hold attribution PII (heldForClientName) flows through the
 *       response — that's the whole point of the endpoint; without
 *       this assertion the §11 dialog has no UI to render to.</li>
 * </ol>
 */
@DisplayName("GET /api/v1/shelters/{id}/reservations — slice 4 §11.5")
class ShelterReservationsEndpointTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID shelterId;

    @BeforeEach
    void setUp() {
        String slug = "shelter-reservations-" + UUID.randomUUID().toString().substring(0, 8);
        UUID tenantId = authHelper.setupTestTenant(slug).getId();
        // Round 5 §16.B: opt this tenant into reentryMode so the issued JWTs
        // carry reentryMode=true and the API serialization gate surfaces
        // hold-attribution PII fields. The §11.5 endpoint contract under test
        // explicitly REQUIRES the PII to flow back; this test asserts that
        // contract holds when the gate is open (default-off remains the
        // production-correct behavior for tenants that haven't opted in).
        authHelper.enableReentryMode(tenantId);
        authHelper.setupCocAdminUser();
        authHelper.setupCoordinatorUser();
        authHelper.setupOutreachWorkerUser();

        shelterId = createShelter();
        assignCoordinator(shelterId);
        submitAvailability(shelterId);
    }

    @Test
    @DisplayName("assigned coordinator sees their shelter's HELD reservations with PII")
    void assignedCoordinator_seesHoldsWithPii() {
        // Outreach worker places a hold WITH attribution.
        String name = "Probe-" + UUID.randomUUID();
        UUID reservationId = postHoldWithAttribution(name);

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/reservations",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> rows = response.getBody();
        assertThat(rows).isNotNull();

        // Find the hold we created.
        Map<String, Object> ourRow = rows.stream()
                .filter(r -> reservationId.toString().equals(r.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "coordinator should see the hold they were not the author of"));

        assertThat(ourRow.get("status")).isEqualTo("HELD");
        // PII surfacing — slice 2D warroom H1 added these fields to
        // ReservationResponse; this endpoint MUST surface them too.
        assertThat(ourRow.get("heldForClientName"))
                .as("coordinator dashboard hold-list MUST surface the attribution name "
                        + "the outreach worker entered — that's the entire purpose of §11.5")
                .isEqualTo(name);
    }

    @Test
    @DisplayName("unassigned coordinator gets 403 (in-controller fine-pass)")
    void unassignedCoordinator_gets403() {
        // Create a SECOND shelter, do NOT assign the coordinator.
        UUID otherShelterId = createShelter();
        // No assignCoordinator() call — by construction, the test's
        // coordinatorUser is not assigned to otherShelterId.

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/shelters/" + otherShelterId + "/reservations",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode())
                .as("unassigned coordinator must be rejected — fine-pass authz "
                        + "via CoordinatorAssignmentRepository.isAssigned")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("COC_ADMIN bypasses the assignment check")
    void cocAdmin_bypassesAssignmentCheck() {
        // COC_ADMIN is NOT in the coordinator_assignment table — but the
        // controller's `isCoordinatorOnly` branch lets admins through.
        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/reservations",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.cocAdminHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode())
                .as("COC_ADMIN must bypass the per-shelter assignment check "
                        + "and read any shelter's holds in their tenant")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("OUTREACH_WORKER (not in @PreAuthorize role list) gets 403")
    void outreachWorker_gets403() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/reservations",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.outreachWorkerHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode())
                .as("OUTREACH_WORKER is not in @PreAuthorize hasAnyRole list — "
                        + "Spring Security rejects before the controller body runs")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("empty result when no HELD reservations on the shelter")
    void noHolds_returnsEmptyList() {
        // Fresh shelter, no holds created yet — admin reads to bypass
        // assignment check.
        UUID emptyShelterId = createShelter();

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "/api/v1/shelters/" + emptyShelterId + "/reservations",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.cocAdminHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("empty result must be 200 + [] — never null, never 404; the "
                        + "frontend renders the no-holds state from the empty array")
                .isEmpty();
    }

    // ---------------------------------------------------------------------
    // Helpers — copy + adapt from existing reservation tests
    // ---------------------------------------------------------------------

    private UUID createShelter() {
        String body = """
                {
                    "name": "Holds Test Shelter %s",
                    "addressStreet": "1 Test St",
                    "addressCity": "Raleigh",
                    "addressState": "NC",
                    "addressZip": "27601",
                    "phone": "919-555-0500",
                    "dvShelter": false,
                    "constraints": {
                        "sobrietyRequired": false,
                        "idRequired": false,
                        "referralRequired": false,
                        "petsAllowed": false,
                        "wheelchairAccessible": true,
                        "populationTypesServed": ["SINGLE_ADULT"]
                    },
                    "capacities": [{"populationType": "SINGLE_ADULT", "bedsTotal": 50}]
                }
                """.formatted(UUID.randomUUID().toString().substring(0, 8));

        ResponseEntity<ShelterResponse> response = restTemplate.exchange(
                "/api/v1/shelters", HttpMethod.POST,
                new HttpEntity<>(body, authHelper.cocAdminHeaders()),
                ShelterResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private void assignCoordinator(UUID id) {
        UUID coordinatorId = authHelper.setupCoordinatorUser().getId();
        restTemplate.exchange(
                "/api/v1/shelters/" + id + "/coordinators",
                HttpMethod.POST,
                new HttpEntity<>("{\"userId\":\"" + coordinatorId + "\"}",
                        authHelper.cocAdminHeaders()),
                Void.class);
    }

    private void submitAvailability(UUID id) {
        String body = """
                {
                    "populationType": "SINGLE_ADULT",
                    "bedsTotal": 50,
                    "bedsOccupied": 0,
                    "bedsOnHold": 0,
                    "acceptingNewGuests": true
                }
                """;
        restTemplate.exchange(
                "/api/v1/shelters/" + id + "/availability",
                HttpMethod.PATCH,
                new HttpEntity<>(body, authHelper.coordinatorHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private UUID postHoldWithAttribution(String name) {
        String body = """
                {
                    "shelterId": "%s",
                    "populationType": "SINGLE_ADULT",
                    "notes": "test reservation",
                    "heldForClientName": "%s",
                    "heldForClientDob": "1990-01-01",
                    "holdNotes": "navigator hand-off pending"
                }
                """.formatted(shelterId, name);

        HttpHeaders headers = new HttpHeaders();
        headers.addAll(authHelper.outreachWorkerHeaders());
        headers.set("Content-Type", "application/json");

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/reservations",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) response.getBody().get("id"));
    }
}
