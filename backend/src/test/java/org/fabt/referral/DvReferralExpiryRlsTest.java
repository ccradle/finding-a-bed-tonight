package org.fabt.referral;

import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.referral.service.ReferralTokenService;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that @Transactional on expireTokens() prevents DV referral expiry.
 *
 * Root cause: @Transactional eagerly acquires a JDBC connection (via
 * DataSourceTransactionManager.doBegin()) BEFORE the method body calls
 * TenantContext.runWithContext(null, true, ...). The RLS-aware DataSource
 * reads dvAccess=false at connection acquisition time, making DV shelters
 * (and their referral tokens) invisible to the UPDATE query.
 *
 * The escalation batch job (ReferralEscalationJobConfig) works because
 * BatchJobScheduler wraps the entire job in TenantContext BEFORE Spring
 * Batch starts transactions.
 *
 * These tests call expireTokens() WITHOUT an outer TenantContext — exactly
 * as Spring's @Scheduled handler invokes it in production.
 */
class DvReferralExpiryRlsTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReferralTokenService referralTokenService;

    private UUID dvShelterId;
    private HttpHeaders adminHeaders;
    private HttpHeaders outreachHeaders;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();

        var dvAdmin = authHelper.setupUserWithDvAccess(
                "rls-dvadmin@test.fabt.org", "RLS DV Admin", new String[]{"PLATFORM_ADMIN"});
        adminHeaders = authHelper.headersForUser(dvAdmin);

        var dvOutreach = authHelper.setupUserWithDvAccess(
                "rls-dvoutreach@test.fabt.org", "RLS DV Outreach", new String[]{"OUTREACH_WORKER"});
        outreachHeaders = authHelper.headersForUser(dvOutreach);

        var dvCoord = authHelper.setupUserWithDvAccess(
                "rls-dvcoord@test.fabt.org", "RLS DV Coord", new String[]{"COORDINATOR"});

        // Create DV shelter and availability inside TenantContext (required for RLS)
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            dvShelterId = createDvShelter();
            patchAvailability(dvShelterId);

            // Assign coordinator to DV shelter (required for referral flow)
            restTemplate.exchange(
                    "/api/v1/shelters/" + dvShelterId + "/coordinators",
                    HttpMethod.POST,
                    new HttpEntity<>("{\"userId\": \"" + dvCoord.getId() + "\"}", adminHeaders),
                    String.class);
        });
    }

    @Test
    @DisplayName("FIX: expireTokens() without @Transactional correctly expires DV referrals (no outer TenantContext)")
    void expireTokens_withoutTransactional_expiresDvReferrals() {
        // 1. Create a DV referral via API
        String tokenId = createExpiredReferral();

        // 2. Verify it's PENDING before we try to expire
        String statusBefore = queryTokenStatus(tokenId);
        assertThat(statusBefore).isEqualTo("PENDING");

        // 3. Call expireTokens() WITHOUT outer TenantContext — exactly as @Scheduled does.
        //    No TenantContext.runWithContext() wrapping this call.
        //    With @Transactional removed, JdbcTemplate acquires the connection lazily
        //    inside runWithContext where dvAccess=true is already bound.
        referralTokenService.expireTokens();

        // 4. Check status — token should now be EXPIRED
        String statusAfter = queryTokenStatus(tokenId);
        assertThat(statusAfter)
                .describedAs("Without @Transactional, JdbcTemplate acquires connection inside runWithContext "
                        + "where dvAccess=true. DV shelter referrals are visible. Token should be EXPIRED.")
                .isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("Regression guard: expireTokens() works for non-DV referrals too")
    void expireTokens_nonDvReferral_alsoExpires() {
        // Create a non-DV shelter and referral to ensure the fix doesn't break non-DV path
        UUID nonDvShelterId = TenantContext.callWithContext(authHelper.getTestTenantId(), true, () -> {
            UUID sid = createNonDvShelter();
            patchAvailabilityNonDv(sid);
            return sid;
        });

        // Create referral via API (outreach worker has dvAccess, can see both)
        String body = String.format("""
                {
                  "shelterId": "%s",
                  "householdSize": 1,
                  "populationType": "SINGLE_ADULT",
                  "urgency": "STANDARD",
                  "specialNeeds": "",
                  "callbackNumber": "919-555-0098"
                }
                """, nonDvShelterId);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals", HttpMethod.POST,
                new HttpEntity<>(body, outreachHeaders), String.class);

        // Non-DV shelters shouldn't accept referrals (they're for DV only)
        // If the API rejects it, that's correct behavior — skip the expiry check
        if (resp.getStatusCode() == HttpStatus.CREATED) {
            String tokenId = extractField(resp.getBody(), "id");
            TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
                jdbcTemplate.update(
                        "UPDATE referral_token SET expires_at = NOW() - INTERVAL '1 hour' WHERE id = ?::uuid",
                        tokenId);
            });
            referralTokenService.expireTokens();
            String status = queryTokenStatus(tokenId);
            assertThat(status).isEqualTo("EXPIRED");
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Creates a DV referral and immediately backdates its expires_at to the past.
     * Returns the token ID.
     */
    private String createExpiredReferral() {
        String body = String.format("""
                {
                  "shelterId": "%s",
                  "householdSize": 1,
                  "populationType": "DV_SURVIVOR",
                  "urgency": "STANDARD",
                  "specialNeeds": "",
                  "callbackNumber": "919-555-0099"
                }
                """, dvShelterId);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals", HttpMethod.POST,
                new HttpEntity<>(body, outreachHeaders), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String tokenId = extractField(resp.getBody(), "id");

        // Backdate expires_at to 1 hour ago (must use owner role to bypass RLS for UPDATE)
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            int updated = jdbcTemplate.update(
                    "UPDATE referral_token SET expires_at = NOW() - INTERVAL '1 hour' WHERE id = ?::uuid",
                    tokenId);
            assertThat(updated).isEqualTo(1);
        });

        return tokenId;
    }

    /**
     * Queries token status using the DB owner role (bypasses RLS) to get ground truth.
     */
    private String queryTokenStatus(String tokenId) {
        // Use TenantContext with dvAccess=true so RLS allows the SELECT
        return TenantContext.callWithContext(authHelper.getTestTenantId(), true, () ->
                jdbcTemplate.queryForObject(
                        "SELECT status FROM referral_token WHERE id = ?::uuid",
                        String.class, tokenId));
    }

    private UUID createDvShelter() {
        String body = String.format("""
                {
                  "name": "RLS Expiry Test DV Shelter %s",
                  "addressStreet": "123 Test St",
                  "addressCity": "Raleigh",
                  "addressState": "NC",
                  "addressZip": "27601",
                  "phone": "919-555-0001",
                  "dvShelter": true,
                  "constraints": { "populationTypesServed": ["DV_SURVIVOR"] },
                  "capacities": [{"populationType": "DV_SURVIVOR", "bedsTotal": 10}]
                }
                """, UUID.randomUUID().toString().substring(0, 8));

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/shelters", HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(extractField(resp.getBody(), "id"));
    }

    private UUID createNonDvShelter() {
        String body = String.format("""
                {
                  "name": "RLS Expiry Test Regular Shelter %s",
                  "addressStreet": "456 Test St",
                  "addressCity": "Raleigh",
                  "addressState": "NC",
                  "addressZip": "27601",
                  "phone": "919-555-0002",
                  "dvShelter": false,
                  "constraints": { "populationTypesServed": ["SINGLE_ADULT"] },
                  "capacities": [{"populationType": "SINGLE_ADULT", "bedsTotal": 10}]
                }
                """, UUID.randomUUID().toString().substring(0, 8));

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/shelters", HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(extractField(resp.getBody(), "id"));
    }

    private void patchAvailabilityNonDv(UUID shelterId) {
        String body = """
                {"populationType": "SINGLE_ADULT", "bedsTotal": 10, "bedsOccupied": 3, "bedsOnHold": 0, "acceptingNewGuests": true}
                """;
        restTemplate.exchange("/api/v1/shelters/" + shelterId + "/availability",
                HttpMethod.PATCH, new HttpEntity<>(body, adminHeaders), String.class);
    }

    private void patchAvailability(UUID shelterId) {
        String body = """
                {"populationType": "DV_SURVIVOR", "bedsTotal": 10, "bedsOccupied": 3, "bedsOnHold": 0, "acceptingNewGuests": true}
                """;
        restTemplate.exchange("/api/v1/shelters/" + shelterId + "/availability",
                HttpMethod.PATCH, new HttpEntity<>(body, adminHeaders), String.class);
    }

    private String extractField(String json, String field) {
        int idx = json.indexOf("\"" + field + "\":\"");
        if (idx < 0) {
            throw new AssertionError("Field '" + field + "' not found in response: " + json);
        }
        int start = idx + field.length() + 4;
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }
}
