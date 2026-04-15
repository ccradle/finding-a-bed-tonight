package org.fabt.notification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.notification.domain.Notification;
import org.fabt.notification.service.NotificationPersistenceService;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 task 7.1a — measurement gate for per-notification markActed.
 *
 * <p>The deep-link lifecycle (Phase 3 task 7.2 / 7.3) wires each
 * successful domain action (accept a DV referral, confirm a hold, etc.)
 * to a frontend call that fans out PATCH /acted across every notification
 * sharing the same payload identifier. The canonical worst case for a
 * single referral is the request + four escalations (1h, 2h, 3.5h, 4h) =
 * 5 PATCH calls. Elena's note on the task: per-notification PATCH is
 * preferred — a batch endpoint adds a JSONB expression index the app
 * doesn't otherwise need.</p>
 *
 * <p>This test pins the performance budget: 5 sequential PATCHes from a
 * test client must complete in &le; 500ms wall time. If this ever blows
 * the budget (e.g., from Flyway adding an expensive trigger or from RLS
 * policy growth), the gate forces us to reconsider the batch endpoint
 * (task 8.2) before coordinators start hitting it in production.</p>
 *
 * <p>Sequential (not parallel) is the pessimistic measurement — the
 * frontend uses Promise.all so production will typically be faster.
 * Sequential also matches the shape a constrained environment (e.g.,
 * HTTP/1.1 with a single connection or a stringent rate limiter) would
 * impose.</p>
 */
class MarkActedMeasurementTest extends BaseIntegrationTest {

    /** Per the task, 500ms for 5 sequential PATCHes. */
    private static final long WALL_TIME_BUDGET_MS = 500L;
    /** 1 request + 4 escalations = 5 total, per the canonical worst case. */
    private static final int EXPECTED_ROUND_TRIPS = 5;

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private NotificationPersistenceService notificationPersistenceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User coordinator;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        coordinator = authHelper.setupCoordinatorUser();
        jdbcTemplate.update("DELETE FROM notification WHERE recipient_id = ?", coordinator.getId());
    }

    @Test
    @DisplayName("7.1a — 5 sequential PATCH /acted calls for one referral complete in ≤ 500ms")
    void markActed_fiveNotifications_sameReferralId_withinBudget() {
        // Create the canonical scenario: 1 request + 4 escalation notifications
        // all carrying the same referralId. This is what a coordinator sees
        // when the full escalation chain fires before they act.
        UUID referralId = UUID.randomUUID();
        String payload = "{\"referralId\":\"" + referralId + "\"}";
        List<UUID> notificationIds = new ArrayList<>(EXPECTED_ROUND_TRIPS);
        String[] types = { "referral.requested", "escalation.1h", "escalation.2h",
                "escalation.3_5h", "escalation.4h" };
        for (String type : types) {
            final String notifType = type;
            Notification n = TenantContext.callWithContext(authHelper.getTestTenantId(), false,
                    () -> notificationPersistenceService.send(
                            coordinator.getId(), notifType, "CRITICAL", payload));
            notificationIds.add(n.getId());
        }
        assertThat(notificationIds)
                .as("setup should produce exactly the round-trip count we budget")
                .hasSize(EXPECTED_ROUND_TRIPS);

        // Fire 5 sequential PATCH /acted calls, measured end-to-end.
        long startNanos = System.nanoTime();
        for (UUID id : notificationIds) {
            ResponseEntity<String> resp = restTemplate.exchange(
                    "/api/v1/notifications/" + id + "/acted",
                    HttpMethod.PATCH,
                    new HttpEntity<>(authHelper.coordinatorHeaders()),
                    String.class);
            // Controller returns 204 No Content (ResponseEntity<Void>). Accept
            // any 2xx — the interesting signal here is wall time, not status.
            assertThat(resp.getStatusCode().is2xxSuccessful())
                    .as("PATCH /acted should succeed — got %s", resp.getStatusCode())
                    .isTrue();
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        assertThat(elapsedMs)
                .as("5 sequential markActed PATCHes took %dms; budget is %dms. "
                        + "If this assertion starts failing, revisit task 8.2 batch endpoint "
                        + "before shipping more Phase 3 lifecycle work.",
                        elapsedMs, WALL_TIME_BUDGET_MS)
                .isLessThanOrEqualTo(WALL_TIME_BUDGET_MS);

        // Sanity check: each notification's acted_at is now populated.
        Integer actedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE recipient_id = ? AND acted_at IS NOT NULL",
                Integer.class, coordinator.getId());
        assertThat(actedCount)
                .as("all 5 notifications should be marked acted after the PATCH batch")
                .isEqualTo(EXPECTED_ROUND_TRIPS);
    }
}
