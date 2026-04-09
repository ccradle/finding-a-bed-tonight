package org.fabt.notification.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.fabt.notification.domain.Notification;
import org.fabt.notification.service.NotificationPersistenceService;
import org.fabt.notification.service.NotificationService;
import org.fabt.shared.web.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "Persistent notifications and real-time SSE stream")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);
    private static final int DEFAULT_LIMIT = 50;

    private final NotificationService notificationService;
    private final NotificationPersistenceService notificationPersistenceService;

    public NotificationController(NotificationService notificationService,
                                  NotificationPersistenceService notificationPersistenceService) {
        this.notificationService = notificationService;
        this.notificationPersistenceService = notificationPersistenceService;
    }

    @Operation(
            summary = "Subscribe to real-time notifications via SSE",
            description = "Returns a Server-Sent Events stream. Supports Authorization header (preferred) "
                    + "or query parameter (?token=<jwt>) for legacy EventSource clients. "
                    + "On reconnect, pass Last-Event-ID header to replay missed events. "
                    + "Events: connected, heartbeat, refresh, dv-referral.responded, dv-referral.requested, availability.updated."
    )
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            Authentication authentication,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader,
            HttpServletResponse response) {
        // Prevent Cloudflare and upstream nginx proxies from buffering the SSE stream
        response.setHeader("X-Accel-Buffering", "no");
        UUID userId = (UUID) authentication.getPrincipal();
        UUID tenantId = TenantContext.getTenantId();
        boolean dvAccess = TenantContext.getDvAccess();
        String[] roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .toArray(String[]::new);

        Long lastEventId = null;
        if (lastEventIdHeader != null && !lastEventIdHeader.isBlank()) {
            try {
                lastEventId = Long.parseLong(lastEventIdHeader);
            } catch (NumberFormatException e) {
                log.debug("Invalid Last-Event-ID header: {}", lastEventIdHeader);
            }
        }

        log.debug("SSE stream requested by user {} (tenant {}, lastEventId={})", userId, tenantId, lastEventId);
        SseEmitter emitter = notificationService.register(userId, tenantId, roles, dvAccess, lastEventId);

        // SSE catch-up (Design D4): deliver unread persistent notifications after connection.
        // Done here (not in NotificationService) to avoid circular dependency with
        // NotificationPersistenceService. Runs on a virtual thread to avoid blocking the
        // servlet thread — the emitter is async, so writes after return are safe.
        //
        // IMPORTANT: ScopedValue does NOT inherit to Thread.ofVirtual().start() — only to
        // child threads created within StructuredTaskScope. We must explicitly bind
        // TenantContext inside the new thread so RLS can resolve app.current_user_id.
        //
        // KNOWN RACE: If the user marks a notification as read between SSE connect and
        // catch-up delivery, the catch-up may deliver it as unread. This is benign —
        // the frontend deduplicates by notification ID (T-39) and reconciles read state
        // from the REST count endpoint on mount (T-37).
        final UUID catchupUserId = userId;
        final UUID catchupTenantId = tenantId;
        Thread.ofVirtual().name("sse-catchup-" + userId).start(() -> {
            TenantContext.runWithContext(catchupTenantId, catchupUserId, false, () -> {
                try {
                    List<Notification> unread = notificationPersistenceService.findUnreadForCatchup(catchupUserId, DEFAULT_LIMIT);
                    for (Notification n : unread) {
                        notificationService.pushNotification(catchupUserId, n);
                    }
                    if (!unread.isEmpty()) {
                        log.debug("SSE catch-up: delivered {} unread notifications to user {}", unread.size(), catchupUserId);
                    }
                } catch (Exception e) {
                    log.debug("SSE catch-up failed for user {}: {}", catchupUserId, e.getMessage());
                }
            });
        });

        return emitter;
    }

    @Operation(
            summary = "List notifications for authenticated user",
            description = "Returns notifications ordered by severity DESC, created_at DESC. "
                    + "Use ?unread=true to filter to unread only."
    )
    @GetMapping
    public List<Notification> list(
            Authentication authentication,
            @RequestParam(value = "unread", required = false, defaultValue = "false") boolean unreadOnly) {
        UUID userId = (UUID) authentication.getPrincipal();
        return notificationPersistenceService.findByRecipientId(userId, unreadOnly, DEFAULT_LIMIT);
    }

    @Operation(
            summary = "Get unread notification count",
            description = "Returns {\"unread\": N} for bell badge display."
    )
    @GetMapping("/count")
    public Map<String, Integer> count(Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return Map.of("unread", notificationPersistenceService.countUnread(userId));
    }

    @Operation(
            summary = "Mark a notification as read",
            description = "Sets read_at timestamp. Idempotent — returns 204 even if already read."
    )
    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID id, Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        notificationPersistenceService.markRead(id, userId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Mark a notification as acted on",
            description = "Sets acted_at and read_at timestamps. For CRITICAL notifications that require action."
    )
    @PatchMapping("/{id}/acted")
    public ResponseEntity<Void> markActed(@PathVariable UUID id, Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        notificationPersistenceService.markActed(id, userId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Mark all notifications as read",
            description = "Sets read_at on all unread notifications for the authenticated user. "
                    + "CRITICAL severity notifications are excluded — they require explicit action "
                    + "(accept/reject the referral) before they can be dismissed (Design D3)."
    )
    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead(Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        notificationPersistenceService.markAllRead(userId);
        return ResponseEntity.noContent().build();
    }
}
