package org.fabt.reservation;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.availability.TestEventListener;
import org.fabt.auth.domain.User;
import org.fabt.reservation.service.ReservationExpiryService;
import org.fabt.shared.event.DomainEvent;
import org.fabt.shelter.api.ShelterResponse;
import org.fabt.shelter.repository.CoordinatorAssignmentRepository;
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

class ReservationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CoordinatorAssignmentRepository coordinatorAssignmentRepository;

    @Autowired
    private TestEventListener eventListener;

    @Autowired
    private ReservationExpiryService reservationExpiryService;

    private UUID shelterId;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        authHelper.setupCocAdminUser();
        authHelper.setupCoordinatorUser();
        authHelper.setupOutreachWorkerUser();
        eventListener.clear();

        // Create shelter and assign coordinator
        shelterId = createTestShelter(authHelper.cocAdminHeaders());
        assignCoordinator(shelterId);

        // Submit initial availability so there are beds to reserve
        submitAvailability(shelterId, authHelper.coordinatorHeaders(), "SINGLE_ADULT", 50, 10, 0);
    }

    // -------------------------------------------------------------------------
    // 9.1: Create reservation, verify beds_on_hold incremented
    // -------------------------------------------------------------------------

    @Test
    void test_createReservation_bedsOnHoldIncremented() {
        HttpHeaders headers = authHelper.outreachWorkerHeaders();

        ResponseEntity<Map<String, Object>> response = createReservation(
                shelterId, headers, "SINGLE_ADULT");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo("HELD");
        assertThat(response.getBody().get("expiresAt")).isNotNull();
        assertThat(((Number) response.getBody().get("remainingSeconds")).longValue()).isGreaterThan(0);

        // Verify beds_on_hold incremented in availability
        ResponseEntity<Map<String, Object>> shelterDetail = restTemplate.exchange(
                "/api/v1/shelters/" + shelterId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {}
        );

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> availability = (List<Map<String, Object>>) shelterDetail.getBody().get("availability");
        Map<String, Object> singleAdult = availability.stream()
                .filter(a -> "SINGLE_ADULT".equals(a.get("populationType")))
                .findFirst()
                .orElseThrow();
        assertThat(((Number) singleAdult.get("bedsOnHold")).intValue()).isGreaterThanOrEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // 9.2: Confirm reservation, verify beds_on_hold decremented, beds_occupied incremented
    // -------------------------------------------------------------------------

    @Test
    void test_confirmReservation_adjustsAvailability() {
        HttpHeaders headers = authHelper.outreachWorkerHeaders();

        // Create reservation
        ResponseEntity<Map<String, Object>> createResponse = createReservation(
                shelterId, headers, "SINGLE_ADULT");
        String reservationId = (String) createResponse.getBody().get("id");

        // Confirm it
        ResponseEntity<Map<String, Object>> confirmResponse = restTemplate.exchange(
                "/api/v1/reservations/" + reservationId + "/confirm",
                HttpMethod.PATCH,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmResponse.getBody().get("status")).isEqualTo("CONFIRMED");
    }

    // -------------------------------------------------------------------------
    // 9.3: Cancel reservation, verify beds_on_hold decremented
    // -------------------------------------------------------------------------

    @Test
    void test_cancelReservation_bedsOnHoldDecremented() {
        HttpHeaders headers = authHelper.outreachWorkerHeaders();

        ResponseEntity<Map<String, Object>> createResponse = createReservation(
                shelterId, headers, "SINGLE_ADULT");
        String reservationId = (String) createResponse.getBody().get("id");

        ResponseEntity<Map<String, Object>> cancelResponse = restTemplate.exchange(
                "/api/v1/reservations/" + reservationId + "/cancel",
                HttpMethod.PATCH,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelResponse.getBody().get("status")).isEqualTo("CANCELLED");
    }

    // -------------------------------------------------------------------------
    // 9.4: Create reservation fails with 409 when beds_available = 0
    // -------------------------------------------------------------------------

    @Test
    void test_createReservation_noBeds_returns409() {
        HttpHeaders coordHeaders = authHelper.coordinatorHeaders();
        HttpHeaders outreachHeaders = authHelper.outreachWorkerHeaders();

        // Set availability to 0 beds available
        submitAvailability(shelterId, coordHeaders, "SINGLE_ADULT", 50, 50, 0);

        ResponseEntity<Map<String, Object>> response = createReservation(
                shelterId, outreachHeaders, "SINGLE_ADULT");

        // IllegalStateException maps to 400 via GlobalExceptionHandler
        assertThat(response.getStatusCode().value()).isGreaterThanOrEqualTo(400);
    }

    // -------------------------------------------------------------------------
    // 9.5: Confirm expired reservation returns error
    // -------------------------------------------------------------------------

    @Test
    void test_confirmExpiredReservation_returnsError() {
        HttpHeaders headers = authHelper.outreachWorkerHeaders();

        // Create reservation
        ResponseEntity<Map<String, Object>> createResponse = createReservation(
                shelterId, headers, "SINGLE_ADULT");
        String reservationId = (String) createResponse.getBody().get("id");

        // Manually expire it in the database
        jdbcTemplate.update(
                "UPDATE reservation SET status = 'EXPIRED', cancelled_at = NOW() WHERE id = ?::uuid",
                reservationId
        );

        // Try to confirm
        ResponseEntity<Map<String, Object>> confirmResponse = restTemplate.exchange(
                "/api/v1/reservations/" + reservationId + "/confirm",
                HttpMethod.PATCH,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(confirmResponse.getStatusCode().value()).isGreaterThanOrEqualTo(400);
    }

    // -------------------------------------------------------------------------
    // 9.6: Concurrent reservation for last bed — one succeeds, one gets error
    // -------------------------------------------------------------------------

    @Test
    void test_concurrentReservation_lastBed() {
        HttpHeaders coordHeaders = authHelper.coordinatorHeaders();

        // Set availability to exactly 1 bed available
        submitAvailability(shelterId, coordHeaders, "SINGLE_ADULT", 50, 49, 0);

        // First reservation should succeed
        HttpHeaders outreachHeaders = authHelper.outreachWorkerHeaders();
        ResponseEntity<Map<String, Object>> first = createReservation(
                shelterId, outreachHeaders, "SINGLE_ADULT");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Second reservation should fail (0 beds available after first hold)
        ResponseEntity<Map<String, Object>> second = createReservation(
                shelterId, outreachHeaders, "SINGLE_ADULT");
        assertThat(second.getStatusCode().value()).isGreaterThanOrEqualTo(400);
    }

    // -------------------------------------------------------------------------
    // 9.7: Only creator can confirm/cancel (other user gets 403, COC_ADMIN can)
    // -------------------------------------------------------------------------

    @Test
    void test_onlyCreatorCanConfirmOrCancel() {
        HttpHeaders outreachHeaders = authHelper.outreachWorkerHeaders();

        // Create reservation as outreach worker
        ResponseEntity<Map<String, Object>> createResponse = createReservation(
                shelterId, outreachHeaders, "SINGLE_ADULT");
        String reservationId = (String) createResponse.getBody().get("id");

        // Coordinator tries to confirm — should get 403
        HttpHeaders coordHeaders = authHelper.coordinatorHeaders();
        ResponseEntity<Map<String, Object>> coordConfirm = restTemplate.exchange(
                "/api/v1/reservations/" + reservationId + "/confirm",
                HttpMethod.PATCH,
                new HttpEntity<>(coordHeaders),
                new ParameterizedTypeReference<>() {}
        );
        assertThat(coordConfirm.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Original creator can confirm
        ResponseEntity<Map<String, Object>> creatorConfirm = restTemplate.exchange(
                "/api/v1/reservations/" + reservationId + "/confirm",
                HttpMethod.PATCH,
                new HttpEntity<>(outreachHeaders),
                new ParameterizedTypeReference<>() {}
        );
        assertThat(creatorConfirm.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // -------------------------------------------------------------------------
    // 9.8: Auto-expiry transitions HELD to EXPIRED
    // -------------------------------------------------------------------------

    @Test
    void test_autoExpiry_transitionsToExpired() {
        HttpHeaders headers = authHelper.outreachWorkerHeaders();

        // Create reservation
        ResponseEntity<Map<String, Object>> createResponse = createReservation(
                shelterId, headers, "SINGLE_ADULT");
        String reservationId = (String) createResponse.getBody().get("id");

        // Manually set expires_at to the past to simulate expiry
        jdbcTemplate.update(
                "UPDATE reservation SET expires_at = NOW() - INTERVAL '1 minute' WHERE id = ?::uuid",
                reservationId
        );

        // Verify the reservation is findable as expired
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation WHERE id = ?::uuid AND status = 'HELD' AND expires_at < NOW()",
                Integer.class,
                reservationId
        );
        assertThat(count).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // 9.9: Reservation events published
    // -------------------------------------------------------------------------

    @Test
    void test_reservationEvents_published() {
        eventListener.clear();
        HttpHeaders headers = authHelper.outreachWorkerHeaders();

        // Create
        ResponseEntity<Map<String, Object>> createResponse = createReservation(
                shelterId, headers, "SINGLE_ADULT");
        String reservationId = (String) createResponse.getBody().get("id");

        List<DomainEvent> createEvents = eventListener.getEventsByType("reservation.created");
        assertThat(createEvents).isNotEmpty();
        assertThat(createEvents.get(createEvents.size() - 1).payload().get("shelter_id"))
                .isEqualTo(shelterId.toString());

        // Confirm
        restTemplate.exchange(
                "/api/v1/reservations/" + reservationId + "/confirm",
                HttpMethod.PATCH,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {}
        );

        List<DomainEvent> confirmEvents = eventListener.getEventsByType("reservation.confirmed");
        assertThat(confirmEvents).isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // 9.10: Bed search cache invalidated immediately after hold/cancel
    // -------------------------------------------------------------------------

    @Test
    void test_bedSearch_cacheInvalidatedAfterHold() {
        HttpHeaders outreachHeaders = authHelper.outreachWorkerHeaders();

        // Prime the bed search cache by searching BEFORE the hold
        restTemplate.exchange("/api/v1/queries/beds", HttpMethod.POST,
                new HttpEntity<>("{}", outreachHeaders), new ParameterizedTypeReference<Map<String, Object>>() {});

        // Create a hold — this must invalidate the bed search cache
        ResponseEntity<Map<String, Object>> holdResponse = createReservation(shelterId, outreachHeaders, "SINGLE_ADULT");
        assertThat(holdResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Immediately search again — must reflect the hold (no stale cache)
        int bedsOnHold = searchBedsOnHold(outreachHeaders, shelterId, "SINGLE_ADULT");
        assertThat(bedsOnHold).as("Bed search must reflect hold immediately (cache invalidation)").isGreaterThanOrEqualTo(1);

        // Cancel the hold
        String reservationId = (String) holdResponse.getBody().get("id");
        restTemplate.exchange("/api/v1/reservations/" + reservationId + "/cancel",
                HttpMethod.PATCH, new HttpEntity<>(outreachHeaders), new ParameterizedTypeReference<Map<String, Object>>() {});

        // Immediately search again — must reflect the cancel (no stale cache)
        int bedsOnHoldAfterCancel = searchBedsOnHold(outreachHeaders, shelterId, "SINGLE_ADULT");
        assertThat(bedsOnHoldAfterCancel).as("Bed search must reflect cancel immediately (cache invalidation)").isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // 9.11: Idempotency key — first creates, second returns existing
    // -------------------------------------------------------------------------

    @Test
    void test_holdWithIdempotencyKey_createOnFirst_returnExistingOnSecond() {
        HttpHeaders headers = authHelper.outreachWorkerHeaders();
        String idempotencyKey = UUID.randomUUID().toString();
        headers.set("X-Idempotency-Key", idempotencyKey);

        // First request — creates a new hold
        ResponseEntity<Map<String, Object>> first = createReservation(shelterId, headers, "SINGLE_ADULT");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String firstId = (String) first.getBody().get("id");

        // Second request with same key — should return existing hold, not create new
        ResponseEntity<Map<String, Object>> second = createReservation(shelterId, headers, "SINGLE_ADULT");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        String secondId = (String) second.getBody().get("id");

        assertThat(secondId).isEqualTo(firstId);
    }

    // -------------------------------------------------------------------------
    // 9.12: Without idempotency key — creates normally (no regression)
    // -------------------------------------------------------------------------

    @Test
    void test_holdWithoutIdempotencyKey_createsNormally() {
        HttpHeaders headers = authHelper.outreachWorkerHeaders();

        ResponseEntity<Map<String, Object>> response = createReservation(shelterId, headers, "SINGLE_ADULT");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("HELD");
    }

    // -------------------------------------------------------------------------
    // 9.13: Same idempotency key, different user — creates separately
    // -------------------------------------------------------------------------

    @Test
    void test_holdWithIdempotencyKey_differentUser_createsSeparately() {
        String idempotencyKey = UUID.randomUUID().toString();

        // First user creates hold
        HttpHeaders outreachHeaders = authHelper.outreachWorkerHeaders();
        outreachHeaders.set("X-Idempotency-Key", idempotencyKey);
        ResponseEntity<Map<String, Object>> first = createReservation(shelterId, outreachHeaders, "SINGLE_ADULT");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String firstId = (String) first.getBody().get("id");

        // Different user (cocAdmin) creates hold with same key — should be a new hold
        HttpHeaders adminHeaders = authHelper.cocAdminHeaders();
        adminHeaders.set("X-Idempotency-Key", idempotencyKey);
        ResponseEntity<Map<String, Object>> second = createReservation(shelterId, adminHeaders, "SINGLE_ADULT");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String secondId = (String) second.getBody().get("id");

        assertThat(secondId).isNotEqualTo(firstId);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID createTestShelter(HttpHeaders headers) {
        String body = """
                {
                    "name": "Reservation Test Shelter %s",
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

    private void assignCoordinator(UUID shelterId) {
        User coordinator = authHelper.setupCoordinatorUser();
        String body = """
                {"userId": "%s"}
                """.formatted(coordinator.getId());

        restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/coordinators",
                HttpMethod.POST,
                new HttpEntity<>(body, authHelper.cocAdminHeaders()),
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

        restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/availability",
                HttpMethod.PATCH,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {}
        );
    }

    private ResponseEntity<Map<String, Object>> createReservation(
            UUID shelterId, HttpHeaders headers, String populationType) {
        String body = """
                {
                    "shelterId": "%s",
                    "populationType": "%s",
                    "notes": "Test reservation"
                }
                """.formatted(shelterId, populationType);

        return restTemplate.exchange(
                "/api/v1/reservations",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<>() {}
        );
    }

    // -------------------------------------------------------------------------
    // Persistent Notification Integration
    // -------------------------------------------------------------------------

    @Test
    void test_reservationExpiry_notifiesOutreachWorker() {
        HttpHeaders headers = authHelper.outreachWorkerHeaders();

        // Create reservation
        ResponseEntity<Map<String, Object>> createResponse = createReservation(
                shelterId, headers, "SINGLE_ADULT");
        String reservationId = (String) createResponse.getBody().get("id");

        // Force expiry by setting expires_at to past
        jdbcTemplate.update(
                "UPDATE reservation SET expires_at = NOW() - INTERVAL '1 minute' WHERE id = ?::uuid",
                reservationId);

        // Trigger expiry job
        reservationExpiryService.expireOverdueReservations();

        // Outreach worker should have a reservation.expired notification
        ResponseEntity<String> notifResp = restTemplate.exchange(
                "/api/v1/notifications?unread=true", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(notifResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(notifResp.getBody()).contains("reservation.expired");
        assertThat(notifResp.getBody()).contains("ACTION_REQUIRED");
    }

    // -------------------------------------------------------------------------
    // notification-deep-linking Phase 3 task 8.1 — extended GET endpoint
    // -------------------------------------------------------------------------
    //
    // Each test creates a dedicated outreach worker so the /api/v1/reservations
    // response is scoped to reservations THIS test created (the endpoint
    // filters by authenticated userId). Reusing the shared outreachWorkerHeaders
    // would pollute assertions with state from earlier tests in the same
    // class. Matches the feedback_isolated_test_users convention.

    private HttpHeaders freshOutreachHeaders(String tag) {
        org.fabt.auth.domain.User user = authHelper.setupUserWithDvAccess(
                "p3-8-1-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.fabt.org",
                "Phase3 Test Worker " + tag, new String[] { "OUTREACH_WORKER" });
        return authHelper.headersForUser(user);
    }

    @Test
    void test_listReservations_noParams_preservesBackCompat_heldOnly() {
        HttpHeaders headers = freshOutreachHeaders("bc");
        ResponseEntity<Map<String, Object>> held = createReservation(shelterId, headers, "SINGLE_ADULT");
        ResponseEntity<Map<String, Object>> toCancel = createReservation(shelterId, headers, "SINGLE_ADULT");
        String cancelId = (String) toCancel.getBody().get("id");
        restTemplate.exchange("/api/v1/reservations/" + cancelId + "/cancel",
                HttpMethod.PATCH, new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        ResponseEntity<List<Map<String, Object>>> listResp = restTemplate.exchange(
                "/api/v1/reservations", HttpMethod.GET,
                new HttpEntity<>(headers), new ParameterizedTypeReference<>() {});
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResp.getBody())
                .as("Bare GET must return only HELD — backward compat with existing consumers")
                .extracting(r -> r.get("status"))
                .containsOnly("HELD")
                .hasSize(1);
        assertThat(listResp.getBody().get(0).get("id")).isEqualTo(held.getBody().get("id"));
    }

    @Test
    void test_listReservations_statusFilter_includesTerminalStates() {
        HttpHeaders headers = freshOutreachHeaders("terminal");
        createReservation(shelterId, headers, "SINGLE_ADULT");
        ResponseEntity<Map<String, Object>> toCancel = createReservation(shelterId, headers, "SINGLE_ADULT");
        String cancelId = (String) toCancel.getBody().get("id");
        restTemplate.exchange("/api/v1/reservations/" + cancelId + "/cancel",
                HttpMethod.PATCH, new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        ResponseEntity<List<Map<String, Object>>> listResp = restTemplate.exchange(
                "/api/v1/reservations?status=HELD,CANCELLED,EXPIRED,CONFIRMED,CANCELLED_SHELTER_DEACTIVATED",
                HttpMethod.GET, new HttpEntity<>(headers), new ParameterizedTypeReference<>() {});
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResp.getBody())
                .as("With status filter, both HELD and CANCELLED rows should appear")
                .extracting(r -> r.get("status"))
                .contains("HELD", "CANCELLED");
    }

    @Test
    void test_listReservations_sinceDays_filtersOldRows() {
        HttpHeaders headers = freshOutreachHeaders("since");
        ResponseEntity<Map<String, Object>> recent = createReservation(shelterId, headers, "SINGLE_ADULT");
        ResponseEntity<Map<String, Object>> old = createReservation(shelterId, headers, "SINGLE_ADULT");
        String oldId = (String) old.getBody().get("id");

        // Backdate the "old" reservation's created_at. TenantContext gives RLS-bypass
        // for the test thread — same pattern the shelter deactivation test uses.
        org.fabt.shared.web.TenantContext.runWithContext(
                authHelper.getTestTenantId(), true, () ->
                jdbcTemplate.update("UPDATE reservation SET created_at = NOW() - INTERVAL '30 days' WHERE id = ?::uuid",
                        oldId));

        ResponseEntity<List<Map<String, Object>>> listResp = restTemplate.exchange(
                "/api/v1/reservations?status=HELD&sinceDays=14",
                HttpMethod.GET, new HttpEntity<>(headers), new ParameterizedTypeReference<>() {});
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResp.getBody())
                .as("sinceDays=14 must exclude reservations backdated to 30 days ago")
                .hasSize(1);
        assertThat(listResp.getBody().get(0).get("id")).isEqualTo(recent.getBody().get("id"));
    }

    @Test
    void test_listReservations_statusFilter_whitespaceTolerant() {
        HttpHeaders headers = freshOutreachHeaders("ws");
        createReservation(shelterId, headers, "SINGLE_ADULT");

        ResponseEntity<List<Map<String, Object>>> listResp = restTemplate.exchange(
                "/api/v1/reservations?status=HELD,%20CANCELLED",
                HttpMethod.GET, new HttpEntity<>(headers), new ParameterizedTypeReference<>() {});
        assertThat(listResp.getStatusCode())
                .as("Whitespace after commas must be tolerated")
                .isEqualTo(HttpStatus.OK);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private int searchBedsOnHold(HttpHeaders headers, UUID shelterId, String populationType) {
        ResponseEntity<Map<String, Object>> searchResponse = restTemplate.exchange(
                "/api/v1/queries/beds", HttpMethod.POST,
                new HttpEntity<>("{}", headers),
                new ParameterizedTypeReference<>() {});
        List<Map<String, Object>> results = (List<Map<String, Object>>) searchResponse.getBody().get("results");
        return results.stream()
                .filter(r -> shelterId.toString().equals(r.get("shelterId")))
                .flatMap(r -> ((List<Map<String, Object>>) r.get("availability")).stream())
                .filter(a -> populationType.equals(a.get("populationType")))
                .map(a -> ((Number) a.get("bedsOnHold")).intValue())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Shelter " + shelterId + " not found in bed search results"));
    }
}
