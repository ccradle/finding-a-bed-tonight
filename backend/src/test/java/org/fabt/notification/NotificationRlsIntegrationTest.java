package org.fabt.notification;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.notification.domain.Notification;
import org.fabt.notification.service.NotificationPersistenceService;
import org.fabt.shared.web.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for notification table RLS policies.
 * Verifies: read isolation by recipient, service writes for arbitrary recipients,
 * nil UUID returns zero rows, markAllRead excludes CRITICAL (Design D3).
 */
class NotificationRlsIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private NotificationPersistenceService notificationPersistenceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User coordinatorUser;
    private User outreachUser;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        coordinatorUser = authHelper.setupCoordinatorUser();
        outreachUser = authHelper.setupOutreachWorkerUser();

        // Clean up notifications from previous tests to prevent cross-test contamination.
        // Tests share coordinatorUser/outreachUser (findOrCreate), so accumulated
        // notifications from prior tests would cause false passes or flaky failures.
        //
        // Note: notification DELETE policy is USING (true) — unrestricted for fabt_app.
        // No TenantContext needed for DELETE.
        jdbcTemplate.update("DELETE FROM notification WHERE recipient_id IN (?, ?)",
                coordinatorUser.getId(), outreachUser.getId());
    }

    @Test
    @DisplayName("Service can write notification for a different user (INSERT unrestricted)")
    void serviceCanWriteForArbitraryRecipient() {
        // Outreach worker's request context, but notification is for coordinator
        TenantContext.runWithContext(authHelper.getTestTenantId(), outreachUser.getId(), false, () -> {
            Notification notification = notificationPersistenceService.send(
                    authHelper.getTestTenantId(), coordinatorUser.getId(),
                    "referral.requested", "ACTION_REQUIRED",
                    "{\"referralId\":\"" + UUID.randomUUID() + "\"}");

            assertThat(notification).isNotNull();
            assertThat(notification.getRecipientId()).isEqualTo(coordinatorUser.getId());
        });
    }

    @Test
    @DisplayName("User can only read their own notifications via REST (RLS enforced)")
    void userCanOnlyReadOwnNotifications() {
        // Create notifications for both users (as system)
        TenantContext.runWithContext(authHelper.getTestTenantId(), false, () -> {
            notificationPersistenceService.send(
                    authHelper.getTestTenantId(), coordinatorUser.getId(),
                    "test.coordinator", "INFO", "{}");
            notificationPersistenceService.send(
                    authHelper.getTestTenantId(), outreachUser.getId(),
                    "test.outreach", "INFO", "{}");
        });

        // Coordinator should only see their own notification
        ResponseEntity<String> coordResponse = restTemplate.exchange(
                "/api/v1/notifications?unread=true",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                String.class);

        assertThat(coordResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(coordResponse.getBody()).contains("test.coordinator");
        assertThat(coordResponse.getBody()).doesNotContain("test.outreach");

        // Outreach worker should only see their own notification
        ResponseEntity<String> outreachResponse = restTemplate.exchange(
                "/api/v1/notifications?unread=true",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.outreachWorkerHeaders()),
                String.class);

        assertThat(outreachResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outreachResponse.getBody()).contains("test.outreach");
        assertThat(outreachResponse.getBody()).doesNotContain("test.coordinator");
    }

    @Test
    @DisplayName("Unread count reflects only own notifications")
    void unreadCountScopedToRecipient() {
        TenantContext.runWithContext(authHelper.getTestTenantId(), false, () -> {
            notificationPersistenceService.send(
                    authHelper.getTestTenantId(), coordinatorUser.getId(),
                    "test.a", "INFO", "{}");
            notificationPersistenceService.send(
                    authHelper.getTestTenantId(), coordinatorUser.getId(),
                    "test.b", "INFO", "{}");
            notificationPersistenceService.send(
                    authHelper.getTestTenantId(), outreachUser.getId(),
                    "test.c", "INFO", "{}");
        });

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/notifications/count",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Coordinator has at least 2 unread (test.a and test.b), not 3
        assertThat(response.getBody()).doesNotContain("\"unread\":0");
    }

    @Test
    @DisplayName("markRead on another user's notification is a no-op (RLS blocks UPDATE)")
    void markReadOnOtherUsersNotificationIsNoop() {
        // Create notification for coordinator
        final UUID[] notifId = new UUID[1];
        TenantContext.runWithContext(authHelper.getTestTenantId(), false, () -> {
            Notification n = notificationPersistenceService.send(
                    authHelper.getTestTenantId(), coordinatorUser.getId(),
                    "test.rls", "ACTION_REQUIRED", "{}");
            notifId[0] = n.getId();
        });

        // Outreach worker tries to mark coordinator's notification as read
        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/notifications/" + notifId[0] + "/read",
                HttpMethod.PATCH,
                new HttpEntity<>(authHelper.outreachWorkerHeaders()),
                Void.class);

        // Returns 204 (idempotent) but notification remains unread for coordinator
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Verify coordinator's notification is still unread
        ResponseEntity<String> coordCount = restTemplate.exchange(
                "/api/v1/notifications/count",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                String.class);
        assertThat(coordCount.getBody()).doesNotContain("\"unread\":0");
    }

    @Test
    @DisplayName("markAllRead excludes CRITICAL notifications (Design D3)")
    void markAllReadExcludesCritical() {
        TenantContext.runWithContext(authHelper.getTestTenantId(), false, () -> {
            notificationPersistenceService.send(
                    authHelper.getTestTenantId(), coordinatorUser.getId(),
                    "test.info", "INFO", "{}");
            notificationPersistenceService.send(
                    authHelper.getTestTenantId(), coordinatorUser.getId(),
                    "test.critical", "CRITICAL", "{}");
        });

        // Mark all as read
        restTemplate.exchange(
                "/api/v1/notifications/read-all",
                HttpMethod.POST,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                Void.class);

        // Unread count should still be > 0 (CRITICAL survives)
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/notifications/count",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain("\"unread\":0");
    }

    @Test
    @DisplayName("Cross-tenant isolation — Tenant B user cannot read Tenant A notifications")
    void crossTenantNotificationIsolation() {
        // Create notification in the default test tenant
        TenantContext.runWithContext(authHelper.getTestTenantId(), false, () -> {
            notificationPersistenceService.send(
                    authHelper.getTestTenantId(), coordinatorUser.getId(),
                    "test.tenant-a", "INFO", "{}");
        });

        // Create a second tenant with its own coordinator
        var tenantB = authHelper.setupTestTenant("rls-tenant-b");
        var coordB = authHelper.setupUserWithDvAccess(
                "coord-rls-b@test.fabt.org", "Coordinator B", new String[]{"COORDINATOR"});

        // Create a notification in Tenant B
        TenantContext.runWithContext(tenantB.getId(), false, () -> {
            notificationPersistenceService.send(
                    tenantB.getId(), coordB.getId(),
                    "test.tenant-b", "INFO", "{}");
        });

        // Coordinator in Tenant A should see only their notification
        ResponseEntity<String> tenantAResponse = restTemplate.exchange(
                "/api/v1/notifications?unread=true",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                String.class);
        assertThat(tenantAResponse.getBody()).contains("test.tenant-a");
        assertThat(tenantAResponse.getBody()).doesNotContain("test.tenant-b");

        // Coordinator in Tenant B should see only their notification
        ResponseEntity<String> tenantBResponse = restTemplate.exchange(
                "/api/v1/notifications?unread=true",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.headersForUser(coordB)),
                String.class);
        assertThat(tenantBResponse.getBody()).contains("test.tenant-b");
        assertThat(tenantBResponse.getBody()).doesNotContain("test.tenant-a");
    }

    @Test
    @DisplayName("Batch sendToAll creates notifications for multiple recipients")
    void batchSendToAllCreatesForAllRecipients() {
        TenantContext.runWithContext(authHelper.getTestTenantId(), false, () -> {
            notificationPersistenceService.sendToAll(
                    authHelper.getTestTenantId(),
                    List.of(coordinatorUser.getId(), outreachUser.getId()),
                    "surge.activated", "CRITICAL",
                    "{\"surgeEventId\":\"" + UUID.randomUUID() + "\"}");
        });

        // Both users should have the notification
        ResponseEntity<String> coordResponse = restTemplate.exchange(
                "/api/v1/notifications?unread=true",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                String.class);
        assertThat(coordResponse.getBody()).contains("surge.activated");

        ResponseEntity<String> outreachResponse = restTemplate.exchange(
                "/api/v1/notifications?unread=true",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.outreachWorkerHeaders()),
                String.class);
        assertThat(outreachResponse.getBody()).contains("surge.activated");
    }

    @Test
    @DisplayName("Cleanup deletes old read notifications but preserves unread CRITICAL (Design D8)")
    void cleanupDeletesOldReadPreservesUnreadCritical() {
        // Create notifications as system (no user context needed for INSERT — RLS allows)
        TenantContext.runWithContext(authHelper.getTestTenantId(), false, () -> {
            notificationPersistenceService.send(
                    authHelper.getTestTenantId(), coordinatorUser.getId(),
                    "test.old-read", "INFO", "{}");
            notificationPersistenceService.send(
                    authHelper.getTestTenantId(), coordinatorUser.getId(),
                    "test.old-critical", "CRITICAL", "{}");
        });

        // Mark the INFO one as read — needs coordinator's userId for UPDATE RLS policy
        TenantContext.runWithContext(authHelper.getTestTenantId(), coordinatorUser.getId(), false, () -> {
            notificationPersistenceService.markAllRead(coordinatorUser.getId());

            // Backdate both notifications to 91 days ago
            jdbcTemplate.update(
                    "UPDATE notification SET created_at = ? WHERE recipient_id = ? AND type IN ('test.old-read', 'test.old-critical')",
                    java.sql.Timestamp.from(Instant.now().minus(91, ChronoUnit.DAYS)),
                    coordinatorUser.getId());
        });

        // Run cleanup — DELETE policy is unrestricted, repo uses Timestamp for correct PG binding
        notificationPersistenceService.cleanupOldNotifications();

        // The read INFO should be deleted, the unread CRITICAL should survive
        ResponseEntity<String> notifResp = restTemplate.exchange(
                "/api/v1/notifications",
                HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                String.class);
        assertThat(notifResp.getBody()).doesNotContain("test.old-read");
        assertThat(notifResp.getBody()).contains("test.old-critical");
    }

    @Test
    @DisplayName("markActed on CRITICAL → read_at and acted_at set, count decrements")
    void markActedOnCriticalClearsItFromUnread() {
        final UUID[] notifId = new UUID[1];
        TenantContext.runWithContext(authHelper.getTestTenantId(), false, () -> {
            Notification n = notificationPersistenceService.send(
                    authHelper.getTestTenantId(), coordinatorUser.getId(),
                    "test.acted-critical", "CRITICAL", "{}");
            notifId[0] = n.getId();
        });

        // Verify it's unread
        ResponseEntity<String> beforeCount = restTemplate.exchange(
                "/api/v1/notifications/count", HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()), String.class);
        assertThat(beforeCount.getBody()).doesNotContain("\"unread\":0");

        // Act on the CRITICAL notification
        ResponseEntity<Void> actResp = restTemplate.exchange(
                "/api/v1/notifications/" + notifId[0] + "/acted",
                HttpMethod.PATCH,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                Void.class);
        assertThat(actResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Unread list should no longer contain the acted notification
        ResponseEntity<String> afterNotifs = restTemplate.exchange(
                "/api/v1/notifications?unread=true", HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()), String.class);
        assertThat(afterNotifs.getBody()).doesNotContain("test.acted-critical");
    }

    @Test
    @DisplayName("markAllRead then count → returns only CRITICAL unread count")
    void markAllReadThenCountReturnsOnlyCriticalCount() {
        TenantContext.runWithContext(authHelper.getTestTenantId(), false, () -> {
            notificationPersistenceService.send(
                    authHelper.getTestTenantId(), outreachUser.getId(),
                    "test.count-info1", "INFO", "{}");
            notificationPersistenceService.send(
                    authHelper.getTestTenantId(), outreachUser.getId(),
                    "test.count-action", "ACTION_REQUIRED", "{}");
            notificationPersistenceService.send(
                    authHelper.getTestTenantId(), outreachUser.getId(),
                    "test.count-critical", "CRITICAL", "{}");
        });

        // Mark all as read (CRITICAL excluded)
        restTemplate.exchange(
                "/api/v1/notifications/read-all", HttpMethod.POST,
                new HttpEntity<>(authHelper.outreachWorkerHeaders()), Void.class);

        // Count should reflect only the CRITICAL notification as unread
        ResponseEntity<String> countResp = restTemplate.exchange(
                "/api/v1/notifications/count", HttpMethod.GET,
                new HttpEntity<>(authHelper.outreachWorkerHeaders()), String.class);
        assertThat(countResp.getBody()).doesNotContain("\"unread\":0");

        // Unread list should contain only the CRITICAL notification
        ResponseEntity<String> unreadResp = restTemplate.exchange(
                "/api/v1/notifications?unread=true", HttpMethod.GET,
                new HttpEntity<>(authHelper.outreachWorkerHeaders()), String.class);
        assertThat(unreadResp.getBody()).contains("test.count-critical");
        assertThat(unreadResp.getBody()).doesNotContain("test.count-info1");
        assertThat(unreadResp.getBody()).doesNotContain("test.count-action");
    }

    @Test
    @DisplayName("markRead on non-existent UUID → 204, no crash (idempotent)")
    void markReadOnNonExistentUuidReturns204() {
        UUID fakeId = UUID.randomUUID();
        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/notifications/" + fakeId + "/read",
                HttpMethod.PATCH,
                new HttpEntity<>(authHelper.coordinatorHeaders()),
                Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("Cleanup at 89 days → notification preserved (boundary condition)")
    void cleanupAt89DaysPreservesNotification() {
        TenantContext.runWithContext(authHelper.getTestTenantId(), false, () -> {
            notificationPersistenceService.send(
                    authHelper.getTestTenantId(), coordinatorUser.getId(),
                    "test.boundary-89", "INFO", "{}");
        });

        // Mark as read — needs coordinator userId for UPDATE RLS policy
        TenantContext.runWithContext(authHelper.getTestTenantId(), coordinatorUser.getId(), false, () -> {
            notificationPersistenceService.markAllRead(coordinatorUser.getId());

            // Backdate to 89 days ago — inside the 90-day retention window
            jdbcTemplate.update(
                    "UPDATE notification SET created_at = ? WHERE recipient_id = ? AND type = 'test.boundary-89'",
                    java.sql.Timestamp.from(Instant.now().minus(89, ChronoUnit.DAYS)),
                    coordinatorUser.getId());
        });

        // Run cleanup — DELETE policy unrestricted, no context needed
        notificationPersistenceService.cleanupOldNotifications();

        // Should survive — 89 < 90 retention days
        ResponseEntity<String> notifResp = restTemplate.exchange(
                "/api/v1/notifications", HttpMethod.GET,
                new HttpEntity<>(authHelper.coordinatorHeaders()), String.class);
        assertThat(notifResp.getBody()).contains("test.boundary-89");
    }
}
