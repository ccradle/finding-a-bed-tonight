package org.fabt.notification.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.fabt.notification.domain.Notification;
import org.fabt.notification.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write-through notification service (Design D5).
 * Writes to PostgreSQL AND pushes to SSE emitter in the same method call.
 * If the user is connected, they get real-time SSE. If not, the DB row
 * waits for catch-up on next login.
 */
@Service
public class NotificationPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(NotificationPersistenceService.class);
    private static final int CLEANUP_RETENTION_DAYS = 90;

    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;

    public NotificationPersistenceService(NotificationRepository notificationRepository,
                                          NotificationService notificationService) {
        this.notificationRepository = notificationRepository;
        this.notificationService = notificationService;
    }

    /**
     * Create a persistent notification and push via SSE if recipient is connected.
     * This is the single entry point for all notification creation.
     */
    @Transactional
    public Notification send(UUID tenantId, UUID recipientId, String type, String severity, String payload) {
        Notification notification = new Notification(tenantId, recipientId, type, severity, payload);
        Notification saved = notificationRepository.save(notification);

        // Push to SSE if user is connected (best-effort, non-blocking)
        try {
            notificationService.pushNotification(recipientId, saved);
        } catch (Exception e) {
            // SSE push failure is non-fatal — notification is persisted in DB.
            // User will receive it via catch-up on next SSE connect.
            log.debug("SSE push failed for notification {} to user {} — will deliver via catch-up",
                    saved.getId(), recipientId, e);
        }

        log.info("Notification created: id={}, type={}, severity={}, recipient={}",
                saved.getId(), type, severity, recipientId);
        return saved;
    }

    /**
     * Send to multiple recipients (e.g., surge activation to all coordinators).
     * Uses batch INSERT (single SQL round-trip) then fans out SSE pushes.
     * Scales to hundreds of recipients without N+1 INSERT overhead.
     *
     * <p><b>SSE push uses a synthetic notification ID</b> (UUID.randomUUID()) because
     * the batch INSERT generates IDs server-side and doesn't return them. The frontend
     * deduplicates by SSE event ID (monotonic counter), not by notification ID, so
     * this is safe. When the user reconnects, catch-up delivers the real DB-generated
     * IDs via {@link #findUnreadForCatchup}.</p>
     */
    @Transactional
    public void sendToAll(UUID tenantId, List<UUID> recipientIds, String type, String severity, String payload) {
        if (recipientIds.isEmpty()) return;

        // Single batch INSERT — O(1) SQL round-trip regardless of recipient count
        UUID[] ids = recipientIds.toArray(UUID[]::new);
        int inserted = notificationRepository.batchInsert(tenantId, ids, type, severity, payload);

        log.info("Batch notification created: type={}, severity={}, recipients={}, inserted={}",
                type, severity, recipientIds.size(), inserted);

        // Fan out SSE pushes to connected recipients (best-effort, non-blocking).
        // Notifications are persisted — disconnected users get them via catch-up.
        for (UUID recipientId : recipientIds) {
            try {
                // Build a lightweight Notification for SSE push (no DB round-trip).
                // Synthetic ID is fine — frontend deduplicates by SSE event ID, not notification ID.
                // The real DB-generated ID will arrive via catch-up if the user reconnects.
                Notification stub = new Notification(tenantId, recipientId, type, severity, payload);
                stub.setId(UUID.randomUUID());
                notificationService.pushNotification(recipientId, stub);
            } catch (Exception e) {
                log.debug("SSE push failed for recipient {} — will deliver via catch-up", recipientId, e);
            }
        }
    }

    /**
     * Fetch unread notifications for SSE catch-up on login.
     * Ordered by severity (CRITICAL first) then most recent.
     */
    @Transactional(readOnly = true)
    public List<Notification> findUnreadForCatchup(UUID recipientId, int limit) {
        return notificationRepository.findUnreadByRecipientId(recipientId, limit, 0);
    }

    @Transactional(readOnly = true)
    public int countUnread(UUID recipientId) {
        return notificationRepository.countUnreadByRecipientId(recipientId);
    }

    @Transactional(readOnly = true)
    public List<Notification> findByRecipientId(UUID recipientId, boolean unreadOnly, int limit) {
        return findByRecipientId(recipientId, unreadOnly, limit, 0);
    }

    @Transactional(readOnly = true)
    public List<Notification> findByRecipientId(UUID recipientId, boolean unreadOnly, int limit, int offset) {
        if (unreadOnly) {
            return notificationRepository.findUnreadByRecipientId(recipientId, limit, offset);
        }
        return notificationRepository.findByRecipientId(recipientId, limit, offset);
    }

    @Transactional
    public void markRead(UUID id, UUID recipientId) {
        int updated = notificationRepository.markRead(id, recipientId);
        if (updated == 0) {
            log.debug("markRead no-op: notification {} not found or not owned by user {}", id, recipientId);
        }
    }

    @Transactional
    public void markActed(UUID id, UUID recipientId) {
        int updated = notificationRepository.markActed(id, recipientId);
        if (updated == 0) {
            log.debug("markActed no-op: notification {} not found or not owned by user {}", id, recipientId);
        }
    }

    @Transactional
    public void markAllRead(UUID recipientId) {
        int updated = notificationRepository.markAllRead(recipientId);
        log.debug("markAllRead: {} notifications marked read for user {} (CRITICAL excluded)",
                updated, recipientId);
    }

    /**
     * Cleanup: delete read notifications older than 90 days.
     * Unread CRITICAL notifications are never auto-deleted (Design D8).
     * NOTE: For multi-instance deployments, add ShedLock.
     */
    @Scheduled(fixedRate = 86_400_000) // daily
    public void cleanupOldNotifications() {
        java.sql.Timestamp cutoff = java.sql.Timestamp.from(
                Instant.now().minus(CLEANUP_RETENTION_DAYS, ChronoUnit.DAYS));
        int deleted = notificationRepository.deleteOldRead(cutoff);
        log.info("Notification cleanup: deleted {} read notifications older than {} days", deleted, CLEANUP_RETENTION_DAYS);
    }
}
