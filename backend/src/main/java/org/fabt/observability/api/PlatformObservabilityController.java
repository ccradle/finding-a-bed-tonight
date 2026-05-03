package org.fabt.observability.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.fabt.auth.platform.PlatformAdminOnly;
import org.fabt.observability.OperationalMonitorService;
import org.fabt.observability.PlatformConfig;
import org.fabt.observability.PlatformConfigService;
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.audit.AuditEventType;
import org.fabt.shared.web.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-operator endpoint for instance-wide observability settings.
 * Splits the tenant-agnostic fields out of the old per-tenant config
 * per the platform-observability-split openspec change (2026-05-02).
 *
 * <p>Authorized only for {@code PLATFORM_OPERATOR} role. Writes require
 * the {@code X-Platform-Justification} header (enforced by the
 * {@code @PlatformAdminOnly} aspect).
 */
@RestController
@RequestMapping("/api/v1/platform/observability")
public class PlatformObservabilityController {

    private final PlatformConfigService configService;
    private final OperationalMonitorService monitorService;
    private final ApplicationEventPublisher eventPublisher;

    public PlatformObservabilityController(PlatformConfigService configService,
                                         OperationalMonitorService monitorService,
                                         ApplicationEventPublisher eventPublisher) {
        this.configService = configService;
        this.monitorService = monitorService;
        this.eventPublisher = eventPublisher;
    }

    @Operation(
            summary = "Get platform-wide observability config",
            description = "Returns the 6 instance-wide settings (prometheus, tracing, "
                    + "and the 3 monitor intervals) from the platform_config singleton. "
                    + "Requires PLATFORM_OPERATOR role."
    )
    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    public ResponseEntity<PlatformConfig> get() {
        return ResponseEntity.ok(configService.get());
    }

    @Operation(
            summary = "Update platform-wide observability config",
            description = "Applies a partial update to the platform_config JSONB. "
                    + "Changes to monitor intervals trigger an immediate reschedule "
                    + "of the OperationalMonitorService tasks. "
                    + "Requires PLATFORM_OPERATOR role + X-Platform-Justification header."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Config updated successfully"),
            @ApiResponse(responseCode = "400", description = "Validation failure (interval out of range or malformed endpoint)"),
            @ApiResponse(responseCode = "403", description = "Not a platform operator or MFA not verified")
    })
    @PutMapping
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @PlatformAdminOnly(
            reason = "Platform-wide observability config update — affects monitoring/tracing posture for the entire instance",
            emits = AuditEventType.PLATFORM_OBSERVABILITY_UPDATED)
    public ResponseEntity<PlatformConfig> update(
            @Parameter(description = "Partial update map. Recognized keys: prometheus_enabled, "
                    + "tracing_enabled, tracing_endpoint, monitor_stale_interval_minutes, "
                    + "monitor_dv_canary_interval_minutes, monitor_temperature_interval_minutes.")
            @RequestBody Map<String, Object> patch,
            Authentication authentication) {

        UUID actorId = parseUserId(authentication);
        
        // Capture state before update for audit comparison
        PlatformConfig before = configService.get();
        
        // Apply update. Throws StructuredErrorException on validation failure.
        PlatformConfig after = configService.update(patch, actorId);
        
        // Emit per-field audit rows (D5)
        emitFieldAudits(actorId, before, after, patch);
        
        // Trigger reschedule if any monitor interval changed
        if (patch.containsKey("monitor_stale_interval_minutes") ||
            patch.containsKey("monitor_dv_canary_interval_minutes") ||
            patch.containsKey("monitor_temperature_interval_minutes")) {
            monitorService.rescheduleFromConfig();
        }
        
        return ResponseEntity.ok(after);
    }

    /**
     * Emits one {@link AuditEventType#PLATFORM_OBSERVABILITY_UPDATED} row per
     * changed field (design D5). This is ADDITIONAL to the attempted-action
     * audit row emitted by the {@code @PlatformAdminOnly} aspect.
     */
    private void emitFieldAudits(UUID actorId, PlatformConfig before, PlatformConfig after, Map<String, Object> patch) {
        for (String key : patch.keySet()) {
            Object oldValue = getFieldValue(before, key);
            Object newValue = getFieldValue(after, key);
            boolean changed = !java.util.Objects.equals(oldValue, newValue);
            
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("field", key);
            details.put("old_value", oldValue);
            details.put("new_value", newValue);
            details.put("value_changed", changed);
            details.put("outcome", "applied");
            
            // Platform-wide action: AE.tenant_id = SYSTEM_TENANT_ID, NOT chained.
            eventPublisher.publishEvent(new AuditEventRecord(
                    actorId, TenantContext.SYSTEM_TENANT_ID, 
                    AuditEventType.PLATFORM_OBSERVABILITY_UPDATED, details, null));
        }
    }

    private Object getFieldValue(PlatformConfig cfg, String key) {
        return switch (key) {
            case "prometheus_enabled" -> cfg.prometheusEnabled();
            case "tracing_enabled" -> cfg.tracingEnabled();
            case "tracing_endpoint" -> cfg.tracingEndpoint();
            case "monitor_stale_interval_minutes" -> cfg.monitorStaleIntervalMinutes();
            case "monitor_dv_canary_interval_minutes" -> cfg.monitorDvCanaryIntervalMinutes();
            case "monitor_temperature_interval_minutes" -> cfg.monitorTemperatureIntervalMinutes();
            default -> null;
        };
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
