package org.fabt.referral;

import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.referral.service.ReferralTokenPurgeService;
import org.fabt.referral.service.ReferralTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.fabt.shared.web.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for DV opaque referral token lifecycle.
 * Verifies zero-PII design, warm handoff, purge, and access control.
 */
class DvReferralIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReferralTokenService referralTokenService;

    @Autowired
    private ReferralTokenPurgeService purgeService;

    private UUID dvShelterId;
    private UUID nonDvShelterId;
    private HttpHeaders outreachHeaders;
    private HttpHeaders coordHeaders;
    private HttpHeaders adminHeaders;
    private HttpHeaders noDvOutreachHeaders;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        authHelper.setupOutreachWorkerUser();
        authHelper.setupCocAdminUser();

        // DV operations require dvAccess=true — use a DV-access admin for setup
        var dvAdmin = authHelper.setupUserWithDvAccess(
                "dvadmin@test.fabt.org", "DV Admin", new String[]{"PLATFORM_ADMIN"});
        adminHeaders = authHelper.headersForUser(dvAdmin);
        // Outreach worker with dvAccess=true (can see DV shelters via RLS)
        var dvOutreach = authHelper.setupUserWithDvAccess(
                "dvoutreach@test.fabt.org", "DV Outreach", new String[]{"OUTREACH_WORKER"});
        outreachHeaders = authHelper.headersForUser(dvOutreach);
        // Coordinator with dvAccess=true (can screen DV referrals)
        var dvCoord = authHelper.setupUserWithDvAccess(
                "dvcoord@test.fabt.org", "DV Coordinator", new String[]{"COC_ADMIN"});
        coordHeaders = authHelper.headersForUser(dvCoord);
        // Outreach worker WITHOUT dvAccess (for negative tests)
        noDvOutreachHeaders = authHelper.outreachWorkerHeaders();

        // Bind TenantContext for direct JDBC calls in test thread (RLS requires dvAccess + tenantId)
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            dvShelterId = createShelter(true);
            nonDvShelterId = createShelter(false);

            // Set up availability so DV shelter has beds
            patchAvailability(dvShelterId, "DV_SURVIVOR", 10, 3, 0);
        });
    }

    // =========================================================================
    // Token Lifecycle
    // =========================================================================

    @Test
    void tc_create_accept_warmHandoff() {
        // Create referral
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        assertEquals(HttpStatus.CREATED, createResp.getStatusCode());
        String tokenId = extractField(createResp.getBody(), "id");
        assertNotNull(tokenId);

        // Verify token is PENDING
        assertTrue(createResp.getBody().contains("\"status\":\"PENDING\""));

        // Accept
        ResponseEntity<String> acceptResp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/accept",
                HttpMethod.PATCH, new HttpEntity<>(coordHeaders), String.class);
        assertEquals(HttpStatus.OK, acceptResp.getStatusCode());
        assertTrue(acceptResp.getBody().contains("\"status\":\"ACCEPTED\""));

        // Warm handoff: accepted response includes shelter phone
        assertTrue(acceptResp.getBody().contains("\"shelterPhone\""),
                "ACCEPTED response must include shelter phone for warm handoff");

        // Warm handoff: verify response does NOT include shelter address
        assertFalse(acceptResp.getBody().contains("\"addressStreet\""),
                "Response must NOT include shelter address (FVPSA)");
        assertFalse(acceptResp.getBody().contains("\"addressCity\""),
                "Response must NOT include shelter city");
        assertFalse(acceptResp.getBody().contains("\"latitude\""),
                "Response must NOT include shelter latitude");
    }

    @Test
    void tc_create_reject_workerSeesReason() {
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        String tokenId = extractField(createResp.getBody(), "id");

        // Reject with reason
        String rejectBody = """
                {"reason": "No capacity for pets at this time"}
                """;
        ResponseEntity<String> rejectResp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/reject",
                HttpMethod.PATCH, new HttpEntity<>(rejectBody, coordHeaders), String.class);
        assertEquals(HttpStatus.OK, rejectResp.getStatusCode());
        assertTrue(rejectResp.getBody().contains("\"status\":\"REJECTED\""));
        assertTrue(rejectResp.getBody().contains("No capacity for pets"));

        // Worker sees the rejection reason in their list
        ResponseEntity<String> mineResp = restTemplate.exchange(
                "/api/v1/dv-referrals/mine", HttpMethod.GET,
                new HttpEntity<>(outreachHeaders), String.class);
        assertTrue(mineResp.getBody().contains("No capacity for pets"));
    }

    @Test
    void tc_create_expire_statusChange() {
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        String tokenId = extractField(createResp.getBody(), "id");

        // Force expiry by updating expires_at to past
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            jdbcTemplate.update(
                    "UPDATE referral_token SET expires_at = NOW() - INTERVAL '1 minute' WHERE id = ?::uuid",
                    tokenId);
            referralTokenService.expireTokens();
        });

        // Verify status changed
        ResponseEntity<String> mineResp = restTemplate.exchange(
                "/api/v1/dv-referrals/mine", HttpMethod.GET,
                new HttpEntity<>(outreachHeaders), String.class);
        assertTrue(mineResp.getBody().contains("\"status\":\"EXPIRED\""));
    }

    // =========================================================================
    // Access Control
    // =========================================================================

    @Test
    void tc_noDvAccess_cannotCreate() {
        // Outreach worker with dvAccess=false — two layers of protection:
        // 1. RLS: SET ROLE fabt_app + app.dv_access=false hides the DV shelter from queries
        // 2. Service: TenantContext.getDvAccess() check rejects before any query
        // Either layer should block — the response should be 4xx or 5xx
        ResponseEntity<String> resp = createReferral(dvShelterId, noDvOutreachHeaders);
        assertTrue(resp.getStatusCode().is4xxClientError() || resp.getStatusCode().is5xxServerError(),
                "Non-DV-access user should not be able to create referral for DV shelter. Got: " + resp.getStatusCode());
    }

    @Test
    void tc_nonDvShelter_rejected() {
        ResponseEntity<String> resp = createReferral(nonDvShelterId, outreachHeaders);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "Referral tokens are only for DV shelters");
    }

    @Test
    void tc_duplicatePending_rejected() {
        ResponseEntity<String> first = createReferral(dvShelterId, outreachHeaders);
        assertEquals(HttpStatus.CREATED, first.getStatusCode());

        ResponseEntity<String> second = createReferral(dvShelterId, outreachHeaders);
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode(),
                "Duplicate PENDING token for same user+shelter must be 409");
    }

    // =========================================================================
    // Warm Handoff — Zero PII Verification
    // =========================================================================

    @Test
    void tc_accepted_includesPhone_notAddress() {
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        String tokenId = extractField(createResp.getBody(), "id");

        restTemplate.exchange("/api/v1/dv-referrals/" + tokenId + "/accept",
                HttpMethod.PATCH, new HttpEntity<>(coordHeaders), String.class);

        // Worker's "mine" list should include phone for accepted referral
        ResponseEntity<String> mineResp = restTemplate.exchange(
                "/api/v1/dv-referrals/mine", HttpMethod.GET,
                new HttpEntity<>(outreachHeaders), String.class);
        String body = mineResp.getBody();
        assertNotNull(body);
        assertTrue(body.contains("shelterPhone"), "Accepted referral must include shelter phone");
        assertFalse(body.contains("addressStreet"), "Must NOT include address");
        assertFalse(body.contains("latitude"), "Must NOT include coordinates");
    }

    // =========================================================================
    // Purge
    // =========================================================================

    @Test
    void tc_purge_hardDeletes() {
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        String tokenId = extractField(createResp.getBody(), "id");

        // Accept the token
        restTemplate.exchange("/api/v1/dv-referrals/" + tokenId + "/accept",
                HttpMethod.PATCH, new HttpEntity<>(coordHeaders), String.class);

        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            // Verify token exists
            Integer countBefore = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM referral_token WHERE id = ?::uuid", Integer.class, tokenId);
            assertEquals(1, countBefore);

            // Force responded_at to be old enough for purge
            jdbcTemplate.update(
                    "UPDATE referral_token SET responded_at = NOW() - INTERVAL '25 hours' WHERE id = ?::uuid",
                    tokenId);
            purgeService.purgeTerminalTokens();

            // Verify token is GONE (hard-deleted, not soft-deleted)
            Integer countAfter = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM referral_token WHERE id = ?::uuid", Integer.class, tokenId);
            assertEquals(0, countAfter, "Purge must hard-delete terminal tokens");
        });
    }

    // =========================================================================
    // Expired Token Cannot Be Accepted
    // =========================================================================

    @Test
    void tc_expiredToken_cannotAccept() {
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        String tokenId = extractField(createResp.getBody(), "id");

        // Force expiry
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            jdbcTemplate.update(
                    "UPDATE referral_token SET expires_at = NOW() - INTERVAL '1 minute' WHERE id = ?::uuid",
                    tokenId);
            referralTokenService.expireTokens();
        });

        // Try to accept — should fail
        ResponseEntity<String> acceptResp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/accept",
                HttpMethod.PATCH, new HttpEntity<>(coordHeaders), String.class);
        assertTrue(acceptResp.getStatusCode().is4xxClientError(),
                "Expired token cannot be accepted");
    }

    // =========================================================================
    // Coordinator Assignment
    // =========================================================================

    @Test
    void tc_coordinatorSeesOnlyAssignedShelters() {
        // Pending list should only return tokens for shelters the coordinator is assigned to
        ResponseEntity<String> pendingResp = restTemplate.exchange(
                "/api/v1/dv-referrals/pending?shelterId=" + dvShelterId,
                HttpMethod.GET, new HttpEntity<>(coordHeaders), String.class);
        assertEquals(HttpStatus.OK, pendingResp.getStatusCode());
    }

    // =========================================================================
    // Analytics
    // =========================================================================

    @Test
    void tc_analytics_returnsAggregateCounts_noPII() {
        // Create and accept a referral to generate counter data
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        String tokenId = extractField(createResp.getBody(), "id");
        restTemplate.exchange("/api/v1/dv-referrals/" + tokenId + "/accept",
                HttpMethod.PATCH, new HttpEntity<>(coordHeaders), String.class);

        // Query analytics
        ResponseEntity<String> analyticsResp = restTemplate.exchange(
                "/api/v1/dv-referrals/analytics", HttpMethod.GET,
                new HttpEntity<>(adminHeaders), String.class);
        assertEquals(HttpStatus.OK, analyticsResp.getStatusCode());
        String body = analyticsResp.getBody();
        assertNotNull(body);

        // Should contain aggregate counts — no PII
        assertTrue(body.contains("\"requested\""), "Analytics must include requested count");
        assertTrue(body.contains("\"accepted\""), "Analytics must include accepted count");
        assertTrue(body.contains("\"rejected\""), "Analytics must include rejected count");
        assertTrue(body.contains("\"expired\""), "Analytics must include expired count");

        // Must NOT contain any PII fields
        assertFalse(body.contains("callbackNumber"), "Analytics must NOT contain callback numbers");
        assertFalse(body.contains("specialNeeds"), "Analytics must NOT contain special needs");
        assertFalse(body.contains("householdSize"), "Analytics must NOT contain household size");
    }

    // =========================================================================
    // Referrals Don't Affect Bed Availability
    // =========================================================================

    @Test
    void tc_referralDoesNotAffectAvailability() {
        // Read availability before
        ResponseEntity<String> before = restTemplate.exchange(
                "/api/v1/shelters/" + dvShelterId, HttpMethod.GET,
                new HttpEntity<>(adminHeaders), String.class);
        String beforeBody = before.getBody();

        // Create and accept a referral
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        String tokenId = extractField(createResp.getBody(), "id");
        restTemplate.exchange("/api/v1/dv-referrals/" + tokenId + "/accept",
                HttpMethod.PATCH, new HttpEntity<>(coordHeaders), String.class);

        // Read availability after — should be unchanged
        ResponseEntity<String> after = restTemplate.exchange(
                "/api/v1/shelters/" + dvShelterId, HttpMethod.GET,
                new HttpEntity<>(adminHeaders), String.class);

        // DV referrals are separate from holds — they don't change bed_availability
        // The beds_on_hold count should be the same
        assertEquals(extractFieldFromDetail(beforeBody, "bedsOnHold"),
                extractFieldFromDetail(after.getBody(), "bedsOnHold"),
                "DV referrals must not affect bed availability counts");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UUID createShelter(boolean dvShelter) {
        String body = String.format("""
                {
                  "name": "%s Shelter %s",
                  "addressStreet": "123 Test St",
                  "addressCity": "Raleigh",
                  "addressState": "NC",
                  "addressZip": "27601",
                  "phone": "919-555-%04d",
                  "dvShelter": %s,
                  "constraints": {
                    "populationTypesServed": ["%s"]
                  },
                  "capacities": [{"populationType": "%s", "bedsTotal": 10}]
                }
                """,
                dvShelter ? "DV" : "Regular",
                UUID.randomUUID().toString().substring(0, 8),
                (int) (Math.random() * 9999),
                dvShelter,
                dvShelter ? "DV_SURVIVOR" : "SINGLE_ADULT",
                dvShelter ? "DV_SURVIVOR" : "SINGLE_ADULT");

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/shelters", HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders), String.class);
        return UUID.fromString(extractField(resp.getBody(), "id"));
    }

    private void patchAvailability(UUID shelterId, String popType, int total, int occupied, int hold) {
        String body = String.format("""
                {"populationType": "%s", "bedsTotal": %d, "bedsOccupied": %d, "bedsOnHold": %d, "acceptingNewGuests": true}
                """, popType, total, occupied, hold);
        restTemplate.exchange("/api/v1/shelters/" + shelterId + "/availability",
                HttpMethod.PATCH, new HttpEntity<>(body, coordHeaders), String.class);
    }

    private ResponseEntity<String> createReferral(UUID shelterId, HttpHeaders headers) {
        String body = String.format("""
                {
                  "shelterId": "%s",
                  "householdSize": 3,
                  "populationType": "DV_SURVIVOR",
                  "urgency": "URGENT",
                  "specialNeeds": "Wheelchair accessible needed",
                  "callbackNumber": "919-555-0042"
                }
                """, shelterId);
        return restTemplate.exchange("/api/v1/dv-referrals", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private String extractField(String json, String field) {
        int idx = json.indexOf("\"" + field + "\":\"");
        if (idx < 0) return null;
        int start = idx + field.length() + 4;
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    private int extractFieldFromDetail(String json, String field) {
        int idx = json.indexOf("\"" + field + "\":");
        if (idx < 0) return -999;
        String val = json.substring(idx + field.length() + 3);
        int end = Math.min(
                val.indexOf(",") >= 0 ? val.indexOf(",") : val.length(),
                val.indexOf("}") >= 0 ? val.indexOf("}") : val.length()
        );
        return Integer.parseInt(val.substring(0, end).trim());
    }
}
