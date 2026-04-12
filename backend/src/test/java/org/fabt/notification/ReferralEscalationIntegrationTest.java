package org.fabt.notification;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.notification.service.EscalationPolicyService;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for DV referral escalation batch job.
 * Tests threshold-based escalation, dedup, and stop-on-action.
 */
class ReferralEscalationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Tasklet escalationTasklet;

    @Autowired
    private EscalationPolicyService escalationPolicyService;

    private UUID dvShelterId;
    private HttpHeaders outreachHeaders;
    private HttpHeaders dvCoordHeaders;
    private User dvCoordinator;
    private User cocAdmin;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();

        // DV admin for shelter creation
        var dvAdmin = authHelper.setupUserWithDvAccess(
                "esc-admin@test.fabt.org", "Escalation Admin", new String[]{"PLATFORM_ADMIN"});
        HttpHeaders adminHeaders = authHelper.headersForUser(dvAdmin);

        // DV outreach worker (creates referrals)
        var dvOutreach = authHelper.setupUserWithDvAccess(
                "esc-outreach@test.fabt.org", "Escalation Outreach", new String[]{"OUTREACH_WORKER"});
        outreachHeaders = authHelper.headersForUser(dvOutreach);

        // DV coordinator (receives escalation notifications)
        dvCoordinator = authHelper.setupUserWithDvAccess(
                "esc-coord@test.fabt.org", "Escalation Coordinator", new String[]{"COORDINATOR"});
        dvCoordHeaders = authHelper.headersForUser(dvCoordinator);

        // CoC admin (receives 2h escalation)
        cocAdmin = authHelper.setupUserWithDvAccess(
                "esc-cocadmin@test.fabt.org", "Escalation CoC Admin", new String[]{"COC_ADMIN"});

        // Clean up referral tokens and notifications from prior tests FIRST.
        // Must use RESET ROLE to bypass notification SELECT RLS policy (Lesson #80).
        // referral_token DELETE also needs dvAccess via shelter RLS cascade.
        // Use raw DataSource connection to ensure RESET ROLE + DELETEs on same connection.
        //
        // Also wipe any tenant-specific escalation policy left behind by sibling
        // tests (e.g. ReferralEscalationFrozenPolicyTest publishes a custom v2)
        // and clear the in-memory policy cache so new referrals snapshot the
        // seeded platform default. Without this, the test snapshots whatever
        // sibling test polluted the cache and the threshold timing assertions
        // become unstable.
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
            try (var ps = conn.prepareStatement("DELETE FROM escalation_policy WHERE tenant_id = ?")) {
                ps.setObject(1, authHelper.getTestTenantId());
                ps.executeUpdate();
            }
            conn.createStatement().execute("SET ROLE fabt_app");
        } catch (java.sql.SQLException e) {
            throw new RuntimeException("setUp cleanup failed", e);
        }
        escalationPolicyService.clearCaches_testOnly();

        // Create DV shelter (needs dvAccess context for shelter RLS)
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            dvShelterId = createDvShelter(adminHeaders);
        });
    }

    @Test
    @DisplayName("T-28: Referral past 1h → escalation.1h notification for coordinator")
    void escalation1hCreatesNotificationForCoordinator() throws Exception {
        // Create referral and backdate to 65 minutes ago
        String tokenId = createReferralAndBackdate(65);

        // Run escalation job (TenantContext with dvAccess=true, matching production path)
        TenantContext.runWithContext(null, true, () -> {
            try {
                escalationTasklet.execute(null, null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Coordinator should have escalation.1h notification
        ResponseEntity<String> notifResp = restTemplate.exchange(
                "/api/v1/notifications?unread=true", HttpMethod.GET,
                new HttpEntity<>(dvCoordHeaders), String.class);
        assertThat(notifResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(notifResp.getBody()).contains("escalation.1h");
        assertThat(notifResp.getBody()).contains("ACTION_REQUIRED");
        // Verify payload contains only referralId and threshold — zero PII
        // PostgreSQL reformats JSONB with spaces: "threshold": "1h" (not "threshold":"1h")
        assertThat(notifResp.getBody()).contains("threshold");
        assertThat(notifResp.getBody()).contains("1h");
        assertThat(notifResp.getBody()).doesNotContain("callbackNumber");
        assertThat(notifResp.getBody()).doesNotContain("householdSize");
    }

    @Test
    @DisplayName("T-29: Referral accepted before 2h → no 2h escalation")
    void acceptedReferralStopsEscalation() throws Exception {
        // Create referral and backdate to 65 minutes ago (past 1h, not past 2h)
        String tokenId = createReferralAndBackdate(65);

        // Accept the referral as coordinator
        ResponseEntity<String> acceptResp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/accept",
                HttpMethod.PATCH, new HttpEntity<>(dvCoordHeaders), String.class);
        assertThat(acceptResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Backdate further to 125 minutes (past 2h threshold)
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            jdbcTemplate.update(
                    "UPDATE referral_token SET created_at = ? WHERE id = ?::uuid",
                    java.sql.Timestamp.from(Instant.now().minus(125, ChronoUnit.MINUTES)),
                    tokenId);
        });

        // Run escalation
        TenantContext.runWithContext(null, true, () -> {
            try {
                escalationTasklet.execute(null, null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // CoC admin should NOT have escalation.2h — referral was already accepted
        HttpHeaders cocAdminHeaders = authHelper.headersForUser(cocAdmin);
        ResponseEntity<String> notifResp = restTemplate.exchange(
                "/api/v1/notifications?unread=true", HttpMethod.GET,
                new HttpEntity<>(cocAdminHeaders), String.class);
        assertThat(notifResp.getBody()).doesNotContain("escalation.2h");
    }

    @Test
    @DisplayName("T-30: Escalation is idempotent — running job twice creates same notifications")
    void escalationIsIdempotent() throws Exception {
        // Create referral backdated to 65 minutes
        createReferralAndBackdate(65);

        // Run escalation twice — separate scopes ensure first commits before second runs
        TenantContext.runWithContext(null, true, () -> {
            try { escalationTasklet.execute(null, null); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        TenantContext.runWithContext(null, true, () -> {
            try { escalationTasklet.execute(null, null); }
            catch (Exception e) { throw new RuntimeException(e); }
        });

        // Count via RESET ROLE to bypass SELECT RLS — exactly 1 (dedup worked)
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            conn.createStatement().execute("RESET ROLE");
            try (var ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM notification WHERE recipient_id = ? AND type = 'escalation.1h'")) {
                ps.setObject(1, dvCoordinator.getId());
                var rs = ps.executeQuery();
                rs.next();
                int count = rs.getInt(1);
                assertThat(count).as("Escalation should fire exactly once per referral (dedup)").isEqualTo(1);
            }
            conn.createStatement().execute("SET ROLE fabt_app");
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String createReferralAndBackdate(int minutesAgo) {
        // Create referral via REST
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

        String tokenId = extractField(resp.getBody(), "id");

        // Backdate created_at to simulate aging
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            jdbcTemplate.update(
                    "UPDATE referral_token SET created_at = ? WHERE id = ?::uuid",
                    java.sql.Timestamp.from(Instant.now().minus(minutesAgo, ChronoUnit.MINUTES)),
                    tokenId);
        });

        return tokenId;
    }

    private UUID createDvShelter(HttpHeaders headers) {
        String body = String.format("""
                {
                  "name": "Escalation Test DV Shelter %s",
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

        // Patch availability so referrals can be created
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
