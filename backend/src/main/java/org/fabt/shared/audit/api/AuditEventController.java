package org.fabt.shared.audit.api;

import java.util.List;

import org.fabt.shared.audit.AuditEventEntity;
import org.fabt.shared.audit.AuditEventService;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-events")
@PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
public class AuditEventController {

    private final AuditEventService auditEventService;

    public AuditEventController(AuditEventService auditEventService) {
        this.auditEventService = auditEventService;
    }

    @Operation(
            summary = "Query audit events for a target user",
            description = "Returns audit events for the specified user in reverse chronological order. " +
                    "Includes role changes, dvAccess changes, deactivation/reactivation, password resets. " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @GetMapping
    public ResponseEntity<List<AuditEventResponse>> getAuditEvents(
            @RequestParam UUID targetUserId) {
        List<AuditEventResponse> events = auditEventService.findByTargetUserId(targetUserId)
                .stream()
                .map(AuditEventResponse::from)
                .toList();
        return ResponseEntity.ok(events);
    }

    public record AuditEventResponse(
            UUID id,
            String timestamp,
            UUID actorUserId,
            UUID targetUserId,
            String action,
            String details,
            String ipAddress
    ) {
        public static AuditEventResponse from(AuditEventEntity entity) {
            return new AuditEventResponse(
                    entity.getId(),
                    entity.getTimestamp() != null ? entity.getTimestamp().toString() : null,
                    entity.getActorUserId(),
                    entity.getTargetUserId(),
                    entity.getAction(),
                    entity.getDetails() != null ? entity.getDetails().value() : null,
                    entity.getIpAddress());
        }
    }
}
