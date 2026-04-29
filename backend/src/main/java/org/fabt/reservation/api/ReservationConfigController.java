package org.fabt.reservation.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.audit.AuditEventType;
import org.fabt.shelter.api.HoldDurationRequest;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Admin endpoints for tenant-scoped reservation configuration
 * (transitional-reentry-support task 4.5, slice 2C).
 *
 * <p>Why a new controller, not a method on {@link org.fabt.tenant.api.TenantController}:
 * the existing TenantController is annotated for PLATFORM_OPERATOR /
 * @PlatformAdminOnly authority on most write endpoints. Hold duration is
 * COC_ADMIN scope (design D5) — keeping it on a separate controller
 * makes the auth boundary visible in the URL surface.
 *
 * <p>Why a new controller, not a method on {@link org.fabt.tenant.api.TenantKeyRotationController}:
 * that controller is platform-operator-grade (key rotation is cross-tenant
 * platform authority). Hold duration is in-tenant operational config —
 * different reasoning, different role, different audit posture.
 *
 * <p>Audit: emits {@link AuditEventType#TENANT_CONFIG_UPDATED} after the
 * write succeeds (slice 2D warroom B1 fix — slice 2C had no audit
 * emission, so a COC_ADMIN could change hold duration with zero
 * forensic trail). Per design D5 H4 / warroom M2: NOT annotated
 * {@code @PlatformAdminOnly}, so the Phase G
 * {@code JustificationValidationFilter} does NOT apply (no
 * {@code X-Platform-Justification} header required). COC_ADMIN's role
 * is sufficient.
 *
 * <p>Demo-mode behavior: DemoGuardFilter explicitly blocks this endpoint
 * with a friendly message branch (see DemoGuardFilter.getBlockMessage,
 * pattern matches {@code /api/v1/admin/tenants/[^/]+/hold-duration}).
 */
@RestController
@RequestMapping("/api/v1/admin/tenants")
public class ReservationConfigController {

    private final TenantService tenantService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public ReservationConfigController(TenantService tenantService,
                                       ApplicationEventPublisher eventPublisher,
                                       ObjectMapper objectMapper) {
        this.tenantService = tenantService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Operation(
            summary = "Update tenant hold duration (COC_ADMIN)",
            description = "Sets the new-hold expiry duration (minutes) for the given tenant. "
                    + "Range 30–480 enforced by Bean Validation on HoldDurationRequest. "
                    + "Affects only NEW holds — in-flight reservations retain their original "
                    + "expires_at (design D5). Returns the updated value as confirmation."
    )
    @PatchMapping("/{tenantId}/hold-duration")
    @PreAuthorize("hasRole('COC_ADMIN')")
    public ResponseEntity<Map<String, Object>> updateHoldDuration(
            @Parameter(description = "UUID of the tenant whose hold duration to update")
            @PathVariable UUID tenantId,
            @Valid @RequestBody HoldDurationRequest request,
            Authentication authentication) {
        // Capture old value BEFORE the write so the audit row can record the
        // pre-change state (slice 2D warroom B1). Look up via the read-side
        // path to avoid coupling to the write-path JSON-key casing decision.
        Integer oldValue = tenantService.findById(tenantId)
                .map(Tenant::getConfig)
                .map(c -> readHoldDurationFromConfig(objectMapper, c.value()))
                .orElse(null);

        tenantService.setHoldDurationMinutes(tenantId, request.holdDurationMinutes());

        UUID actorUserId = parseUserId(authentication);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("config_key", "hold_duration_minutes");
        details.put("old_value", oldValue);
        details.put("new_value", request.holdDurationMinutes());
        eventPublisher.publishEvent(new AuditEventRecord(
                actorUserId, null, AuditEventType.TENANT_CONFIG_UPDATED, details, null));

        return ResponseEntity.ok(Map.of(
                "tenantId", tenantId.toString(),
                "holdDurationMinutes", request.holdDurationMinutes()
        ));
    }

    /**
     * Best-effort read of {@code hold_duration_minutes} from a tenant config
     * JSON string for the audit "old_value" capture. Returns null on parse
     * failure or absent key — the audit row will record null which the
     * forensic reader can interpret as "unknown prior value." We deliberately
     * do NOT throw here; the audit's purpose is to record the change, not
     * to gate the change on a clean prior state.
     */
    private static Integer readHoldDurationFromConfig(ObjectMapper objectMapper, String configJson) {
        if (configJson == null || configJson.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(configJson);
            JsonNode v = node.get("hold_duration_minutes");
            return v != null && v.isInt() ? v.asInt() : null;
        } catch (tools.jackson.core.JacksonException e) {
            return null;
        }
    }

    private static UUID parseUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) return null;
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
