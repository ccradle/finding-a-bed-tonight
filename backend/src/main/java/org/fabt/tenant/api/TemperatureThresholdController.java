package org.fabt.tenant.api;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.audit.AuditEventType;
import org.fabt.shared.errors.ErrorCodes;
import org.fabt.shared.errors.StructuredErrorException;
import org.fabt.shared.web.TenantContext;
import org.fabt.shared.web.TenantPathGuard;
import org.fabt.tenant.service.TenantService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-scoped controller for the surge-trigger temperature threshold
 * (per-tenant geographic concern — a downtown Asheville CoC and a coastal
 * Wilmington CoC need different surge thresholds for the same NOAA station
 * shape).
 *
 * <p>Per the platform-observability-split openspec change (2026-05-02), this
 * is the only observability knob that remains per-tenant. Platform-wide
 * observability config (prometheus, tracing, monitor cadences) lives on
 * {@code PlatformObservabilityController} (PLATFORM_OPERATOR scope).
 *
 * <p><b>Authorization:</b> {@code COC_ADMIN} of the target tenant.
 * COORDINATOR / OUTREACH_WORKER are forbidden (writing surge thresholds is
 * a config-grade operation, not a day-of-operations action). Cross-tenant
 * access returns 404 via {@link TenantPathGuard} (existence-leak prevention,
 * design D3).
 *
 * <p><b>Audit:</b> emits {@link AuditEventType#TENANT_CONFIG_UPDATED} with
 * {@code config_key: "temperature_threshold_f"} on every successful write,
 * mirroring the dv-policy-tenant-flag pattern (warroom round 4 §5.3 fix —
 * the original implementation silently swallowed the audit). The details
 * payload carries {@code old_value, new_value, value_changed, outcome:
 * "applied"} for downstream replay tooling.
 *
 * <p><b>Write-path concurrency:</b> read-modify-write of the {@code
 * tenant.config.observability} sub-map. Surge-threshold updates are
 * operator-driven and infrequent; the existing TenantService.updateConfig
 * has no row lock, so two simultaneous writes against unrelated keys could
 * theoretically race. We accept this risk because (a) writes against the
 * same tenant from two operators concurrently is vanishingly rare, and (b)
 * the lost-update would only affect a single config key, not break invariants.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/surge-threshold")
public class TemperatureThresholdController {

    private static final String CONFIG_KEY = "temperature_threshold_f";
    private static final double THRESHOLD_MIN_F = -50.0;
    private static final double THRESHOLD_MAX_F = 150.0;
    private static final double DEFAULT_THRESHOLD_F = 32.0;

    private final TenantService tenantService;
    private final ApplicationEventPublisher eventPublisher;

    public TemperatureThresholdController(TenantService tenantService,
                                          ApplicationEventPublisher eventPublisher) {
        this.tenantService = tenantService;
        this.eventPublisher = eventPublisher;
    }

    @Operation(
            summary = "Get temperature threshold for surge activation",
            description = "Returns the Fahrenheit threshold below which the platform "
                    + "recommends activating surge mode. Stored in tenant.config.observability. "
                    + "Requires COC_ADMIN role."
    )
    @GetMapping
    @PreAuthorize("hasRole('COC_ADMIN')")
    public ResponseEntity<Map<String, Object>> get(
            @Parameter(description = "UUID of the tenant") @PathVariable UUID tenantId) {
        TenantPathGuard.requireMatchingTenant(tenantId);
        double threshold = readThreshold(tenantId);
        return ResponseEntity.ok(Map.of(CONFIG_KEY, threshold));
    }

    @Operation(
            summary = "Update temperature threshold for surge activation",
            description = "Updates the threshold in the tenant's config JSONB. "
                    + "Accepted range: -50 to 150°F (D4). "
                    + "Emits a TENANT_CONFIG_UPDATED audit row with config_key=temperature_threshold_f. "
                    + "Requires COC_ADMIN role."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Threshold updated successfully"),
            @ApiResponse(responseCode = "400", description = "Value out of range, missing, or wrong type")
    })
    @PutMapping
    @PreAuthorize("hasRole('COC_ADMIN')")
    public ResponseEntity<Map<String, Object>> update(
            @Parameter(description = "UUID of the tenant") @PathVariable UUID tenantId,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        TenantPathGuard.requireMatchingTenant(tenantId);

        // Validation: dedicated error code (warroom round 4 B4 fix — was
        // wrongly using PLATFORM_OBSERVABILITY_INTERVAL_OUT_OF_RANGE).
        Object rawValue = body.get(CONFIG_KEY);
        if (rawValue == null) {
            throw new StructuredErrorException(
                    ErrorCodes.TENANT_SURGE_THRESHOLD_OUT_OF_RANGE,
                    "Missing field: " + CONFIG_KEY,
                    Map.of("field", CONFIG_KEY));
        }
        if (!(rawValue instanceof Number number)) {
            throw new StructuredErrorException(
                    ErrorCodes.TENANT_SURGE_THRESHOLD_OUT_OF_RANGE,
                    CONFIG_KEY + " must be a number",
                    Map.of("field", CONFIG_KEY, "received", String.valueOf(rawValue)));
        }
        double newValue = number.doubleValue();
        if (Double.isNaN(newValue) || Double.isInfinite(newValue)) {
            throw new StructuredErrorException(
                    ErrorCodes.TENANT_SURGE_THRESHOLD_OUT_OF_RANGE,
                    CONFIG_KEY + " must be a finite number",
                    Map.of("field", CONFIG_KEY));
        }
        if (newValue < THRESHOLD_MIN_F || newValue > THRESHOLD_MAX_F) {
            throw new StructuredErrorException(
                    ErrorCodes.TENANT_SURGE_THRESHOLD_OUT_OF_RANGE,
                    CONFIG_KEY + " must be in [" + THRESHOLD_MIN_F + ", " + THRESHOLD_MAX_F + "]°F",
                    Map.of("field", CONFIG_KEY, "received", newValue,
                            "min", THRESHOLD_MIN_F, "max", THRESHOLD_MAX_F));
        }

        // Read current value BEFORE the write so the audit row carries the
        // pre-change baseline. Defaults to DEFAULT_THRESHOLD_F if the tenant
        // has never set a threshold.
        double oldValue = readThreshold(tenantId);

        // Read-modify-write of the observability sub-map. Preserves any
        // sibling keys (noaa_station_id, the soon-to-be-dropped backward-read
        // platform-wide keys) that may live alongside temperature_threshold_f.
        Map<String, Object> currentConfig = tenantService.getConfig(tenantId);
        Map<String, Object> observability = extractObservability(currentConfig);
        observability.put(CONFIG_KEY, newValue);
        currentConfig.put("observability", observability);
        tenantService.updateConfig(tenantId, currentConfig);

        // Emit applied audit (warroom round 4 §5.3 fix — was missing).
        // Mirrors DvPolicyController.emitConfigUpdated shape.
        emitConfigUpdated(parseUserId(authentication), oldValue, newValue);

        return ResponseEntity.ok(Map.of(CONFIG_KEY, newValue));
    }

    /**
     * Returns the persisted threshold (defaulting to {@link #DEFAULT_THRESHOLD_F}
     * if absent or non-numeric). Shared between GET and the audit pre-read in PUT.
     */
    private double readThreshold(UUID tenantId) {
        Map<String, Object> config = tenantService.getConfig(tenantId);
        Map<String, Object> observability = extractObservability(config);
        Object stored = observability.get(CONFIG_KEY);
        if (stored instanceof Number n) {
            return n.doubleValue();
        }
        return DEFAULT_THRESHOLD_F;
    }

    /**
     * Returns a mutable map for the tenant's {@code observability} sub-map. If
     * absent or malformed, returns a fresh HashMap so callers can put-then-write
     * without NPE.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractObservability(Map<String, Object> tenantConfig) {
        Object existing = tenantConfig.get("observability");
        if (existing instanceof Map<?, ?> m) {
            // Defensive copy so we don't mutate a shared map by accident.
            return new HashMap<>((Map<String, Object>) m);
        }
        return new HashMap<>();
    }

    private void emitConfigUpdated(UUID actorUserId, double oldValue, double newValue) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("config_key", CONFIG_KEY);
        details.put("old_value", oldValue);
        details.put("new_value", newValue);
        details.put("value_changed", oldValue != newValue);
        details.put("outcome", "applied");
        // tenant_id resolved automatically by the audit persister from
        // TenantContext (we're inside a COC_ADMIN call so context is set).
        eventPublisher.publishEvent(new AuditEventRecord(
                actorUserId, TenantContext.getTenantId(),
                AuditEventType.TENANT_CONFIG_UPDATED, details, null));
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
