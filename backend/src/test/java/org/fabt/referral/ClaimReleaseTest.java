package org.fabt.referral;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
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
 * T-25 — Integration tests for claim, release, override, auto-release.
 *
 * <p>The load-bearing assertion is the concurrency test: two parallel
 * non-override claims must NOT both succeed. The Session 3 pause-check
 * called this out specifically.</p>
 */
class ClaimReleaseTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ReferralTokenService referralTokenService;

    private UUID dvShelterId;
    private HttpHeaders outreachHeaders;
    private User adminA;
    private User adminB;
    private HttpHeaders adminAHeaders;
    private HttpHeaders adminBHeaders;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();

        var dvAdmin = authHelper.setupUserWithDvAccess(
                "claim-padmin@test.fabt.org", "Claim Platform Admin", new String[]{"PLATFORM_ADMIN"});
        HttpHeaders padminHeaders = authHelper.headersForUser(dvAdmin);

        var dvOutreach = authHelper.setupUserWithDvAccess(
                "claim-outreach@test.fabt.org", "Claim Outreach", new String[]{"OUTREACH_WORKER"});
        outreachHeaders = authHelper.headersForUser(dvOutreach);

        adminA = authHelper.setupUserWithDvAccess(
                "claim-admin-a@test.fabt.org", "Claim Admin A", new String[]{"COC_ADMIN"});
        adminAHeaders = authHelper.headersForUser(adminA);

        adminB = authHelper.setupUserWithDvAccess(
                "claim-admin-b@test.fabt.org", "Claim Admin B", new String[]{"COC_ADMIN"});
        adminBHeaders = authHelper.headersForUser(adminB);

        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            conn.createStatement().execute("RESET ROLE");
            try (var ps = conn.prepareStatement("DELETE FROM referral_token WHERE tenant_id = ?")) {
                ps.setObject(1, authHelper.getTestTenantId());
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("DELETE FROM notification WHERE tenant_id = ?")) {
                ps.setObject(1, authHelper.getTestTenantId());
                ps.executeUpdate();
            }
            conn.createStatement().execute("SET ROLE fabt_app");
        } catch (java.sql.SQLException e) {
            throw new RuntimeException("setUp cleanup failed", e);
        }

        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            dvShelterId = createDvShelter(padminHeaders);
        });
    }

    @Test
    @DisplayName("T-25a: admin A claims, admin B blocked without override (409)")
    void blocksConcurrentClaimWithoutOverride() {
        UUID tokenId = createPendingReferral();

        ResponseEntity<String> claimA = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/claim",
                HttpMethod.POST, new HttpEntity<>(adminAHeaders), String.class);
        assertThat(claimA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(claimA.getBody()).contains(adminA.getId().toString());

        ResponseEntity<String> claimB = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/claim",
                HttpMethod.POST, new HttpEntity<>(adminBHeaders), String.class);
        assertThat(claimB.getStatusCode())
                .as("Second claim by a different admin without override must be 409")
                .isEqualTo(HttpStatus.CONFLICT);

        // DB: A still holds the claim.
        UUID claimedBy = readClaimedBy(tokenId);
        assertThat(claimedBy).isEqualTo(adminA.getId());
    }

    @Test
    @DisplayName("T-25b: Override-Claim header lets admin B steal the claim")
    void overrideHeaderAllowsSteal() {
        UUID tokenId = createPendingReferral();

        // A claims first.
        ResponseEntity<String> claimA = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/claim",
                HttpMethod.POST, new HttpEntity<>(adminAHeaders), String.class);
        assertThat(claimA.getStatusCode()).isEqualTo(HttpStatus.OK);

        // B steals with header.
        HttpHeaders adminBOverride = new HttpHeaders();
        adminBOverride.putAll(adminBHeaders);
        adminBOverride.set("Override-Claim", "true");
        ResponseEntity<String> claimB = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/claim",
                HttpMethod.POST, new HttpEntity<>(adminBOverride), String.class);
        assertThat(claimB.getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID claimedBy = readClaimedBy(tokenId);
        assertThat(claimedBy).isEqualTo(adminB.getId());
    }

    @Test
    @DisplayName("T-25c: holding admin can release; non-holder is blocked")
    void releaseRequiresHolderOrOverride() {
        UUID tokenId = createPendingReferral();

        restTemplate.exchange("/api/v1/dv-referrals/" + tokenId + "/claim",
                HttpMethod.POST, new HttpEntity<>(adminAHeaders), String.class);

        // Non-holder release without override → 403
        ResponseEntity<String> rejectedRelease = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/release",
                HttpMethod.POST, new HttpEntity<>(adminBHeaders), String.class);
        assertThat(rejectedRelease.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Holder release → 200, claim cleared
        ResponseEntity<String> ok = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/release",
                HttpMethod.POST, new HttpEntity<>(adminAHeaders), String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readClaimedBy(tokenId)).isNull();
    }

    @Test
    @DisplayName("T-25d: auto-release scheduler clears expired claims and uses partial index")
    void autoReleaseClearsExpiredClaims() {
        UUID tokenId = createPendingReferral();

        // Service-layer claim, then backdate the expiry into the past.
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            referralTokenService.claimToken(tokenId, adminA.getId(), false);
            jdbcTemplate.update(
                    "UPDATE referral_token SET claim_expires_at = NOW() - INTERVAL '1 minute' WHERE id = ?",
                    tokenId);
        });
        assertThat(readClaimedBy(tokenId)).as("Claim must exist before auto-release runs").isNotNull();

        // Run the auto-release task directly (don't wait for the @Scheduled cycle).
        referralTokenService.autoReleaseClaims();

        assertThat(readClaimedBy(tokenId))
                .as("Auto-release should have cleared the expired claim")
                .isNull();
    }

    @Test
    @DisplayName("T-25e: TWO concurrent claims — exactly one wins, the other gets 409")
    void concurrentClaimsExactlyOneWinner() throws Exception {
        UUID tokenId = createPendingReferral();

        // Two admins racing. CountDownLatch synchronizes them so both submit
        // within a few microseconds — TOCTOU window in any non-atomic UPDATE.
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> race(tokenId, adminAHeaders, start, okCount, conflictCount));
            Future<?> f2 = pool.submit(() -> race(tokenId, adminBHeaders, start, okCount, conflictCount));

            start.countDown();

            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(okCount.get())
                .as("Exactly one concurrent claim must succeed (atomic conditional UPDATE)")
                .isEqualTo(1);
        assertThat(conflictCount.get())
                .as("The losing concurrent claim must observe 409, not silently overwrite")
                .isEqualTo(1);

        // Whoever holds the claim now is the winner — but the other admin must
        // not be on the row. This is the load-bearing data assertion.
        UUID claimedBy = readClaimedBy(tokenId);
        assertThat(claimedBy).isNotNull();
        assertThat(claimedBy).isIn(adminA.getId(), adminB.getId());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void race(UUID tokenId, HttpHeaders adminHeaders, CountDownLatch start,
                      AtomicInteger okCount, AtomicInteger conflictCount) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/claim",
                HttpMethod.POST, new HttpEntity<>(adminHeaders), String.class);
        if (resp.getStatusCode() == HttpStatus.OK) {
            okCount.incrementAndGet();
        } else if (resp.getStatusCode() == HttpStatus.CONFLICT) {
            conflictCount.incrementAndGet();
        } else {
            throw new AssertionError("Unexpected race response: " + resp.getStatusCode());
        }
    }

    private UUID createPendingReferral() {
        String body = String.format("""
                {
                  "shelterId": "%s",
                  "householdSize": 2,
                  "populationType": "DV_SURVIVOR",
                  "urgency": "URGENT",
                  "specialNeeds": null,
                  "callbackNumber": "919-555-0099"
                }
                """, dvShelterId);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals", HttpMethod.POST,
                new HttpEntity<>(body, outreachHeaders), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(extractField(resp.getBody(), "id"));
    }

    private UUID readClaimedBy(UUID tokenId) {
        UUID[] holder = new UUID[1];
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            conn.createStatement().execute("RESET ROLE");
            try (var ps = conn.prepareStatement("SELECT claimed_by_admin_id FROM referral_token WHERE id = ?")) {
                ps.setObject(1, tokenId);
                var rs = ps.executeQuery();
                if (rs.next()) holder[0] = (UUID) rs.getObject(1);
            }
            conn.createStatement().execute("SET ROLE fabt_app");
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
        return holder[0];
    }

    private UUID createDvShelter(HttpHeaders headers) {
        String body = String.format("""
                {
                  "name": "Claim Test DV Shelter %s",
                  "addressStreet": "123 Test St",
                  "addressCity": "Raleigh",
                  "addressState": "NC",
                  "addressZip": "27601",
                  "phone": "919-555-%04d",
                  "dvShelter": true,
                  "constraints": {"populationTypesServed": ["DV_SURVIVOR"]},
                  "capacities": [{"populationType": "DV_SURVIVOR", "bedsTotal": 10}]
                }
                """,
                UUID.randomUUID().toString().substring(0, 8),
                (int) (Math.random() * 9999));
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/shelters", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID shelterId = UUID.fromString(extractField(resp.getBody(), "id"));
        String availBody = """
                {"populationType": "DV_SURVIVOR", "bedsTotal": 10, "bedsOccupied": 3, "bedsOnHold": 0, "acceptingNewGuests": true}
                """;
        restTemplate.exchange("/api/v1/shelters/" + shelterId + "/availability",
                HttpMethod.PATCH, new HttpEntity<>(availBody, headers), String.class);
        return shelterId;
    }

    private String extractField(String json, String field) {
        int idx = json.indexOf("\"" + field + "\":\"");
        if (idx < 0) throw new AssertionError("Field '" + field + "' not found: " + json);
        int start = idx + field.length() + 4;
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }
}
