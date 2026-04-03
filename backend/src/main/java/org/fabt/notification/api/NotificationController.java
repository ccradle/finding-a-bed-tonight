package org.fabt.notification.api;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.fabt.notification.service.NotificationService;
import org.fabt.shared.web.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "Server-Sent Events for real-time notifications")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
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
        return notificationService.register(userId, tenantId, roles, dvAccess, lastEventId);
    }
}
