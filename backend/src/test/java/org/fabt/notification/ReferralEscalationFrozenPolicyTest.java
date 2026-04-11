package org.fabt.notification;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.notification.domain.EscalationPolicy;
import org.fabt.notification.service.EscalationPolicyService;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.AfterEach;
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
 * T-23 — Load-bearing test for frozen-at-creation escalation policy semantics.
 *
 * <p><b>Casey Drummond + Riley Cho:</b> if this test ever fails, the audit
 * trail is compromised. A mid-day policy change must NOT retroactively alter
 * how an in-flight referral escalates. The chain-of-custody answer to a court
 * subpoena is "this referral was created under policy version N, and policy
 * version N said to fire CRITICAL at T+2h."</p>
 *
 * <p>Test plan:</p>
 * <ol>
 *   <li>Create referral A under the seeded platform default policy (PT2H → CRITICAL).</li>
 *   <li>Publish a custom tenant policy v2 with a DIFFERENT shape — moves the
 *       2h CRITICAL up to a 1.5h CRITICAL with a different recipient set.</li>
 *   <li>Create referral B under the new tenant policy.</li>
 *   <li>Backdate referral A to 2h05m ago and referral B to 1h35m ago.</li>
 *   <li>Run the escalation tasklet.</li>
 *   <li>Verify referral A fired escalation.2h (per its frozen v1 policy) and
 *       referral B fired escalation.1_5h (per its frozen v2 policy).</li>
 *   <li>Verify referral A's {@code escalation_policy_id} points at v1 and
 *       referral B's points at v2 — proves the test passed for the right
 *       reason, not because both policies coincidentally fire identically.</li>
 * </ol>
 */
class ReferralEscalationFrozenPolicyTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Tasklet escalationTasklet;

    @Autowired
    private EscalationPolicyService escalationPolicyService;

    private UUID dvShelterId;
    private HttpHeaders outreachHeadersA;
    private HttpHeaders outreachHeadersB;
    private User dvCoordinator;
    private User cocAdmin;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();

        var dvAdmin = authHelper.setupUserWithDvAccess(
                "frozen-admin@test.fabt.org", "Frozen Admin", new String[]{"PLATFORM_ADMIN"});
        HttpHeaders adminHeaders = authHelper.headersForUser(dvAdmin);

        // Two outreach workers so we can create two referrals for the same shelter
        // (the "one PENDING per user per shelter" guard rejects a second referral
        // from the same outreach worker).
        var dvOutreachA = authHelper.setupUserWithDvAccess(
                "frozen-outreach-a@test.fabt.org", "Frozen Outreach A", new String[]{"OUTREACH_WORKER"});
        outreachHeadersA = authHelper.headersForUser(dvOutreachA);
        var dvOutreachB = authHelper.setupUserWithDvAccess(
                "frozen-outreach-b@test.fabt.org", "Frozen Outreach B", new String[]{"OUTREACH_WORKER"});
        outreachHeadersB = authHelper.headersForUser(dvOutreachB);

        dvCoordinator = authHelper.setupUserWithDvAccess(
                "frozen-coord@test.fabt.org", "Frozen Coordinator", new String[]{"COORDINATOR"});

        cocAdmin = authHelper.setupUserWithDvAccess(
                "frozen-cocadmin@test.fabt.org", "Frozen CoC Admin", new String[]{"COC_ADMIN"});

        // Clean prior state — reuses the pattern from ReferralEscalationIntegrationTest.
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
            try (var ps = conn.prepareStatement(
                    "DELETE FROM escalation_policy WHERE tenant_id = ?")) {
                ps.setObject(1, authHelper.getTestTenantId());
                ps.executeUpdate();
            }
            conn.createStatement().execute("SET ROLE fabt_app");
        } catch (java.sql.SQLException e) {
            throw new RuntimeException("setUp cleanup failed", e);
        }

        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            dvShelterId = createDvShelter(adminHeaders);
        });
    }

    /**
     * After this test runs, the test tenant has a custom escalation policy
     * cached in {@link EscalationPolicyService#currentPolicyByTenant}, and the
     * row sits in {@code escalation_policy}. Sibling tests in the same JVM
     * (e.g. {@code ReferralEscalationIntegrationTest}) expect the seeded
     * platform default to apply to new referrals — leaving the tenant policy
     * around makes them snapshot the wrong policy and miss escalations.
     *
     * <p>Clean up both layers so test order doesn't matter.</p>
     */
    @AfterEach
    void tearDown() {
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
            throw new RuntimeException("tearDown cleanup failed", e);
        }
        escalationPolicyService.clearCaches_testOnly();
    }

    @Test
    @DisplayName("T-23: each referral fires per its FROZEN policy after a mid-day policy change")
    void frozenAtCreationSurvivesMidDayPolicyChange() throws Exception {
        // ----- 1. Referral A under platform default v1 (seeded by V40) -----
        String referralAId = createReferral(outreachHeadersA);
        UUID policyAId = readEscalationPolicyId(referralAId);
        assertThat(policyAId).as("Referral A must snapshot the seeded platform default policy").isNotNull();

        EscalationPolicy policyA = escalationPolicyService.findById(policyAId).orElseThrow();
        assertThat(policyA.thresholds())
                .as("Seeded v1 policy must contain a 2h CRITICAL threshold")
                .anyMatch(t -> "2h".equals(t.id())
                        && "CRITICAL".equals(t.severity())
                        && t.recipients().contains("COC_ADMIN"));

        // ----- 2. Publish tenant-specific policy v2 with a DIFFERENT shape -----
        UUID actor = authHelper.getUserRepository()
                .findByTenantIdAndEmail(authHelper.getTestTenantId(), "frozen-cocadmin@test.fabt.org")
                .orElseThrow().getId();
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            escalationPolicyService.update(authHelper.getTestTenantId(), "dv-referral",
                    List.of(
                            new EscalationPolicy.Threshold(
                                    "1_5h",
                                    Duration.ofMinutes(90),
                                    "CRITICAL",
                                    List.of("COORDINATOR")),
                            // Strictly increasing — keep a later threshold so validation passes.
                            new EscalationPolicy.Threshold(
                                    "4h",
                                    Duration.ofHours(4),
                                    "ACTION_REQUIRED",
                                    List.of("OUTREACH_WORKER"))),
                    actor);
        });

        // ----- 3. Referral B under tenant policy v2 -----
        String referralBId = createReferral(outreachHeadersB);
        UUID policyBId = readEscalationPolicyId(referralBId);
        assertThat(policyBId).as("Referral B must snapshot tenant policy v2").isNotNull();
        assertThat(policyBId).as("Policy A and Policy B MUST differ — otherwise the test passes for the wrong reason")
                .isNotEqualTo(policyAId);

        EscalationPolicy policyB = escalationPolicyService.findById(policyBId).orElseThrow();
        assertThat(policyB.version()).isEqualTo(1); // first row for this tenant
        assertThat(policyB.thresholds()).extracting(EscalationPolicy.Threshold::id)
                .containsExactly("1_5h", "4h");

        // ----- 4. Backdate so each referral has crossed exactly one threshold -----
        // Referral A → 125 minutes (past v1 PT1H and PT2H, NOT past PT3H30M)
        // Referral B → 95 minutes (past v2 PT1H30M, NOT past PT4H)
        backdateReferral(referralAId, 125);
        backdateReferral(referralBId, 95);

        // ----- 5. Run the escalation tasklet (matching the production path) -----
        TenantContext.runWithContext(null, true, () -> {
            try {
                escalationTasklet.execute(null, null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // ----- 6. Assert each referral fired per its frozen policy -----
        // Referral A: under v1 policy, 125 minutes triggers escalation.1h (COORDINATOR)
        // and escalation.2h (COC_ADMIN). Referral B is too young to trigger A's policy.
        assertThat(notificationCountFor(dvCoordinator.getId(), "escalation.1h", referralAId))
                .as("Referral A must fire escalation.1h under its frozen v1 policy")
                .isEqualTo(1);
        assertThat(notificationCountFor(cocAdmin.getId(), "escalation.2h", referralAId))
                .as("Referral A must fire escalation.2h under its frozen v1 policy")
                .isEqualTo(1);

        // Referral B: under v2 policy, 95 minutes triggers escalation.1_5h (COORDINATOR).
        assertThat(notificationCountFor(dvCoordinator.getId(), "escalation.1_5h", referralBId))
                .as("Referral B must fire escalation.1_5h under its frozen v2 policy")
                .isEqualTo(1);

        // Cross-checks: referral A must NOT fire the v2 1_5h type, referral B
        // must NOT fire the v1 2h type. This is the load-bearing assertion —
        // it proves the policies are NOT bleeding into each other.
        assertThat(notificationCountFor(dvCoordinator.getId(), "escalation.1_5h", referralAId))
                .as("Referral A must NOT pick up v2's escalation.1_5h — frozen-at-creation violated")
                .isEqualTo(0);
        assertThat(notificationCountFor(cocAdmin.getId(), "escalation.2h", referralBId))
                .as("Referral B must NOT pick up v1's escalation.2h — frozen-at-creation violated")
                .isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String createReferral(HttpHeaders headers) {
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
                new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return extractField(resp.getBody(), "id");
    }

    private UUID readEscalationPolicyId(String referralId) {
        UUID[] holder = new UUID[1];
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () ->
                holder[0] = jdbcTemplate.queryForObject(
                        "SELECT escalation_policy_id FROM referral_token WHERE id = ?::uuid",
                        UUID.class, referralId));
        return holder[0];
    }

    private void backdateReferral(String referralId, int minutesAgo) {
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            jdbcTemplate.update(
                    "UPDATE referral_token SET created_at = ? WHERE id = ?::uuid",
                    java.sql.Timestamp.from(Instant.now().minus(minutesAgo, ChronoUnit.MINUTES)),
                    referralId);
        });
    }

    private int notificationCountFor(UUID recipientId, String type, String referralId) {
        // RESET ROLE to bypass notification SELECT RLS for the assertion query.
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            conn.createStatement().execute("RESET ROLE");
            try (var ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM notification "
                    + " WHERE recipient_id = ? AND type = ? AND payload::text LIKE ?")) {
                ps.setObject(1, recipientId);
                ps.setString(2, type);
                ps.setString(3, "%" + referralId + "%");
                var rs = ps.executeQuery();
                rs.next();
                int count = rs.getInt(1);
                conn.createStatement().execute("SET ROLE fabt_app");
                return count;
            }
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private UUID createDvShelter(HttpHeaders headers) {
        String body = String.format("""
                {
                  "name": "Frozen Test DV Shelter %s",
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
