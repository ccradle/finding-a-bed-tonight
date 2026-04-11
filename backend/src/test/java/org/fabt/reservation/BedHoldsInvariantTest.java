package org.fabt.reservation;

import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.availability.repository.BedAvailabilityRepository;
import org.fabt.availability.repository.BedAvailabilityRepository.DriftRow;
import org.fabt.reservation.repository.ReservationRepository;
import org.fabt.reservation.service.ReservationExpiryService;
import org.fabt.reservation.service.ReservationService;
import org.fabt.shared.web.TenantContext;
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

/**
 * Gating invariant test for the bed-hold-integrity change (Issue #102 RCA).
 *
 * <p>Asserts the load-bearing invariant
 * <pre>
 *     bed_availability.beds_on_hold (latest snapshot)
 *       === SELECT COUNT(*) FROM reservation
 *           WHERE shelter_id = ? AND population_type = ? AND status = 'HELD'
 * </pre>
 * after every reservation lifecycle event AND after the reconciliation tasklet
 * runs. This is the test that should fail loudly if a future refactor
 * reintroduces delta math on {@code beds_on_hold}.</p>
 */
class BedHoldsInvariantTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ReservationService reservationService;
    @Autowired private ReservationExpiryService reservationExpiryService;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private BedAvailabilityRepository bedAvailabilityRepository;

    private UUID shelterId;
    private UUID coordinatorId;
    private UUID outreachWorkerId;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        authHelper.setupCocAdminUser();
        coordinatorId = authHelper.setupCoordinatorUser().getId();
        outreachWorkerId = authHelper.setupOutreachWorkerUser().getId();

        shelterId = createTestShelter(authHelper.cocAdminHeaders());
        assignCoordinator(shelterId, coordinatorId);
        submitAvailability(shelterId, authHelper.coordinatorHeaders(), "SINGLE_ADULT", 50, 10, 0);
    }

    @Test
    void invariant_after_create() {
        assertInvariantHolds("baseline");
        createReservation(shelterId, "SINGLE_ADULT");
        assertInvariantHolds("after create");
    }

    @Test
    void invariant_after_cancel() {
        ResponseEntity<Map<String, Object>> created = createReservation(shelterId, "SINGLE_ADULT");
        String reservationId = (String) created.getBody().get("id");
        assertInvariantHolds("after create (pre-cancel)");

        restTemplate.exchange(
                "/api/v1/reservations/" + reservationId + "/cancel",
                HttpMethod.PATCH,
                new HttpEntity<>(authHelper.outreachWorkerHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        assertInvariantHolds("after cancel");
    }

    @Test
    void invariant_after_expire() {
        ResponseEntity<Map<String, Object>> created = createReservation(shelterId, "SINGLE_ADULT");
        String reservationId = (String) created.getBody().get("id");
        assertInvariantHolds("after create (pre-expire)");

        // Force the reservation past its expires_at and trigger the expiry job
        jdbcTemplate.update(
                "UPDATE reservation SET expires_at = NOW() - INTERVAL '1 minute' WHERE id = ?::uuid",
                reservationId);
        reservationExpiryService.expireOverdueReservations();

        assertInvariantHolds("after expire");
    }

    @Test
    void invariant_after_confirm() {
        ResponseEntity<Map<String, Object>> created = createReservation(shelterId, "SINGLE_ADULT");
        String reservationId = (String) created.getBody().get("id");
        assertInvariantHolds("after create (pre-confirm)");

        restTemplate.exchange(
                "/api/v1/reservations/" + reservationId + "/confirm",
                HttpMethod.PATCH,
                new HttpEntity<>(authHelper.outreachWorkerHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        assertInvariantHolds("after confirm");
    }

    @Test
    void invariant_after_offline_hold() {
        // CoC admin creates a manual offline hold via the new endpoint. Admins
        // bypass the coordinator-assignment check, so this test focuses on the
        // invariant rather than the auth path. The dedicated OfflineHoldEndpointTest
        // exercises the coordinator (assignment-checked) path.
        String body = """
                {
                    "populationType": "SINGLE_ADULT",
                    "reason": "Phone call from intake (test)"
                }
                """;
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/manual-hold",
                HttpMethod.POST,
                new HttpEntity<>(body, authHelper.cocAdminHeaders()),
                new ParameterizedTypeReference<>() {}
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertInvariantHolds("after offline hold");
    }

    @Test
    void invariant_after_recompute_via_public_api() {
        // Direct exercise of the public recomputeBedsOnHold method (the entry point
        // that the reconciliation tasklet uses). Calling through the bean — not
        // self-invocation — so the @Transactional proxy boundary kicks in. Wrap in
        // TenantContext.runWithContext because the HTTP auth filter that normally
        // sets it does not run when calling the service directly from a test.
        createReservation(shelterId, "SINGLE_ADULT");
        assertInvariantHolds("baseline (one hold)");

        UUID tenantId = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM shelter WHERE id = ?",
                UUID.class, shelterId);
        TenantContext.runWithContext(tenantId, false, () ->
                reservationService.recomputeBedsOnHold(
                        shelterId, "SINGLE_ADULT",
                        "test: explicit recompute", "system:test"));
        assertInvariantHolds("after explicit recompute");
    }

    @Test
    void drift_query_returns_empty_after_normal_lifecycle() {
        // After ordinary create/cancel cycles, findDriftedRows() must return empty.
        createReservation(shelterId, "SINGLE_ADULT");
        ResponseEntity<Map<String, Object>> r = createReservation(shelterId, "SINGLE_ADULT");
        String secondId = (String) r.getBody().get("id");
        restTemplate.exchange(
                "/api/v1/reservations/" + secondId + "/cancel",
                HttpMethod.PATCH,
                new HttpEntity<>(authHelper.outreachWorkerHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        java.util.List<DriftRow> drifted = bedAvailabilityRepository.findDriftedRows();

        // Filter to just our shelter — shared test context may have other rows
        long ourDriftedRows = drifted.stream()
                .filter(d -> d.shelterId().equals(shelterId))
                .count();
        assertThat(ourDriftedRows)
                .as("After normal lifecycle, findDriftedRows() must report zero drift for our shelter")
                .isZero();
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private void assertInvariantHolds(String label) {
        Integer snapshotHold = jdbcTemplate.queryForObject(
                """
                SELECT beds_on_hold
                FROM bed_availability
                WHERE shelter_id = ? AND population_type = 'SINGLE_ADULT'
                ORDER BY snapshot_ts DESC
                LIMIT 1
                """,
                Integer.class,
                shelterId);
        int actualCount = reservationRepository.countActiveByShelterId(shelterId, "SINGLE_ADULT");
        assertThat(snapshotHold)
                .as("Invariant violated %s: latest beds_on_hold (%s) != actual HELD count (%s) "
                        + "for shelter %s / SINGLE_ADULT — this is the regression %s exists to catch.",
                        label, snapshotHold, actualCount, shelterId, BedHoldsInvariantTest.class.getSimpleName())
                .isNotNull()
                .isEqualTo(actualCount);
    }

    private UUID createTestShelter(HttpHeaders headers) {
        String body = """
                {
                    "name": "Bed Holds Invariant Test Shelter %s",
                    "addressStreet": "123 Invariant Way",
                    "addressCity": "Raleigh",
                    "addressState": "NC",
                    "addressZip": "27601",
                    "phone": "919-555-0200",
                    "latitude": 35.7796,
                    "longitude": -78.6382,
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
                        {"populationType": "SINGLE_ADULT", "bedsTotal": 50}
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

    private void assignCoordinator(UUID shelterId, UUID userId) {
        String body = """
                {"userId": "%s"}
                """.formatted(userId);
        restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/coordinators",
                HttpMethod.POST,
                new HttpEntity<>(body, authHelper.cocAdminHeaders()),
                Void.class
        );
    }

    private void submitAvailability(UUID shelterId, HttpHeaders headers, String populationType,
                                     int bedsTotal, int bedsOccupied, int bedsOnHold) {
        String body = """
                {
                    "populationType": "%s",
                    "bedsTotal": %d,
                    "bedsOccupied": %d,
                    "bedsOnHold": %d,
                    "acceptingNewGuests": true
                }
                """.formatted(populationType, bedsTotal, bedsOccupied, bedsOnHold);
        restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/availability",
                HttpMethod.PATCH,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {}
        );
    }

    private ResponseEntity<Map<String, Object>> createReservation(UUID shelterId, String populationType) {
        String body = """
                {
                    "shelterId": "%s",
                    "populationType": "%s",
                    "notes": "BedHoldsInvariantTest"
                }
                """.formatted(shelterId, populationType);
        return restTemplate.exchange(
                "/api/v1/reservations",
                HttpMethod.POST,
                new HttpEntity<>(body, authHelper.outreachWorkerHeaders()),
                new ParameterizedTypeReference<>() {}
        );
    }
}
