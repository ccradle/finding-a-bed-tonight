package org.fabt.reservation;

import java.util.Map;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.reservation.service.ReservationExpiryService;
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

/**
 * Endpoint tests for the offline-hold path
 * ({@code POST /api/v1/shelters/{shelterId}/manual-hold}) introduced by the
 * bed-hold-integrity change (Issue #102 RCA).
 */
class OfflineHoldEndpointTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ReservationExpiryService reservationExpiryService;
    @Autowired private CoordinatorAssignmentRepository coordinatorAssignmentRepository;
    @Autowired private MeterRegistry meterRegistry;

    private UUID shelterId;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        authHelper.setupCocAdminUser();
        authHelper.setupCoordinatorUser();
        authHelper.setupOutreachWorkerUser();

        shelterId = createTestShelter(authHelper.cocAdminHeaders());
        assignCoordinator(shelterId);
        submitAvailability(shelterId, authHelper.coordinatorHeaders(), "SINGLE_ADULT", 50, 10, 0);
    }

    // Production-path coverage: an assigned coordinator can successfully create
    // an offline hold via the HTTP path. Added 2026-04-11 (war room) after the
    // SecurityConfig.java:172 fix — previously the SecurityConfig POST
    // /shelters/** rule excluded COORDINATOR, so coordinator calls were 403'd
    // at the filter chain before reaching the controller. The cocAdmin-only
    // coverage masked that production bug. Riley Cho's gating ask: the
    // coordinator success path MUST be tested with real coordinatorHeaders(),
    // not an admin bypass.
    @Test
    void coordinator_creates_offline_hold_succeeds_when_assigned() {
        ResponseEntity<Map<String, Object>> response = postManualHold(
                authHelper.coordinatorHeaders(),
                "SINGLE_ADULT",
                "Phone call from intake — assigned coordinator path");
        assertThat(response.getStatusCode())
                .as("Assigned coordinator must reach the controller and create the hold "
                        + "(regression guard for SecurityConfig filter rule — Issue #102 RCA)")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("HELD");
        assertThat((String) response.getBody().get("notes"))
                .contains("Manual offline hold");
        assertThat(response.getBody().get("expiresAt")).isNotNull();
    }

    @Test
    void coc_admin_creates_offline_hold_succeeds() {
        ResponseEntity<Map<String, Object>> response = postManualHold(
                authHelper.cocAdminHeaders(),
                "SINGLE_ADULT",
                "Phone call from intake — expected guest in 30 minutes");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("HELD");
        assertThat((String) response.getBody().get("notes"))
                .as("Notes must be prefixed with the manual-hold marker")
                .contains("Manual offline hold");
        assertThat(response.getBody().get("expiresAt")).isNotNull();
    }

    @Test
    void offline_hold_increments_beds_on_hold() {
        Integer holdBefore = latestBedsOnHold();
        assertThat(holdBefore).isEqualTo(0);

        postManualHold(authHelper.cocAdminHeaders(), "SINGLE_ADULT", "test");

        Integer holdAfter = latestBedsOnHold();
        assertThat(holdAfter)
                .as("Manual hold must drive a fresh snapshot via the recompute path")
                .isEqualTo(1);
    }

    @Test
    void offline_hold_expires_via_existing_lifecycle() {
        ResponseEntity<Map<String, Object>> created = postManualHold(
                authHelper.cocAdminHeaders(), "SINGLE_ADULT", "Will be force-expired");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String reservationId = (String) created.getBody().get("id");

        // Force expiry by setting expires_at to past
        jdbcTemplate.update(
                "UPDATE reservation SET expires_at = NOW() - INTERVAL '1 minute' WHERE id = ?::uuid",
                reservationId);
        reservationExpiryService.expireOverdueReservations();

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM reservation WHERE id = ?::uuid",
                String.class, reservationId);
        assertThat(status)
                .as("Offline hold must transition to EXPIRED through the standard lifecycle")
                .isEqualTo("EXPIRED");

        Integer holdAfterExpiry = latestBedsOnHold();
        assertThat(holdAfterExpiry)
                .as("beds_on_hold must decrement after expiry — proves the recompute path fires")
                .isEqualTo(0);
    }

    @Test
    void coordinator_not_assigned_to_shelter_403() {
        // Create a SECOND shelter that the coordinator is NOT assigned to
        UUID otherShelterId = createTestShelter(authHelper.cocAdminHeaders());
        // Note: no assignCoordinator() call for this shelter

        // Capture the access_denied counter value BEFORE the call. This lets
        // the test distinguish a controller-level 403 (GlobalExceptionHandler
        // increments the counter via its @ExceptionHandler) from a filter-level
        // 403 (SecurityConfig's inline accessDeniedHandler writes the same
        // JSON shape but does NOT increment this counter). The distinction
        // matters: if this test ever fails the counter-increment assertion,
        // the coordinator request was rejected at the filter chain instead
        // of at the controller's isAssigned() branch — which is exactly the
        // regression that Issue #102 RCA caught (SecurityConfig.java:172).
        // Per Riley Cho, war room 2026-04-11.
        double counterBefore = accessDeniedCount();

        String body = """
                {
                    "populationType": "SINGLE_ADULT",
                    "reason": "Should be rejected"
                }
                """;
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/shelters/" + otherShelterId + "/manual-hold",
                HttpMethod.POST,
                new HttpEntity<>(body, authHelper.coordinatorHeaders()),
                new ParameterizedTypeReference<>() {}
        );
        assertThat(response.getStatusCode())
                .as("Coordinator not assigned to shelter must be rejected")
                .isEqualTo(HttpStatus.FORBIDDEN);

        double counterAfter = accessDeniedCount();
        assertThat(counterAfter - counterBefore)
                .as("Rejection MUST come from the controller body (isAssigned branch), "
                        + "not from the SecurityConfig filter chain. "
                        + "GlobalExceptionHandler.handleAccessDenied increments this "
                        + "counter; the filter's inline accessDeniedHandler does not. "
                        + "Regression guard for Issue #102 RCA.")
                .isEqualTo(1.0);
    }

    private double accessDeniedCount() {
        // Sum across all tagged variants (role, path_prefix) — the counter
        // is registered in GlobalExceptionHandler with tags so there may be
        // multiple meter instances.
        return meterRegistry.find("fabt.http.access_denied.count")
                .counters()
                .stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    @Test
    void outreach_worker_role_rejected() {
        // Outreach workers do not have COORDINATOR/COC_ADMIN/PLATFORM_ADMIN — must be 403
        String body = """
                {
                    "populationType": "SINGLE_ADULT",
                    "reason": "Outreach should not be allowed"
                }
                """;
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/manual-hold",
                HttpMethod.POST,
                new HttpEntity<>(body, authHelper.outreachWorkerHeaders()),
                new ParameterizedTypeReference<>() {}
        );
        assertThat(response.getStatusCode())
                .as("Outreach worker role must not be authorized for offline hold creation")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private ResponseEntity<Map<String, Object>> postManualHold(
            HttpHeaders headers, String populationType, String reason) {
        String body = """
                {
                    "populationType": "%s",
                    "reason": "%s"
                }
                """.formatted(populationType, reason);
        return restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/manual-hold",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<>() {}
        );
    }

    private Integer latestBedsOnHold() {
        return jdbcTemplate.queryForObject(
                """
                SELECT beds_on_hold FROM bed_availability
                WHERE shelter_id = ? AND population_type = 'SINGLE_ADULT'
                ORDER BY snapshot_ts DESC LIMIT 1
                """,
                Integer.class, shelterId);
    }

    private UUID createTestShelter(HttpHeaders headers) {
        String body = """
                {
                    "name": "Offline Hold Test Shelter %s",
                    "addressStreet": "789 Offline Way",
                    "addressCity": "Raleigh",
                    "addressState": "NC",
                    "addressZip": "27601",
                    "phone": "919-555-0400",
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
                "/api/v1/shelters", HttpMethod.POST,
                new HttpEntity<>(body, headers), ShelterResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private void assignCoordinator(UUID shelterId) {
        UUID coordinatorId = authHelper.setupCoordinatorUser().getId();
        coordinatorAssignmentRepository.assign(coordinatorId, shelterId);
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
}
