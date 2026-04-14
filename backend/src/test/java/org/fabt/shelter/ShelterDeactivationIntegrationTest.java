package org.fabt.shelter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
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
 * Integration tests for shelter activate/deactivate lifecycle (Issue #108).
 * Tests: deactivation, reactivation, hold cascade, DV safety gate,
 * availability guard, hold guard, role-based access, and audit events.
 */
class ShelterDeactivationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        authHelper.setupCocAdminUser();
        authHelper.setupCoordinatorUser();
        authHelper.setupOutreachWorkerUser();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ShelterResponse createShelter(HttpHeaders headers) {
        String body = """
                {
                    "name": "Deactivation Test Shelter %s",
                    "addressStreet": "100 Test Ave",
                    "addressCity": "Raleigh",
                    "addressState": "NC",
                    "addressZip": "27601",
                    "phone": "919-555-0108",
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
                        {"populationType": "SINGLE_ADULT", "bedsTotal": 20}
                    ]
                }
                """.formatted(UUID.randomUUID().toString().substring(0, 8));

        ResponseEntity<ShelterResponse> response = restTemplate.exchange(
                "/api/v1/shelters", HttpMethod.POST,
                new HttpEntity<>(body, headers), ShelterResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ShelterResponse createDvShelter(HttpHeaders headers) {
        String body = """
                {
                    "name": "DV Deactivation Test %s",
                    "addressStreet": "200 Safe Haven Rd",
                    "addressCity": "Raleigh",
                    "addressState": "NC",
                    "addressZip": "27601",
                    "phone": "919-555-0777",
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
                "/api/v1/shelters", HttpMethod.POST,
                new HttpEntity<>(body, headers), ShelterResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ResponseEntity<String> deactivate(UUID shelterId, String reason,
                                               boolean confirmDv, HttpHeaders headers) {
        String body = """
                {"reason": "%s", "confirmDv": %s}
                """.formatted(reason, confirmDv);
        return restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/deactivate", HttpMethod.PATCH,
                new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> reactivate(UUID shelterId, HttpHeaders headers) {
        return restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/reactivate", HttpMethod.PATCH,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> holdBed(UUID shelterId, String populationType,
                                            HttpHeaders headers) {
        String body = """
                {"shelterId": "%s", "populationType": "%s", "notes": "test hold"}
                """.formatted(shelterId, populationType);
        return restTemplate.exchange(
                "/api/v1/reservations", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    /**
     * Query audit_events directly via JDBC, bypassing RLS.
     * Same pattern as DvReferralIntegrationTest and EscalationPolicyEndpointTest.
     */
    private String queryAuditEvent(String action, UUID shelterId) {
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            conn.createStatement().execute("RESET ROLE");
            var rs = conn.createStatement().executeQuery(
                    "SELECT details::text FROM audit_events WHERE action = '" + action
                    + "' AND details::text LIKE '%" + shelterId + "%'"
                    + " ORDER BY timestamp DESC LIMIT 1");
            String result = rs.next() ? rs.getString(1) : null;
            conn.createStatement().execute("SET ROLE fabt_app");
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to query audit events", e);
        }
    }

    // -------------------------------------------------------------------------
    // 7.1 — Deactivate shelter with no holds
    // -------------------------------------------------------------------------

    @Test
    void test_deactivate_noHolds_setsMetadataAndExcludesFromSearch() {
        HttpHeaders headers = authHelper.cocAdminHeaders();
        ShelterResponse shelter = createShelter(headers);

        // Deactivate
        ResponseEntity<String> response = deactivate(
                shelter.id(), "TEMPORARY_CLOSURE", false, headers);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify response includes deactivation metadata
        assertThat(response.getBody()).contains("\"active\":false");
        assertThat(response.getBody()).contains("\"deactivationReason\":\"TEMPORARY_CLOSURE\"");
        assertThat(response.getBody()).contains("\"deactivatedAt\"");

        // Verify shelter is excluded from filtered bed search
        ResponseEntity<List<Map<String, Object>>> searchResponse = restTemplate.exchange(
                "/api/v1/shelters?populationType=SINGLE_ADULT", HttpMethod.GET,
                new HttpEntity<>(headers), new ParameterizedTypeReference<>() {});
        assertThat(searchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        @SuppressWarnings("unchecked")
        List<UUID> searchIds = searchResponse.getBody().stream()
                .map(item -> {
                    Map<String, Object> s = (Map<String, Object>) item.get("shelter");
                    return UUID.fromString((String) s.get("id"));
                })
                .toList();
        assertThat(searchIds).doesNotContain(shelter.id());

        // C-4 fix: verify audit event was created
        String auditDetails = queryAuditEvent("SHELTER_DEACTIVATED", shelter.id());
        assertThat(auditDetails)
                .as("SHELTER_DEACTIVATED audit event must exist for shelter %s", shelter.id())
                .isNotNull();
        assertThat(auditDetails).contains("TEMPORARY_CLOSURE");
    }

    // -------------------------------------------------------------------------
    // 7.2 — Deactivate shelter with active holds → cascade cancellation
    // -------------------------------------------------------------------------

    @Test
    void test_deactivate_withHolds_cancelsHoldsAndNotifies() {
        HttpHeaders adminHeaders = authHelper.cocAdminHeaders();
        ShelterResponse shelter = createShelter(adminHeaders);

        // Ensure availability exists with beds — coordinator PATCH
        User coordinator = authHelper.setupCoordinatorUser();
        restTemplate.exchange(
                "/api/v1/shelters/" + shelter.id() + "/coordinators", HttpMethod.POST,
                new HttpEntity<>("""
                        {"userId": "%s"}
                        """.formatted(coordinator.getId()), adminHeaders), Void.class);
        HttpHeaders coordHeaders = authHelper.coordinatorHeaders();
        String availBody = """
                {"populationType": "SINGLE_ADULT", "bedsTotal": 20, "bedsOccupied": 0, "acceptingNewGuests": true}
                """;
        ResponseEntity<String> availResponse = restTemplate.exchange(
                "/api/v1/shelters/" + shelter.id() + "/availability", HttpMethod.PATCH,
                new HttpEntity<>(availBody, coordHeaders), String.class);
        assertThat(availResponse.getStatusCode())
                .as("Availability update must succeed — body: %s", availResponse.getBody())
                .isEqualTo(HttpStatus.OK);

        // C-1 fix: create hold with dedicated worker and assert it succeeds unconditionally
        User holdWorker = authHelper.setupUserWithDvAccess(
                "hold-cascade-worker@test.com", "Hold Cascade Worker", new String[]{"OUTREACH_WORKER"});
        HttpHeaders holdWorkerHeaders = authHelper.headersForUser(holdWorker);

        ResponseEntity<String> holdResponse = holdBed(
                shelter.id(), "SINGLE_ADULT", holdWorkerHeaders);
        assertThat(holdResponse.getStatusCode())
                .as("Hold creation must succeed (20 total, 0 occupied) — body: %s", holdResponse.getBody())
                .isEqualTo(HttpStatus.CREATED);

        // Deactivate — should cascade-cancel the hold
        ResponseEntity<String> deactResponse = deactivate(
                shelter.id(), "CODE_VIOLATION", false, adminHeaders);
        assertThat(deactResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deactResponse.getBody()).contains("\"active\":false");

        // Worker's active holds should now be empty (cascade cancelled)
        ResponseEntity<List<Map<String, Object>>> myHolds = restTemplate.exchange(
                "/api/v1/reservations", HttpMethod.GET,
                new HttpEntity<>(holdWorkerHeaders), new ParameterizedTypeReference<>() {});
        assertThat(myHolds.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(myHolds.getBody())
                .as("Hold worker should have zero active HELD reservations after cascade")
                .isEmpty();

        // notification-deep-linking (Issue #106) — task 0a.3:
        // HOLD_CANCELLED_SHELTER_DEACTIVATED payload must include reservationId
        // so the frontend can deep-link to /outreach/my-holds?reservationId=X.
        ResponseEntity<String> workerNotifResponse = restTemplate.exchange(
                "/api/v1/notifications", HttpMethod.GET,
                new HttpEntity<>(holdWorkerHeaders), String.class);
        assertThat(workerNotifResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(workerNotifResponse.getBody())
                .as("Outreach worker must receive HOLD_CANCELLED_SHELTER_DEACTIVATED notification")
                .contains("HOLD_CANCELLED_SHELTER_DEACTIVATED");
        assertThat(workerNotifResponse.getBody())
                .as("Hold-cancelled notification payload must include reservationId for deep-linking")
                .contains("reservationId");
        assertThat(workerNotifResponse.getBody())
                .as("Hold-cancelled notification payload must include shelterId for context")
                .contains("shelterId");
    }

    // -------------------------------------------------------------------------
    // 7.3 — DV shelter deactivation: confirm gate
    // -------------------------------------------------------------------------

    @Test
    void test_deactivate_dvShelter_withoutConfirm_returns409() {
        User dvAdmin = authHelper.setupUserWithDvAccess(
                "dvdeact@test.com", "DV Deact Admin", new String[]{"COC_ADMIN"});
        HttpHeaders dvHeaders = authHelper.headersForUser(dvAdmin);

        ShelterResponse dvShelter = createDvShelter(dvHeaders);

        // C-2 fix: create a DV referral with ALL required fields
        User dvOutreach = authHelper.setupUserWithDvAccess(
                "dvoutreach-deact@test.com", "DV Outreach", new String[]{"OUTREACH_WORKER"});
        HttpHeaders dvOutreachHeaders = authHelper.headersForUser(dvOutreach);

        String referralBody = """
                {
                    "shelterId": "%s",
                    "populationType": "DV_SURVIVOR",
                    "householdSize": 1,
                    "callbackNumber": "919-555-0001",
                    "urgency": "STANDARD"
                }
                """.formatted(dvShelter.id());
        ResponseEntity<String> referralResponse = restTemplate.exchange(
                "/api/v1/dv-referrals", HttpMethod.POST,
                new HttpEntity<>(referralBody, dvOutreachHeaders), String.class);
        assertThat(referralResponse.getStatusCode())
                .as("DV referral creation must succeed — body: %s", referralResponse.getBody())
                .isEqualTo(HttpStatus.CREATED);

        // Attempt deactivation WITHOUT confirmDv — should be blocked
        ResponseEntity<String> deactResponse = deactivate(
                dvShelter.id(), "PERMANENT_CLOSURE", false, dvHeaders);
        assertThat(deactResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(deactResponse.getBody()).contains("DV_CONFIRMATION_REQUIRED");
        assertThat(deactResponse.getBody()).contains("pendingDvReferrals");

        // Now confirm — should succeed
        ResponseEntity<String> confirmResponse = deactivate(
                dvShelter.id(), "PERMANENT_CLOSURE", true, dvHeaders);
        assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmResponse.getBody()).contains("\"active\":false");
    }

    // -------------------------------------------------------------------------
    // 7.4 — DV deactivation notification restricted to dvAccess users
    // -------------------------------------------------------------------------

    @Test
    void test_deactivate_dvShelter_notificationRestricted() {
        User dvAdmin = authHelper.setupUserWithDvAccess(
                "dvnotif@test.com", "DV Notif Admin", new String[]{"COC_ADMIN"});
        HttpHeaders dvHeaders = authHelper.headersForUser(dvAdmin);

        ShelterResponse dvShelter = createDvShelter(dvHeaders);

        // Deactivate (no pending referrals → no confirmation needed)
        ResponseEntity<String> deactResponse = deactivate(
                dvShelter.id(), "TEMPORARY_CLOSURE", false, dvHeaders);
        assertThat(deactResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // C-3 fix: assert notification EXISTS for dvAccess admin, then verify no address
        ResponseEntity<String> notifResponse = restTemplate.exchange(
                "/api/v1/notifications", HttpMethod.GET,
                new HttpEntity<>(dvHeaders), String.class);
        assertThat(notifResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(notifResponse.getBody())
                .as("DV admin must receive SHELTER_DEACTIVATED notification")
                .contains("SHELTER_DEACTIVATED");
        assertThat(notifResponse.getBody())
                .as("Notification must NOT contain shelter address (VAWA)")
                .doesNotContain("200 Safe Haven Rd")
                .doesNotContain("addressStreet");

        // notification-deep-linking (Issue #106) — task 0a.3:
        // SHELTER_DEACTIVATED payload must include shelterId for deep-linking.
        // Marcus Webb war-room: shelterId is an opaque UUID, not a VAWA leak.
        assertThat(notifResponse.getBody())
                .as("SHELTER_DEACTIVATED notification payload must include shelterId for deep-linking")
                .contains("shelterId")
                .contains(dvShelter.id().toString());

        // Non-dvAccess user should NOT have the DV deactivation notification
        User nonDvWorker = authHelper.setupUserWithDvAccess(
                "nondv-notif-check@test.com", "Non-DV Worker", new String[]{"OUTREACH_WORKER"});
        // Override: create without dvAccess (setupUserWithDvAccess always sets true,
        // so use the shared outreach worker which has dvAccess=false)
        HttpHeaders nonDvHeaders = authHelper.outreachWorkerHeaders();
        ResponseEntity<String> nonDvNotifResponse = restTemplate.exchange(
                "/api/v1/notifications", HttpMethod.GET,
                new HttpEntity<>(nonDvHeaders), String.class);
        assertThat(nonDvNotifResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(nonDvNotifResponse.getBody())
                .as("Non-dvAccess user must NOT receive SHELTER_DEACTIVATED notification")
                .doesNotContain("SHELTER_DEACTIVATED");
    }

    // -------------------------------------------------------------------------
    // 7.5 — Reactivate shelter
    // -------------------------------------------------------------------------

    @Test
    void test_reactivate_clearsMetadataAndReappearsInSearch() {
        HttpHeaders headers = authHelper.cocAdminHeaders();
        ShelterResponse shelter = createShelter(headers);

        // Deactivate first
        deactivate(shelter.id(), "SEASONAL_END", false, headers);

        // Reactivate
        ResponseEntity<String> response = reactivate(shelter.id(), headers);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"active\":true");
        // Metadata should be cleared — deactivationReason absent or null
        assertThat(response.getBody()).doesNotContain("\"deactivationReason\":\"SEASONAL_END\"");

        // Shelter reappears in search
        ResponseEntity<List<Map<String, Object>>> searchResponse = restTemplate.exchange(
                "/api/v1/shelters?populationType=SINGLE_ADULT", HttpMethod.GET,
                new HttpEntity<>(headers), new ParameterizedTypeReference<>() {});

        @SuppressWarnings("unchecked")
        List<UUID> searchIds = searchResponse.getBody().stream()
                .map(item -> {
                    Map<String, Object> s = (Map<String, Object>) item.get("shelter");
                    return UUID.fromString((String) s.get("id"));
                })
                .toList();
        assertThat(searchIds).contains(shelter.id());

        // C-4 fix: verify SHELTER_REACTIVATED audit event
        String auditDetails = queryAuditEvent("SHELTER_REACTIVATED", shelter.id());
        assertThat(auditDetails)
                .as("SHELTER_REACTIVATED audit event must exist for shelter %s", shelter.id())
                .isNotNull();
        assertThat(auditDetails).contains("SEASONAL_END"); // previous reason recorded
    }

    // -------------------------------------------------------------------------
    // 7.6 — Idempotency guards
    // -------------------------------------------------------------------------

    @Test
    void test_deactivate_alreadyInactive_returns409() {
        HttpHeaders headers = authHelper.cocAdminHeaders();
        ShelterResponse shelter = createShelter(headers);

        deactivate(shelter.id(), "TEMPORARY_CLOSURE", false, headers);

        ResponseEntity<String> second = deactivate(
                shelter.id(), "TEMPORARY_CLOSURE", false, headers);
        // IllegalStateException mapped to 409 by GlobalExceptionHandler
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void test_reactivate_alreadyActive_returns409() {
        HttpHeaders headers = authHelper.cocAdminHeaders();
        ShelterResponse shelter = createShelter(headers);

        ResponseEntity<String> response = reactivate(shelter.id(), headers);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // -------------------------------------------------------------------------
    // 7.7 — Guards: availability update + hold creation on inactive shelter
    // -------------------------------------------------------------------------

    @Test
    void test_availabilityUpdate_inactiveShelter_returns409() {
        HttpHeaders adminHeaders = authHelper.cocAdminHeaders();
        ShelterResponse shelter = createShelter(adminHeaders);

        // Assign coordinator and deactivate
        User coordinator = authHelper.setupCoordinatorUser();
        restTemplate.exchange(
                "/api/v1/shelters/" + shelter.id() + "/coordinators", HttpMethod.POST,
                new HttpEntity<>("""
                        {"userId": "%s"}
                        """.formatted(coordinator.getId()), adminHeaders), Void.class);

        deactivate(shelter.id(), "CODE_VIOLATION", false, adminHeaders);

        // Coordinator tries to update availability
        HttpHeaders coordHeaders = authHelper.coordinatorHeaders();
        String availBody = """
                {"populationType": "SINGLE_ADULT", "bedsTotal": 15, "bedsOccupied": 5, "acceptingNewGuests": true}
                """;
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/shelters/" + shelter.id() + "/availability", HttpMethod.PATCH,
                new HttpEntity<>(availBody, coordHeaders), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void test_holdCreation_inactiveShelter_blocked() {
        HttpHeaders adminHeaders = authHelper.cocAdminHeaders();
        ShelterResponse shelter = createShelter(adminHeaders);

        deactivate(shelter.id(), "FUNDING_LOSS", false, adminHeaders);

        // Outreach worker tries to hold a bed
        HttpHeaders outreachHeaders = authHelper.outreachWorkerHeaders();
        ResponseEntity<String> response = holdBed(
                shelter.id(), "SINGLE_ADULT", outreachHeaders);

        // IllegalStateException → 409 via GlobalExceptionHandler
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // -------------------------------------------------------------------------
    // 7.8 — Role-based access: non-admin cannot deactivate
    // -------------------------------------------------------------------------

    @Test
    void test_coordinator_cannotDeactivate() {
        HttpHeaders adminHeaders = authHelper.cocAdminHeaders();
        ShelterResponse shelter = createShelter(adminHeaders);

        HttpHeaders coordHeaders = authHelper.coordinatorHeaders();
        ResponseEntity<String> response = deactivate(
                shelter.id(), "TEMPORARY_CLOSURE", false, coordHeaders);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void test_outreachWorker_cannotDeactivate() {
        HttpHeaders adminHeaders = authHelper.cocAdminHeaders();
        ShelterResponse shelter = createShelter(adminHeaders);

        HttpHeaders outreachHeaders = authHelper.outreachWorkerHeaders();
        ResponseEntity<String> response = deactivate(
                shelter.id(), "TEMPORARY_CLOSURE", false, outreachHeaders);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // -------------------------------------------------------------------------
    // Invalid reason → 400 Bad Request
    // -------------------------------------------------------------------------

    @Test
    void test_deactivate_invalidReason_returns400() {
        HttpHeaders headers = authHelper.cocAdminHeaders();
        ShelterResponse shelter = createShelter(headers);

        ResponseEntity<String> response = deactivate(
                shelter.id(), "NOT_A_REAL_REASON", false, headers);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("INVALID_REASON");
    }
}
