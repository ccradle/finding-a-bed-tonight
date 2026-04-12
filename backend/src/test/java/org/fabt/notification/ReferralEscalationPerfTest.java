package org.fabt.notification;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.notification.service.EscalationPolicyService;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * T-52 perf regression guard (Alex Chen review 2026-04-12).
 *
 * <p>Seeds {@value #FIXTURE_COUNT} pending DV referrals all backdated past
 * the 1h threshold, runs the escalation tasklet once, and asserts the
 * wall clock stays under a generous 60-second budget. This is a
 * <b>regression guard</b>, not a perf SLO — the budget is deliberately
 * loose to absorb CI noise while still catching a 5x+ regression.</p>
 *
 * <p><b>Why this test exists:</b> the original T-52 task description assumed
 * an existing Micrometer timer around the batch job that would let us verify
 * "p95 unchanged" after the policy lookup refactor. There is no such timer —
 * Session 7 discovery. Pre-refactor p95 is unrecoverable. This test is the
 * forward-looking replacement: it catches future regressions even without a
 * historical baseline to compare against.</p>
 *
 * <p><b>What's measured:</b> end-to-end tasklet wall clock including
 * {@code findAllPending}, per-referral policy lookup (hitting the per-run
 * local caches), {@code isNew} dedup check, recipient resolution, and the
 * 1000 downstream {@code NotificationPersistenceService.sendToAll} writes.
 * Spring Batch step timer (auto-emitted) is the secondary observability
 * signal, alongside {@code fabt.escalation.batch.duration} added in T-52.</p>
 *
 * <p><b>Why 60 seconds and not 5:</b> Testcontainer PostgreSQL + JIT cold
 * start + RLS overhead + SSE event publish + notification INSERT per row
 * means {@value #FIXTURE_COUNT} referrals legitimately takes seconds on CI.
 * A 5-second budget would produce flakes, 60 seconds would not catch a 2x
 * regression, and neither bound catches what we want. 60 seconds was picked
 * as "any value at or above this is a genuine signal; below this is noise."</p>
 *
 * <p><b>Not run as part of {@code mvn test} on PRs because of the fixture
 * scale:</b> if CI runtime becomes a concern, gate with an {@code @Tag("perf")}
 * profile later. For now the ~seconds-scale cost is acceptable given that
 * Sam Okafor's Gatling simulations are main-only already. (Alex Chen
 * 2026-04-12: "yes run it on every PR; seconds are cheap and the signal
 * matters.")</p>
 */
class ReferralEscalationPerfTest extends BaseIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ReferralEscalationPerfTest.class);

    /**
     * Number of pending referrals to seed. Balance between signal and CI time.
     *
     * <p>Reduced from aspirational 1000 → practical 200 because the
     * {@code uq_referral_token_pending} unique constraint on
     * {@code (referring_user_id, shelter_id)} blocks multiple PENDING
     * referrals for the same pair. Each row needs a distinct user, and
     * creating 1000 distinct users via {@code TestAuthHelper} is CI-expensive
     * (dominates the test runtime). 200 distinct users still catches a 5x+
     * regression with headroom, which is the actually-valuable signal.</p>
     */
    private static final int FIXTURE_COUNT = 200;

    /** Budget for the tasklet wall clock. See class Javadoc for rationale. */
    private static final Duration BUDGET = Duration.ofSeconds(60);

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Tasklet escalationTasklet;

    @Autowired
    private EscalationPolicyService escalationPolicyService;

    private UUID dvShelterId;
    private List<UUID> referringUserIds;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();

        User dvAdmin = authHelper.setupUserWithDvAccess(
                "perf-admin@test.fabt.org", "Perf Admin", new String[]{"PLATFORM_ADMIN"});
        HttpHeaders adminHeaders = authHelper.headersForUser(dvAdmin);

        // DV coordinator so the 1h threshold resolves a real recipient list.
        authHelper.setupUserWithDvAccess(
                "perf-coord@test.fabt.org", "Perf Coordinator", new String[]{"COORDINATOR"});

        // FIXTURE_COUNT distinct outreach workers — each will own exactly one
        // of the seeded pending referrals, satisfying the
        // uq_referral_token_pending (referring_user_id, shelter_id) constraint.
        // Setup cost is NOT measured — only the tasklet execution is timed.
        referringUserIds = new ArrayList<>(FIXTURE_COUNT);
        for (int i = 0; i < FIXTURE_COUNT; i++) {
            User u = authHelper.setupUserWithDvAccess(
                    "perf-outreach-" + i + "@test.fabt.org",
                    "Perf Outreach " + i,
                    new String[]{"OUTREACH_WORKER"});
            referringUserIds.add(u.getId());
        }

        // Clean slate — any prior test's referrals or notifications would skew
        // the timing.
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
        } catch (SQLException e) {
            throw new RuntimeException("setUp cleanup failed", e);
        }
        escalationPolicyService.clearCaches_testOnly();

        // Create one DV shelter via REST — needed for RLS-valid referrals
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            dvShelterId = createDvShelter(adminHeaders);
        });
    }

    @Test
    @DisplayName("T-52: FIXTURE_COUNT pending referrals escalate within 60s budget (regression guard)")
    void tasklet_processesFixturesUnderBudget() throws Exception {
        // Seed FIXTURE_COUNT pending referrals directly via JDBC, all backdated
        // past the 1h threshold so every row triggers a notification. Bulk
        // insert bypasses ReferralTokenService.createToken (which normally
        // snapshots escalation_policy_id) — leaving escalation_policy_id NULL
        // is a valid code path that exercises getDefaultPolicy fallback.
        seedPendingReferrals(FIXTURE_COUNT, 65);

        // Sanity: fixture count matches
        int seeded;
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            conn.createStatement().execute("RESET ROLE");
            try (var ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM referral_token WHERE tenant_id = ? AND status = 'PENDING'")) {
                ps.setObject(1, authHelper.getTestTenantId());
                var rs = ps.executeQuery();
                rs.next();
                seeded = rs.getInt(1);
            }
            conn.createStatement().execute("SET ROLE fabt_app");
        }
        assertThat(seeded).as("Seeded pending referrals").isEqualTo(FIXTURE_COUNT);

        // Run the escalation tasklet once and measure wall clock.
        long startNanos = System.nanoTime();
        TenantContext.runWithContext(null, true, () -> {
            try {
                escalationTasklet.execute(null, null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Duration actual = Duration.ofNanos(System.nanoTime() - startNanos);

        log.info("T-52 perf regression: tasklet processed {} referrals in {} ms (budget={} ms)",
                FIXTURE_COUNT, actual.toMillis(), BUDGET.toMillis());

        // Regression guard: loose budget to absorb CI noise. See class Javadoc.
        assertThat(actual)
                .as("Escalation tasklet wall clock for %d pending referrals", FIXTURE_COUNT)
                .isLessThanOrEqualTo(BUDGET);

        // Sanity: the tasklet should have created an escalation notification
        // for every distinct pending referral we seeded. Without this guard,
        // a "fast" run could be fast because it silently skipped the work
        // (Riley Cho's "don't measure a broken stopwatch" principle).
        //
        // Count DISTINCT payload->>'referralId' rather than raw notification
        // rows. Sibling test classes in the same mvn run (e.g.
        // ReferralEscalationIntegrationTest, ReferralEscalationFrozenPolicyTest)
        // leave their own DV coordinators alive in the test tenant, so
        // findDvCoordinatorIds returns N coordinators and the raw row count
        // is FIXTURE_COUNT × N. Distinct referralId is FIXTURE_COUNT regardless.
        int distinctReferrals;
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            conn.createStatement().execute("RESET ROLE");
            try (var ps = conn.prepareStatement(
                    "SELECT COUNT(DISTINCT payload->>'referralId') "
                    + "FROM notification WHERE tenant_id = ? AND type = 'escalation.1h'")) {
                ps.setObject(1, authHelper.getTestTenantId());
                var rs = ps.executeQuery();
                rs.next();
                distinctReferrals = rs.getInt(1);
            }
            conn.createStatement().execute("SET ROLE fabt_app");
        }
        assertThat(distinctReferrals)
                .as("Distinct escalated referrals (fast run must not mean skipped work)")
                .isEqualTo(FIXTURE_COUNT);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Bulk-insert {@code count} PENDING referrals backdated by {@code minutesAgo}
     * minutes, one per distinct referring user (constraint:
     * {@code uq_referral_token_pending} on {@code (referring_user_id, shelter_id)}).
     *
     * <p>Uses JDBC batchUpdate for speed — individual REST calls would
     * dominate the test runtime and crowd out the thing we're measuring.
     * Bypasses RLS by using the raw DataSource connection + RESET ROLE.
     * Sibling tests use the same pattern.</p>
     */
    private void seedPendingReferrals(int count, int minutesAgo) {
        if (count != referringUserIds.size()) {
            throw new IllegalStateException("count=" + count
                    + " must equal referringUserIds.size()=" + referringUserIds.size()
                    + " — uq_referral_token_pending requires one referral per user per shelter");
        }
        Instant backdated = Instant.now().minus(Duration.ofMinutes(minutesAgo));
        Instant expiresAt = Instant.now().plus(Duration.ofHours(4));
        UUID tenantId = authHelper.getTestTenantId();

        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            conn.createStatement().execute("RESET ROLE");
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO referral_token
                        (shelter_id, tenant_id, referring_user_id, household_size, population_type,
                         urgency, special_needs, callback_number, status, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                    """)) {
                for (int i = 0; i < count; i++) {
                    ps.setObject(1, dvShelterId);
                    ps.setObject(2, tenantId);
                    ps.setObject(3, referringUserIds.get(i));
                    ps.setInt(4, 1);
                    ps.setString(5, "DV_SURVIVOR");
                    ps.setString(6, "URGENT");
                    ps.setString(7, null);
                    ps.setString(8, "919-555-0100");
                    ps.setTimestamp(9, Timestamp.from(backdated));
                    ps.setTimestamp(10, Timestamp.from(expiresAt));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.createStatement().execute("SET ROLE fabt_app");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to seed pending referrals", e);
        }
    }

    private UUID createDvShelter(HttpHeaders headers) {
        String body = String.format("""
                {
                  "name": "Perf Test DV Shelter %s",
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
        return UUID.fromString(extractField(resp.getBody(), "id"));
    }

    private String extractField(String json, String field) {
        int idx = json.indexOf("\"" + field + "\":\"");
        if (idx < 0) throw new AssertionError("Field '" + field + "' not found: " + json);
        int start = idx + field.length() + 4;
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }
}
