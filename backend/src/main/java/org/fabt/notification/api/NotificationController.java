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
import org.springframework.web.bind.annotation.GetMapping;
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
            description = "Returns a Server-Sent Events stream. Token must be passed as query parameter "
                    + "(?token=<jwt>) because EventSource API does not support custom headers. "
                    + "Events: dv-referral.responded, dv-referral.requested, availability.updated."
    )
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        UUID tenantId = TenantContext.getTenantId();
        boolean dvAccess = TenantContext.getDvAccess();
        String[] roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .toArray(String[]::new);

        log.debug("SSE stream requested by user {} (tenant {})", userId, tenantId);
        return notificationService.register(userId, tenantId, roles, dvAccess);
    }
}
