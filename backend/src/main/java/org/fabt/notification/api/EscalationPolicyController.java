package org.fabt.notification.api;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;

import org.fabt.notification.domain.EscalationPolicy;
import org.fabt.notification.service.EscalationPolicyService;
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.audit.AuditEventTypes;
import org.fabt.shared.event.DomainEvent;
import org.fabt.shared.event.EventBus;
import org.fabt.shared.web.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for per-tenant escalation policy management (T-18, T-19, D7).
 *
 * <p>URL prefix is {@code /api/v1/admin/escalation-policy/{eventType}} per
 * the spec — this is a deliberate exception to the project's "module-prefixed
 * URL" convention because the auth gate ({@code COC_ADMIN+}) and the frontend
 * grouping (admin tab) make the {@code admin} segment a useful semantic
 * signal. The controller's Java location follows the modular monolith
 * (notification module owns the policy domain).</p>
 *
 * <p><b>Append-only:</b> the PATCH endpoint never UPDATEs an existing row.
 * It calls {@link EscalationPolicyService#update}, which inserts a new
 * version row with version+1 — preserving the audit trail. Old policy ids
 * remain valid for any {@code referral_token} that snapshotted them
 * (frozen-at-creation, T-23 load-bearing test).</p>
 */
@RestController
@RequestMapping("/api/v1/admin/escalation-policy")
public class EscalationPolicyController {

    private static final Logger log = LoggerFactory.getLogger(EscalationPolicyController.class);

    private final EscalationPolicyService escalationPolicyService;
    private final ApplicationEventPublisher eventPublisher;
    private final EventBus eventBus;

    public EscalationPolicyController(EscalationPolicyService escalationPolicyService,
                                       ApplicationEventPublisher eventPublisher,
                                       EventBus eventBus) {
        this.escalationPolicyService = escalationPolicyService;
        this.eventPublisher = eventPublisher;
        this.eventBus = eventBus;
    }

    @Operation(
            summary = "Get the current escalation policy for an event type",
            description = "Returns the current policy for the caller's tenant. Falls back to "
                    + "the platform default if no tenant-specific policy exists. Authorized "
                    + "for CoC admins and platform admins."
    )
    @GetMapping("/{eventType}")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<EscalationPolicyDto> getCurrentPolicy(
            @Parameter(description = "Event type identifier (e.g. 'dv-referral')") @PathVariable String eventType) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new AccessDeniedException("Tenant context required to read escalation policy");
        }

        return escalationPolicyService.getCurrentForTenant(tenantId, eventType)
                .map(this::toDto)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @Operation(
            summary = "Publish a new version of the escalation policy",
            description = "Validates and inserts a new versioned row. Existing referrals keep "
                    + "their frozen policy (frozen-at-creation, D7) — only NEW referrals will "
                    + "snapshot the new version. Returns 400 with structured error on "
                    + "validation failure (monotonic threshold violation, invalid role, etc.). "
                    + "Writes ESCALATION_POLICY_UPDATED audit event. Authorized for CoC admins."
    )
    @PatchMapping("/{eventType}")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<EscalationPolicyDto> updatePolicy(
            @PathVariable String eventType,
            @Valid @RequestBody UpdateEscalationPolicyRequest request,
            Authentication authentication) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new AccessDeniedException("Tenant context required to update escalation policy");
        }
        UUID actorUserId = UUID.fromString(authentication.getName());

        List<EscalationPolicy.Threshold> domainThresholds;
        try {
            domainThresholds = request.thresholds().stream()
                    .map(this::toDomainThreshold)
                    .toList();
        } catch (DateTimeParseException e) {
            // Bean Validation can't parse Duration; surface as 400 instead of 500.
            throw new IllegalArgumentException(
                    "One or more thresholds have an invalid 'at' duration. "
                    + "Use ISO-8601 duration format (e.g. PT1H, PT2H30M): " + e.getMessage());
        }

        // D11: service sources tenantId from TenantContext internally —
        // no pass-through. The tenantId var above is still used for the
        // audit event payload below; keeping the local for readability.
        EscalationPolicy created = escalationPolicyService.update(
                eventType, domainThresholds, actorUserId);

        // Audit: who published, what version, what tenant — but NOT the
        // thresholds JSON, which is recoverable from the row itself.
        // Casey Drummond #3 (war room round 3): include previousVersion so a
        // subpoena can answer "what changed?" without joining audit_events
        // back to escalation_policy. Versions are sequential within
        // (tenant_id, event_type) so previousVersion = newVersion - 1; on
        // the very first tenant PATCH (newVersion=1) the previousVersion is
        // null (the seeded platform default has its own row with tenant_id
        // NULL — not part of this tenant's history).
        java.util.Map<String, Object> auditDetails = new java.util.HashMap<>();
        auditDetails.put("tenantId", tenantId.toString());
        auditDetails.put("eventType", eventType);
        auditDetails.put("version", created.version());
        if (created.version() > 1) {
            auditDetails.put("previousVersion", created.version() - 1);
        }
        publishAudit(actorUserId, created.id(), AuditEventTypes.ESCALATION_POLICY_UPDATED, auditDetails);

        // SSE: tell connected admin clients the policy has changed so any
        // open editor can refresh and any new-referral creation form can
        // re-fetch the current shape.
        eventBus.publish(new DomainEvent("referral.policy-updated", tenantId,
                java.util.Map.of(
                        "policyId", created.id().toString(),
                        "eventType", eventType,
                        "version", created.version())));

        log.info("Escalation policy published: tenantId={}, eventType={}, version={}, actor={}",
                tenantId, eventType, created.version(), actorUserId);

        return ResponseEntity.ok(toDto(created));
    }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    private EscalationPolicyDto toDto(EscalationPolicy p) {
        return new EscalationPolicyDto(
                p.id(),
                p.tenantId(),
                p.eventType(),
                p.version(),
                p.thresholds().stream().map(this::toThresholdDto).toList(),
                p.createdAt(),
                p.createdBy());
    }

    private EscalationPolicyThresholdDto toThresholdDto(EscalationPolicy.Threshold t) {
        return new EscalationPolicyThresholdDto(t.id(), t.at().toString(), t.severity(), t.recipients());
    }

    private EscalationPolicy.Threshold toDomainThreshold(EscalationPolicyThresholdDto dto) {
        return new EscalationPolicy.Threshold(
                dto.id(),
                Duration.parse(dto.at()),
                dto.severity(),
                dto.recipients());
    }

    private void publishAudit(UUID actorUserId, UUID targetId, String action, Object details) {
        eventPublisher.publishEvent(new AuditEventRecord(
                actorUserId, targetId, action, details, /* ipAddress */ null));
    }
}
