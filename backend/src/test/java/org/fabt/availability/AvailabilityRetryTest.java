package org.fabt.availability;

import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.availability.service.AvailabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Integration tests for server-side retry on availability updates.
 *
 * Uses @MockitoSpyBean on AvailabilityService to simulate transient
 * DataAccessException on the first call, then let the real method
 * run on retry. Tests the full Spring AOP proxy chain (AvailabilityRetryService
 * → AvailabilityService).
 */
@DisplayName("Availability Retry")
class AvailabilityRetryTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestEventListener eventListener;

    @MockitoSpyBean
    private AvailabilityService availabilityService;

    private UUID shelterId;

    @BeforeEach
    void setUp() {
        // Reset spy state from prior tests — prevents doThrow/doCallRealMethod leakage
        Mockito.reset(availabilityService);

        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        authHelper.setupCocAdminUser();
        var coordinator = authHelper.setupCoordinatorUser();

        eventListener.clear();

        // Create a test shelter
        shelterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO shelter (id, tenant_id, name, dv_shelter, created_at, updated_at) VALUES (?, ?, ?, false, NOW(), NOW())",
                shelterId, authHelper.getTestTenantId(), "Retry Test Shelter");
        jdbcTemplate.update(
                "INSERT INTO shelter_constraints (shelter_id, population_types_served) VALUES (?, ?)",
                shelterId, new String[]{"SINGLE_ADULT"});
        // Assign coordinator (reuse the same instance, don't call setup twice)
        jdbcTemplate.update(
                "INSERT INTO coordinator_assignment (user_id, shelter_id) VALUES (?, ?)",
                coordinator.getId(), shelterId);
    }

    // T-18a: Retry succeeds on second attempt, single domain event
    @Test
    @DisplayName("Transient DataAccessException retried — succeeds on second attempt with one event")
    void retryOnDataAccessException_succeedsOnSecondAttempt() {
        // Make first call throw, second call succeed (real method)
        Mockito.doThrow(new TransientDataAccessResourceException("Simulated transient DB failure"))
                .doCallRealMethod()
                .when(availabilityService)
                .createSnapshot(any(UUID.class), any(), anyInt(), anyInt(), anyInt(),
                        anyBoolean(), any(), any(), anyInt());

        HttpHeaders headers = authHelper.coordinatorHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/availability",
                HttpMethod.PATCH,
                new HttpEntity<>("""
                        {"populationType":"SINGLE_ADULT","bedsTotal":50,"bedsOccupied":30,"bedsOnHold":0,"acceptingNewGuests":true}
                        """, headers),
                String.class);

        // Should succeed (retry worked)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify createSnapshot was called exactly 2 times (first throw, second succeed)
        Mockito.verify(availabilityService, Mockito.times(2))
                .createSnapshot(any(UUID.class), any(), anyInt(), anyInt(), anyInt(),
                        anyBoolean(), any(), any(), anyInt());

        // Verify only ONE domain event published (not one per attempt)
        var events = eventListener.getEventsByType("availability.updated");
        assertThat(events).as("Should publish exactly one event on successful retry").hasSize(1);
    }

    // T-18b: Retry executes in fresh transaction
    @Test
    @DisplayName("Retry executes in fresh transaction — second attempt not rollback-only")
    void retryGivesFreshTransaction() {
        // Same spy setup — first fails, second succeeds
        Mockito.doThrow(new TransientDataAccessResourceException("Transaction rolled back"))
                .doCallRealMethod()
                .when(availabilityService)
                .createSnapshot(any(UUID.class), any(), anyInt(), anyInt(), anyInt(),
                        anyBoolean(), any(), any(), anyInt());

        HttpHeaders headers = authHelper.coordinatorHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/availability",
                HttpMethod.PATCH,
                new HttpEntity<>("""
                        {"populationType":"SINGLE_ADULT","bedsTotal":50,"bedsOccupied":25,"bedsOnHold":0,"acceptingNewGuests":true}
                        """, headers),
                String.class);

        // If the second attempt inherited a rollback-only transaction, it would fail
        assertThat(response.getStatusCode())
                .as("Second attempt should succeed in a fresh transaction")
                .isEqualTo(HttpStatus.OK);

        // Verify data was actually persisted
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bed_availability WHERE shelter_id = ? AND population_type = 'SINGLE_ADULT'",
                Integer.class, shelterId);
        assertThat(count).as("Snapshot should be persisted after successful retry").isGreaterThanOrEqualTo(1);
    }

    // T-18c: Business exception NOT retried
    @Test
    @DisplayName("AvailabilityInvariantViolation is not retried — returns 422 immediately")
    void businessException_notRetried() {
        HttpHeaders headers = authHelper.coordinatorHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        // beds_occupied > beds_total → AvailabilityInvariantViolation (INV-2)
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/availability",
                HttpMethod.PATCH,
                new HttpEntity<>("""
                        {"populationType":"SINGLE_ADULT","bedsTotal":10,"bedsOccupied":20,"bedsOnHold":0,"acceptingNewGuests":true}
                        """, headers),
                String.class);

        // Should return 422 (not retried, not 500)
        assertThat(response.getStatusCode().value())
                .as("Business exception should propagate immediately as 422")
                .isEqualTo(422);

        // Verify createSnapshot was called only ONCE (not retried)
        Mockito.verify(availabilityService, Mockito.times(1))
                .createSnapshot(any(UUID.class), any(), anyInt(), anyInt(), anyInt(),
                        anyBoolean(), any(), any(), anyInt());
    }
}
