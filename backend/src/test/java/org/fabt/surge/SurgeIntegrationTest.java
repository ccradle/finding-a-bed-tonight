package org.fabt.surge;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.availability.TestEventListener;
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

class SurgeIntegrationTest extends BaseIntegrationTest {

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

        // Clean up any active surges from previous tests
        jdbcTemplate.update("DELETE FROM surge_event WHERE tenant_id = ?", authHelper.getTestTenantId());

        // Create a shelter for availability tests
        shelterId = createTestShelter();

        // Assign coordinator
        authHelper.setupCoordinatorUser();
        String assignBody = """
                {"userId": "%s"}
                """.formatted(authHelper.setupCoordinatorUser().getId());
        restTemplate.exchange("/api/v1/shelters/" + shelterId + "/coordinators",
                HttpMethod.POST, new HttpEntity<>(assignBody, authHelper.cocAdminHeaders()), Void.class);
    }

    // -------------------------------------------------------------------------
    // 10.1: Activate surge, verify event
    // -------------------------------------------------------------------------

    @Test
    void test_activateSurge_statusActive_eventPublished() {
        eventListener.clear();
        HttpHeaders headers = authHelper.cocAdminHeaders();

        ResponseEntity<Map<String, Object>> response = activateSurge(headers,
                "White Flag — overnight low below 32°F");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("ACTIVE");
        assertThat(response.getBody().get("reason")).isEqualTo("White Flag — overnight low below 32°F");

        List<DomainEvent> events = eventListener.getEventsByType("surge.activated");
        assertThat(events).isNotEmpty();
        assertThat(events.get(events.size() - 1).payload().get("affected_shelter_count")).isNotNull();
    }

    // -------------------------------------------------------------------------
    // 10.2: Deactivate surge, verify event
    // -------------------------------------------------------------------------

    @Test
    void test_deactivateSurge_statusDeactivated_eventPublished() {
        eventListener.clear();
        HttpHeaders headers = authHelper.cocAdminHeaders();

        ResponseEntity<Map<String, Object>> createResponse = activateSurge(headers, "Test surge");
        String surgeId = (String) createResponse.getBody().get("id");

        ResponseEntity<Map<String, Object>> deactivateResponse = restTemplate.exchange(
                "/api/v1/surge-events/" + surgeId + "/deactivate",
                HttpMethod.PATCH, new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});

        assertThat(deactivateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deactivateResponse.getBody().get("status")).isEqualTo("DEACTIVATED");

        List<DomainEvent> events = eventListener.getEventsByType("surge.deactivated");
        assertThat(events).isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // 10.3: Activate fails when surge already active
    // -------------------------------------------------------------------------

    @Test
    void test_activateSurge_alreadyActive_returns409() {
        HttpHeaders headers = authHelper.cocAdminHeaders();

        activateSurge(headers, "First surge");
        ResponseEntity<Map<String, Object>> second = activateSurge(headers, "Second surge");

        assertThat(second.getStatusCode().value()).isGreaterThanOrEqualTo(400);
    }

    // -------------------------------------------------------------------------
    // 10.4: Outreach worker cannot activate
    // -------------------------------------------------------------------------

    @Test
    void test_outreachWorker_cannotActivate_returns403() {
        HttpHeaders headers = authHelper.outreachWorkerHeaders();

        ResponseEntity<Map<String, Object>> response = activateSurge(headers, "Should fail");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // -------------------------------------------------------------------------
    // 10.5: Auto-expiry
    // -------------------------------------------------------------------------

    @Test
    void test_autoExpiry_scheduledSurgeExpires() {
        HttpHeaders headers = authHelper.cocAdminHeaders();

        // Create surge with scheduled_end in the past
        String body = """
                {"reason": "Expiry test", "scheduledEnd": "2020-01-01T00:00:00Z"}
                """;
        restTemplate.exchange("/api/v1/surge-events", HttpMethod.POST,
                new HttpEntity<>(body, headers), new ParameterizedTypeReference<Map<String, Object>>() {});

        // Verify it's findable as expired
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM surge_event WHERE tenant_id = ? AND status = 'ACTIVE' AND scheduled_end < NOW()",
                Integer.class, authHelper.getTestTenantId());
        assertThat(count).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // 10.6: Availability update with overflowBeds
    // -------------------------------------------------------------------------

    @Test
    void test_availabilityUpdate_withOverflowBeds() {
        HttpHeaders coordHeaders = authHelper.coordinatorHeaders();

        String body = """
                {
                    "populationType": "SINGLE_ADULT",
                    "bedsTotal": 50, "bedsOccupied": 30, "bedsOnHold": 0,
                    "acceptingNewGuests": true, "overflowBeds": 15
                }
                """;
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/availability",
                HttpMethod.PATCH, new HttpEntity<>(body, coordHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // -------------------------------------------------------------------------
    // 10.7: Bed search includes surgeActive flag during active surge
    // -------------------------------------------------------------------------

    @Test
    void test_bedSearch_surgeActive_flag() {
        HttpHeaders cocHeaders = authHelper.cocAdminHeaders();
        HttpHeaders outreachHeaders = authHelper.outreachWorkerHeaders();

        // Activate surge
        activateSurge(cocHeaders, "White Flag test");

        // Search beds
        ResponseEntity<Map<String, Object>> searchResponse = restTemplate.exchange(
                "/api/v1/queries/beds", HttpMethod.POST,
                new HttpEntity<>("{}", outreachHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(searchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) searchResponse.getBody().get("results");
        if (!results.isEmpty()) {
            assertThat(results.get(0).get("surgeActive")).isEqualTo(true);
        }
    }

    // -------------------------------------------------------------------------
    // 10.8: No surge indicator when no active surge
    // -------------------------------------------------------------------------

    @Test
    void test_bedSearch_noSurge_noFlag() {
        HttpHeaders outreachHeaders = authHelper.outreachWorkerHeaders();

        ResponseEntity<Map<String, Object>> searchResponse = restTemplate.exchange(
                "/api/v1/queries/beds", HttpMethod.POST,
                new HttpEntity<>("{}", outreachHeaders),
                new ParameterizedTypeReference<>() {});

        assertThat(searchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) searchResponse.getBody().get("results");
        if (!results.isEmpty()) {
            assertThat(results.get(0).get("surgeActive")).isEqualTo(false);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID createTestShelter() {
        String body = """
                {
                    "name": "Surge Test Shelter %s",
                    "addressStreet": "123 Main St", "addressCity": "Raleigh",
                    "addressState": "NC", "addressZip": "27601",
                    "dvShelter": false,
                    "constraints": {
                        "sobrietyRequired": false, "idRequired": false,
                        "referralRequired": false, "petsAllowed": true,
                        "wheelchairAccessible": true,
                        "populationTypesServed": ["SINGLE_ADULT"]
                    },
                    "capacities": [{"populationType": "SINGLE_ADULT", "bedsTotal": 50}]
                }
                """.formatted(UUID.randomUUID().toString().substring(0, 8));

        ResponseEntity<ShelterResponse> response = restTemplate.exchange(
                "/api/v1/shelters", HttpMethod.POST,
                new HttpEntity<>(body, authHelper.cocAdminHeaders()), ShelterResponse.class);
        return response.getBody().id();
    }

    // -------------------------------------------------------------------------
    // Persistent Notification Integration
    // -------------------------------------------------------------------------

    @Test
    void test_activateSurge_createsNotificationForCoordinators() {
        HttpHeaders headers = authHelper.cocAdminHeaders();
        activateSurge(headers, "White Flag — below 32°F");

        // Coordinator should have a CRITICAL surge notification
        ResponseEntity<String> notifResp = restTemplate.exchange(
                "/api/v1/notifications?unread=true", HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()), String.class);
        assertThat(notifResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(notifResp.getBody()).contains("surge.activated");
        assertThat(notifResp.getBody()).contains("CRITICAL");
    }

    @Test
    void test_deactivateSurge_createsInfoNotificationForCoordinators() {
        HttpHeaders headers = authHelper.cocAdminHeaders();

        // Activate first
        ResponseEntity<Map<String, Object>> createResponse = activateSurge(headers, "Test surge for deactivate");
        String surgeId = (String) createResponse.getBody().get("id");

        // Deactivate
        restTemplate.exchange(
                "/api/v1/surge-events/" + surgeId + "/deactivate",
                HttpMethod.PATCH, new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        // Coordinator should have an INFO surge.deactivated notification
        ResponseEntity<String> notifResp = restTemplate.exchange(
                "/api/v1/notifications?unread=true", HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()), String.class);
        assertThat(notifResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(notifResp.getBody()).contains("surge.deactivated");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ResponseEntity<Map<String, Object>> activateSurge(HttpHeaders headers, String reason) {
        String body = """
                {"reason": "%s"}
                """.formatted(reason);
        return restTemplate.exchange("/api/v1/surge-events", HttpMethod.POST,
                new HttpEntity<>(body, headers), new ParameterizedTypeReference<>() {});
    }
}
