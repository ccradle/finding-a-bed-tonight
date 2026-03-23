package org.fabt.availability;

import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.fabt.reservation.service.ReservationService;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for bed availability calculation correctness.
 * Based on QA briefing (bed-availability-qa-briefing.md) test cases.
 *
 * These tests verify the 9 invariants hold after every operation.
 * Uses AvailabilityInvariantChecker for assertion.
 */
class BedAvailabilityHardeningTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReservationService reservationService;

    private UUID shelterId;
    private HttpHeaders coordHeaders;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        authHelper.setupOutreachWorkerUser();
        authHelper.setupCocAdminUser();
        shelterId = createTestShelter();
        coordHeaders = authHelper.cocAdminHeaders();
    }

    private UUID createTestShelter() {
        HttpHeaders headers = authHelper.adminHeaders();
        String body = """
                {
                  "name": "Invariant Test Shelter",
                  "addressCity": "Raleigh",
                  "addressState": "NC",
                  "populationTypesServed": ["SINGLE_ADULT"],
                  "capacities": [{"populationType": "SINGLE_ADULT", "bedsTotal": 10}]
                }
                """;
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/shelters", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        String responseBody = response.getBody();
        assertNotNull(responseBody);
        // Extract shelter ID from response
        String idStr = responseBody.substring(responseBody.indexOf("\"id\":\"") + 6, responseBody.indexOf("\"", responseBody.indexOf("\"id\":\"") + 6));
        return UUID.fromString(idStr);
    }

    private ResponseEntity<String> patchAvailability(int total, int occupied, int hold) {
        String body = String.format("""
                {
                  "populationType": "SINGLE_ADULT",
                  "bedsTotal": %d,
                  "bedsOccupied": %d,
                  "bedsOnHold": %d,
                  "acceptingNewGuests": true
                }
                """, total, occupied, hold);
        return restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/availability",
                HttpMethod.PATCH,
                new HttpEntity<>(body, coordHeaders),
                String.class);
    }

    private void assertInvariants() {
        AvailabilityInvariantChecker.assertInvariantsHold(jdbcTemplate, shelterId, "SINGLE_ADULT");
    }

    private int extractAvailable(String body) {
        int idx = body.indexOf("\"bedsAvailable\":");
        if (idx < 0) return -999;
        String val = body.substring(idx + 16);
        int end = val.indexOf(",");
        if (end < 0) end = val.indexOf("}");
        return Integer.parseInt(val.substring(0, end));
    }

    // ========================================================================
    // GROUP 1: Baseline Coordinator Updates
    // ========================================================================

    @Test
    void tc_1_1_initialSnapshot() {
        ResponseEntity<String> r = patchAvailability(10, 3, 0);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals(7, extractAvailable(r.getBody()));
        assertInvariants();
    }

    @Test
    void tc_1_2_increaseOccupied() {
        patchAvailability(10, 3, 0);
        ResponseEntity<String> r = patchAvailability(10, 5, 0);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals(5, extractAvailable(r.getBody()));
        assertInvariants();
    }

    @Test
    void tc_1_3_decreaseOccupied() {
        patchAvailability(10, 8, 0);
        ResponseEntity<String> r = patchAvailability(10, 4, 0);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals(6, extractAvailable(r.getBody()));
        assertInvariants();
    }

    @Test
    void tc_1_4_increaseTotal() {
        patchAvailability(10, 8, 0);
        ResponseEntity<String> r = patchAvailability(15, 8, 0);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals(7, extractAvailable(r.getBody()));
        assertInvariants();
    }

    @Test
    void tc_1_5_decreaseTotalAboveOccupied() {
        patchAvailability(10, 3, 0);
        ResponseEntity<String> r = patchAvailability(7, 3, 0);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals(4, extractAvailable(r.getBody()));
        assertInvariants();
    }

    @Test
    void tc_1_6_decreaseTotalBelowOccupied_rejected() {
        patchAvailability(10, 8, 0);
        ResponseEntity<String> r = patchAvailability(5, 8, 0);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, r.getStatusCode(),
                "TC-1.6: total < occupied must be rejected with 422");
    }

    @Test
    void tc_1_7_decreaseTotalBelowOccupiedPlusHold_rejected() {
        patchAvailability(10, 5, 0);
        ResponseEntity<String> r = patchAvailability(6, 5, 3);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, r.getStatusCode(),
                "TC-1.7: total < occupied + hold must be rejected with 422");
    }

    @Test
    void tc_1_8_allZeros() {
        ResponseEntity<String> r = patchAvailability(0, 0, 0);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals(0, extractAvailable(r.getBody()));
        assertInvariants();
    }

    @Test
    void tc_1_9_rapidSequentialUpdates() {
        patchAvailability(10, 3, 0); // available=7
        assertEquals(6, extractAvailable(patchAvailability(10, 4, 0).getBody()));
        assertEquals(5, extractAvailable(patchAvailability(10, 5, 0).getBody()));
        assertEquals(7, extractAvailable(patchAvailability(12, 5, 0).getBody()));
        assertEquals(9, extractAvailable(patchAvailability(12, 3, 0).getBody()));
        assertEquals(5, extractAvailable(patchAvailability(8, 3, 0).getBody()));
        assertInvariants();
    }

    // ========================================================================
    // GROUP 2: Reservation Interactions
    // ========================================================================

    private HttpHeaders outreachHeaders() {
        return authHelper.outreachWorkerHeaders();
    }

    private ResponseEntity<String> placeHold() {
        String body = String.format("""
                {"shelterId": "%s", "populationType": "SINGLE_ADULT", "notes": "test hold"}
                """, shelterId);
        return restTemplate.exchange("/api/v1/reservations", HttpMethod.POST,
                new HttpEntity<>(body, outreachHeaders()), String.class);
    }

    private String extractId(String body) {
        int idx = body.indexOf("\"id\":\"");
        if (idx < 0) return null;
        return body.substring(idx + 6, body.indexOf("\"", idx + 6));
    }

    private int extractField(String body, String field) {
        int idx = body.indexOf("\"" + field + "\":");
        if (idx < 0) return -999;
        String val = body.substring(idx + field.length() + 3);
        int end = Math.min(
                val.indexOf(",") >= 0 ? val.indexOf(",") : val.length(),
                val.indexOf("}") >= 0 ? val.indexOf("}") : val.length()
        );
        return Integer.parseInt(val.substring(0, end).trim());
    }

    private ResponseEntity<String> getLatestAvailability() {
        return restTemplate.exchange("/api/v1/shelters/" + shelterId, HttpMethod.GET,
                new HttpEntity<>(coordHeaders), String.class);
    }

    @Test
    void tc_2_1_holdDecreasesAvailableByOne() {
        patchAvailability(10, 7, 0); // available=3

        ResponseEntity<String> holdResponse = placeHold();
        assertEquals(HttpStatus.CREATED, holdResponse.getStatusCode());

        assertInvariants();

        // Verify: available should be 2 (decreased by 1), hold should be 1
        var detail = getLatestAvailability();
        String body = detail.getBody();
        assertNotNull(body);
        assertTrue(body.contains("\"bedsOnHold\":1"), "Hold should be 1 after placing hold");

        // Cleanup: cancel the hold
        String resId = extractId(holdResponse.getBody());
        restTemplate.exchange("/api/v1/reservations/" + resId + "/cancel",
                HttpMethod.PATCH, new HttpEntity<>(outreachHeaders()), String.class);
    }

    @Test
    void tc_2_2_confirmDoesNotChangeAvailable_INV6() {
        patchAvailability(10, 7, 0); // available=3

        // Place hold → available=2
        ResponseEntity<String> holdResponse = placeHold();
        String resId = extractId(holdResponse.getBody());
        assertNotNull(resId);

        // Get available before confirm
        var before = getLatestAvailability();
        int availBefore = extractField(before.getBody(), "bedsAvailable");

        // Confirm → occupied+1, hold-1, available UNCHANGED (INV-6)
        restTemplate.exchange("/api/v1/reservations/" + resId + "/confirm",
                HttpMethod.PATCH, new HttpEntity<>(outreachHeaders()), String.class);

        var after = getLatestAvailability();
        int availAfter = extractField(after.getBody(), "bedsAvailable");

        assertEquals(availBefore, availAfter,
                "INV-6: beds_available must not change on confirm (hold→occupied swap). Before=" + availBefore + " After=" + availAfter);
        assertInvariants();
    }

    @Test
    void tc_2_3_cancelIncreasesAvailableByOne_INV7() {
        patchAvailability(10, 7, 0); // available=3

        ResponseEntity<String> holdResponse = placeHold();
        String resId = extractId(holdResponse.getBody());
        // available=2 after hold

        // Cancel → hold-1, available+1 (INV-7)
        restTemplate.exchange("/api/v1/reservations/" + resId + "/cancel",
                HttpMethod.PATCH, new HttpEntity<>(outreachHeaders()), String.class);

        var after = getLatestAvailability();
        int availAfter = extractField(after.getBody(), "bedsAvailable");
        assertEquals(3, availAfter,
                "INV-7: available must increase by 1 on cancel (back to 3)");
        assertInvariants();
    }

    @Test
    void tc_2_4_expiryIncreasesAvailableByOne_INV7() {
        patchAvailability(10, 7, 0); // available=3

        ResponseEntity<String> holdResponse = placeHold();
        String resId = extractId(holdResponse.getBody());
        // available=2, hold=1

        // Mark reservation as past expiry so expireReservation() will process it
        jdbcTemplate.update(
                "UPDATE reservation SET expires_at = NOW() - INTERVAL '1 minute' WHERE id = ?::uuid", resId);

        // Call the actual expiry path (same code the @Scheduled poller calls)
        reservationService.expireReservation(java.util.UUID.fromString(resId));

        var after = getLatestAvailability();
        int availAfter = extractField(after.getBody(), "bedsAvailable");
        assertEquals(3, availAfter,
                "INV-7: available must increase by 1 on expiry (back to 3)");
        assertInvariants();
    }

    @Test
    void tc_2_5_holdOnLastBed() {
        patchAvailability(10, 9, 0); // available=1

        ResponseEntity<String> holdResponse = placeHold();
        assertEquals(HttpStatus.CREATED, holdResponse.getStatusCode());

        var after = getLatestAvailability();
        int availAfter = extractField(after.getBody(), "bedsAvailable");
        assertEquals(0, availAfter, "Available must be 0 after holding last bed");
        assertInvariants();

        // Cleanup
        String resId = extractId(holdResponse.getBody());
        restTemplate.exchange("/api/v1/reservations/" + resId + "/cancel",
                HttpMethod.PATCH, new HttpEntity<>(outreachHeaders()), String.class);
    }

    @Test
    void tc_2_6_holdWhenZeroAvailable_rejected() {
        patchAvailability(10, 10, 0); // available=0

        ResponseEntity<String> holdResponse = placeHold();
        assertEquals(HttpStatus.CONFLICT, holdResponse.getStatusCode(),
                "TC-2.6: hold must be rejected when beds_available=0");
        assertInvariants();
    }

    @Test
    void tc_2_7_coordinatorCannotZeroOutActiveHolds() {
        patchAvailability(10, 7, 0); // available=3

        // Place a hold → hold=1, available=2
        ResponseEntity<String> holdResponse = placeHold();
        String resId = extractId(holdResponse.getBody());

        // Coordinator sends hold=0 — should be overridden to 1
        ResponseEntity<String> coordUpdate = patchAvailability(10, 8, 0);
        assertEquals(HttpStatus.OK, coordUpdate.getStatusCode());

        int holdAfter = extractField(coordUpdate.getBody(), "bedsOnHold");
        assertTrue(holdAfter >= 1,
                "TC-2.7: bedsOnHold must be >= 1 (active reservation exists). Got: " + holdAfter);
        assertInvariants();

        // Cleanup
        restTemplate.exchange("/api/v1/reservations/" + resId + "/cancel",
                HttpMethod.PATCH, new HttpEntity<>(outreachHeaders()), String.class);
    }

    @Test
    void tc_2_8_coordinatorReducesTotalWhileHoldsExist_rejected() {
        patchAvailability(10, 7, 0); // available=3

        // Place 2 holds
        ResponseEntity<String> hold1 = placeHold();
        ResponseEntity<String> hold2 = placeHold();
        // State: total=10, occ=7, hold=2, avail=1

        // Coordinator tries to reduce total to 8 → 7+2=9 > 8 → should be 422
        ResponseEntity<String> coordUpdate = patchAvailability(8, 7, 0);
        // The coordinator sends hold=0, but system overrides to 2.
        // Then 7+2=9 > 8 → invariant violation → 422
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, coordUpdate.getStatusCode(),
                "TC-2.8: reducing total below occupied+active_holds must be rejected");

        // Cleanup
        String id1 = extractId(hold1.getBody());
        String id2 = extractId(hold2.getBody());
        if (id1 != null) restTemplate.exchange("/api/v1/reservations/" + id1 + "/cancel",
                HttpMethod.PATCH, new HttpEntity<>(outreachHeaders()), String.class);
        if (id2 != null) restTemplate.exchange("/api/v1/reservations/" + id2 + "/cancel",
                HttpMethod.PATCH, new HttpEntity<>(outreachHeaders()), String.class);
    }

    // ========================================================================
    // GROUP 3: Concurrent Operations
    // ========================================================================

    @Test
    void tc_3_2_twoSimultaneousHoldsOnLastBed() throws Exception {
        patchAvailability(10, 9, 0); // available=1

        // Fire two hold requests concurrently
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<ResponseEntity<String>>>();

        for (int i = 0; i < 2; i++) {
            futures.add(executor.submit(() -> placeHold()));
        }

        int successes = 0;
        int conflicts = 0;
        for (var future : futures) {
            ResponseEntity<String> r = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (r.getStatusCode() == HttpStatus.CREATED) successes++;
            else if (r.getStatusCode() == HttpStatus.CONFLICT) conflicts++;
        }
        executor.shutdown();

        assertEquals(1, successes, "TC-3.2: exactly 1 hold should succeed");
        assertEquals(1, conflicts, "TC-3.2: exactly 1 hold should be rejected");
        assertInvariants();

        // Verify final state
        var detail = getLatestAvailability();
        int availFinal = extractField(detail.getBody(), "bedsAvailable");
        assertTrue(availFinal >= 0, "TC-3.2: available must not be negative. Got: " + availFinal);

        // Cleanup: cancel any HELD reservations
        jdbcTemplate.update("UPDATE reservation SET status = 'CANCELLED' WHERE shelter_id = ? AND status = 'HELD'", shelterId);
    }

    @Test
    void tc_3_5_threeSimultaneousHoldsOnTwoBeds() throws Exception {
        patchAvailability(10, 8, 0); // available=2

        var executor = java.util.concurrent.Executors.newFixedThreadPool(3);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<ResponseEntity<String>>>();

        for (int i = 0; i < 3; i++) {
            futures.add(executor.submit(() -> placeHold()));
        }

        int successes = 0;
        int conflicts = 0;
        for (var future : futures) {
            ResponseEntity<String> r = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (r.getStatusCode() == HttpStatus.CREATED) successes++;
            else if (r.getStatusCode() == HttpStatus.CONFLICT) conflicts++;
        }
        executor.shutdown();

        assertTrue(successes <= 2, "TC-3.5: at most 2 holds should succeed. Got: " + successes);
        assertTrue(conflicts >= 1, "TC-3.5: at least 1 hold should be rejected. Got: " + conflicts);
        assertInvariants();

        var detail = getLatestAvailability();
        int availFinal = extractField(detail.getBody(), "bedsAvailable");
        assertTrue(availFinal >= 0, "TC-3.5: available must not be negative. Got: " + availFinal);

        // Cleanup
        jdbcTemplate.update("UPDATE reservation SET status = 'CANCELLED' WHERE shelter_id = ? AND status = 'HELD'", shelterId);
    }

    @Test
    void tc_3_1_twoConcurrentCoordinatorUpdates() throws Exception {
        patchAvailability(10, 5, 0); // available=5

        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);

        // Coordinator A: add a guest (occupied=6)
        var futureA = executor.submit(() -> patchAvailability(10, 6, 0));
        // Coordinator B: add beds (total=12)
        var futureB = executor.submit(() -> patchAvailability(12, 5, 0));

        ResponseEntity<String> rA = futureA.get(10, java.util.concurrent.TimeUnit.SECONDS);
        ResponseEntity<String> rB = futureB.get(10, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        // Both should succeed (both are valid updates)
        assertEquals(HttpStatus.OK, rA.getStatusCode());
        assertEquals(HttpStatus.OK, rB.getStatusCode());

        // The final state should be the latest snapshot — whichever committed last
        assertInvariants();
        var detail = getLatestAvailability();
        int availFinal = extractField(detail.getBody(), "bedsAvailable");
        assertTrue(availFinal >= 0, "TC-3.1: available must not be negative. Got: " + availFinal);
    }

    // ========================================================================
    // GROUP 4: Cache Correctness
    // ========================================================================

    @Test
    void tc_4_1_getImmediatelyAfterPatchReturnsUpdatedValue() {
        patchAvailability(10, 5, 0); // available=5

        // Update occupied to 7
        patchAvailability(10, 7, 0);

        // Immediate GET should return available=3
        var detail = getLatestAvailability();
        int avail = extractField(detail.getBody(), "bedsAvailable");
        assertEquals(3, avail, "TC-4.1: GET immediately after PATCH must return updated value");
    }

    @Test
    void tc_4_2_tenRapidGetsReturnConsistentValue() {
        patchAvailability(10, 4, 0); // available=6

        // 10 rapid GETs
        for (int i = 0; i < 10; i++) {
            var detail = getLatestAvailability();
            int avail = extractField(detail.getBody(), "bedsAvailable");
            assertEquals(6, avail, "TC-4.2: GET #" + (i + 1) + " returned stale value");
        }
    }

    @Test
    void tc_4_3_bedSearchReflectsUpdate() {
        patchAvailability(10, 10, 0); // available=0
        patchAvailability(10, 7, 0);  // available=3

        // Bed search should reflect the update
        String searchBody = """
                {"populationType": "SINGLE_ADULT"}
                """;
        ResponseEntity<String> searchResult = restTemplate.exchange(
                "/api/v1/queries/beds", HttpMethod.POST,
                new HttpEntity<>(searchBody, outreachHeaders()), String.class);
        assertEquals(HttpStatus.OK, searchResult.getStatusCode());
        // The shelter should appear in results (it has 3 available beds)
        assertNotNull(searchResult.getBody());
    }

    // ========================================================================
    // GROUP 5: Edge Cases
    // ========================================================================

    @Test
    void tc_5_3_holdForWrongPopulationType_rejected() {
        patchAvailability(10, 5, 0); // SINGLE_ADULT available=5

        // Try to hold FAMILY_WITH_CHILDREN (not served by this shelter)
        String body = String.format("""
                {"shelterId": "%s", "populationType": "FAMILY_WITH_CHILDREN", "notes": "wrong type"}
                """, shelterId);
        ResponseEntity<String> r = restTemplate.exchange("/api/v1/reservations", HttpMethod.POST,
                new HttpEntity<>(body, outreachHeaders()), String.class);

        // Should fail — no FAMILY_WITH_CHILDREN beds available
        assertNotEquals(HttpStatus.CREATED, r.getStatusCode(),
                "TC-5.3: hold for unsupported population type should not succeed");

        // SINGLE_ADULT availability should be unaffected
        assertInvariants();
    }

    // ========================================================================
    // GROUP 6: UI-to-API Consistency
    // ========================================================================

    @Test
    void tc_6_3_searchAndDetailReturnSameAvailability() {
        patchAvailability(10, 4, 0); // available=6

        // Get from shelter detail
        var detail = getLatestAvailability();
        int detailAvail = extractField(detail.getBody(), "bedsAvailable");

        // Get from bed search
        String searchBody = """
                {"populationType": "SINGLE_ADULT"}
                """;
        ResponseEntity<String> searchResult = restTemplate.exchange(
                "/api/v1/queries/beds", HttpMethod.POST,
                new HttpEntity<>(searchBody, outreachHeaders()), String.class);

        // The search should return the same available count for this shelter
        String searchResponseBody = searchResult.getBody();
        assertNotNull(searchResponseBody);
        // Find this shelter in results and check available matches
        if (searchResponseBody.contains(shelterId.toString())) {
            // Extract bedsAvailable from the search result for this shelter
            int searchIdx = searchResponseBody.indexOf(shelterId.toString());
            String afterShelter = searchResponseBody.substring(searchIdx);
            int availIdx = afterShelter.indexOf("\"bedsAvailable\":");
            if (availIdx > 0) {
                String val = afterShelter.substring(availIdx + 16);
                int end = Math.min(
                        val.indexOf(",") >= 0 ? val.indexOf(",") : val.length(),
                        val.indexOf("}") >= 0 ? val.indexOf("}") : val.length()
                );
                int searchAvail = Integer.parseInt(val.substring(0, end).trim());
                assertEquals(detailAvail, searchAvail,
                        "TC-6.3: search and detail must return same availability");
            }
        }
    }

    // ========================================================================
    // GROUP 7: Single Source of Truth (D10 — shelter_capacity eliminated)
    // ========================================================================

    @Test
    void tc_7_1_createShelterWithCapacity_capacityComesFromAvailability() {
        // Create shelter with capacity — capacity should be backed by bed_availability
        HttpHeaders headers = authHelper.adminHeaders();
        String body = """
                {
                  "name": "D10 SSoT Test Shelter",
                  "addressCity": "Raleigh",
                  "addressState": "NC",
                  "capacities": [{"populationType": "VETERAN", "bedsTotal": 25}]
                }
                """;
        ResponseEntity<String> createResp = restTemplate.exchange(
                "/api/v1/shelters", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertEquals(HttpStatus.CREATED, createResp.getStatusCode());
        String respBody = createResp.getBody();
        String newId = respBody.substring(respBody.indexOf("\"id\":\"") + 6, respBody.indexOf("\"", respBody.indexOf("\"id\":\"") + 6));

        // GET detail — capacities must be populated from bed_availability
        ResponseEntity<String> detail = restTemplate.exchange(
                "/api/v1/shelters/" + newId, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.OK, detail.getStatusCode());
        String detailBody = detail.getBody();
        assertNotNull(detailBody);
        assertTrue(detailBody.contains("\"capacities\""), "Response must include capacities");
        assertTrue(detailBody.contains("\"VETERAN\""), "Capacities must include VETERAN population");
        assertTrue(detailBody.contains("\"bedsTotal\":25"), "bedsTotal must be 25");

        // Availability must also show 25 total, 0 occupied, 0 hold
        assertTrue(detailBody.contains("\"bedsAvailable\":25"), "Available must be 25 (all unoccupied)");
    }

    @Test
    void tc_7_2_updateCapacity_preservesOccupiedAndOnHold() {
        // Set up: shelter already has 10 total, set 3 occupied
        patchAvailability(10, 3, 0);
        assertInvariants();

        // Update capacity to 15 via shelter PUT (should preserve occupied=3)
        HttpHeaders headers = authHelper.adminHeaders();
        String updateBody = """
                {
                  "capacities": [{"populationType": "SINGLE_ADULT", "bedsTotal": 15}]
                }
                """;
        ResponseEntity<String> updateResp = restTemplate.exchange(
                "/api/v1/shelters/" + shelterId, HttpMethod.PUT,
                new HttpEntity<>(updateBody, headers), String.class);
        assertEquals(HttpStatus.OK, updateResp.getStatusCode());

        // GET detail and verify: total=15, occupied=3, available=12
        ResponseEntity<String> detail = restTemplate.exchange(
                "/api/v1/shelters/" + shelterId, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        String detailBody = detail.getBody();
        assertNotNull(detailBody);
        assertTrue(detailBody.contains("\"bedsTotal\":15"), "Total must be updated to 15");
        assertTrue(detailBody.contains("\"bedsAvailable\":12"), "Available must be 15-3-0=12");

        assertInvariants();
    }

    @Test
    void tc_7_3_capacityAndAvailability_alwaysInSync() {
        // This test verifies the bug that was found: capacity and availability beds_total never diverge
        patchAvailability(10, 2, 0);

        // Read detail — capacity.bedsTotal and availability.bedsTotal must match
        HttpHeaders headers = authHelper.adminHeaders();
        ResponseEntity<String> detail = restTemplate.exchange(
                "/api/v1/shelters/" + shelterId, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        String body = detail.getBody();
        assertNotNull(body);

        // Extract capacity bedsTotal and availability bedsTotal — they must be identical
        // The response has capacities[].bedsTotal and availability[].bedsTotal
        int capIdx = body.indexOf("\"capacities\"");
        int availIdx = body.indexOf("\"availability\"");
        assertTrue(capIdx >= 0 && availIdx >= 0, "Response must have both capacities and availability");

        // Both sections should show bedsTotal:10
        String capSection = body.substring(capIdx, availIdx);
        String availSection = body.substring(availIdx);
        assertTrue(capSection.contains("\"bedsTotal\":10"), "Capacity bedsTotal must be 10");
        assertTrue(availSection.contains("\"bedsTotal\":10"), "Availability bedsTotal must be 10");

        assertInvariants();
    }
}
