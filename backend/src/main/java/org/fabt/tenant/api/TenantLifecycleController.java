package org.fabt.tenant.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

import org.fabt.auth.platform.PlatformActionStateCapture;
import org.fabt.auth.platform.PlatformAdminOnly;
import org.fabt.shared.audit.AuditEventType;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantLifecycleService;
import org.fabt.tenant.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for tenant lifecycle state transitions (G-4.6, v0.53).
 *
 * <p>The {@link TenantLifecycleService} state machine and the
 * Phase F audit infrastructure already shipped — this controller is a
 * thin wrapper that exposes the four state transitions through the
 * platform-operator authority + audit-chain stack:</p>
 * <ul>
 *   <li>{@code POST /api/v1/tenants/{id}/suspend}   → {@code suspend(...)}</li>
 *   <li>{@code POST /api/v1/tenants/{id}/unsuspend} → {@code unsuspend(...)}</li>
 *   <li>{@code POST /api/v1/tenants/{id}/offboard}  → {@code offboard(...)}</li>
 *   <li>{@code DELETE /api/v1/tenants/{id}}         → {@code hardDelete(...)}</li>
 * </ul>
 *
 * <p><b>Authorization stack (per endpoint):</b>
 * <ol>
 *   <li>{@code @PreAuthorize("hasRole('PLATFORM_OPERATOR')")} — only the
 *       separate platform-operator identity (iss=fabt-platform JWT) can
 *       reach this method.</li>
 *   <li>{@code @PlatformAdminOnly(emits=PLATFORM_TENANT_<ACTION>)} —
 *       requires {@code MFA_VERIFIED} authority + {@code X-Platform-Justification}
 *       header (≥10 ASCII chars), commits a PAL row + a chained
 *       {@code audit_event} row BEFORE the service runs (Decision 11).</li>
 * </ol>
 *
 * <p><b>State capture:</b> each endpoint calls
 * {@link PlatformActionStateCapture#captureBefore} immediately before
 * invoking the service and {@link PlatformActionStateCapture#captureAfter}
 * immediately after (when applicable). The allowlist is intentionally
 * small — {@code slug}, {@code name}, {@code state}, {@code archivedAt} —
 * so credentials, OAuth secrets, API keys, and DEKs cannot leak into
 * the PAL row even if the {@link Tenant} entity grows.</p>
 *
 * <p><b>F13 caveat:</b> the aspect's PAL row is committed BEFORE
 * {@code proceed()} (Decision 11), and V89's append-only trigger
 * blocks the after-write update. So {@code captureAfter}'s state is
 * surfaced in application logs (MDC {@code platform_action=true}) but
 * does NOT reach the persisted PAL row in v0.53. Captured for
 * forensic continuity now; F13 follow-up restructures the aspect to
 * commit after {@code proceed()} so after-state lands in the row.</p>
 *
 * <p><b>Audit chain:</b> each endpoint emits its row in the TARGET
 * tenant's chain (Decision 13) so per-tenant chain integrity surfaces
 * the lifecycle event. Hard-delete is the exception — that row's
 * {@code tenant_id} is forced to {@code SYSTEM_TENANT_ID} so the audit
 * row survives the cascade delete of the target tenant.</p>
 *
 * <p>Out-of-band psql lifecycle actions remain documented in the
 * runbook as emergency-only (and break the audit posture on purpose).
 * See the platform-admin-split-and-access-log design.md F12 for the
 * decision-record on why this slice was added to v0.53.</p>
 */
@RestController
@RequestMapping("/api/v1/tenants")
@ConditionalOnProperty(name = "fabt.tenant.lifecycle.enabled", havingValue = "true", matchIfMissing = false)
public class TenantLifecycleController {

    private static final Logger log = LoggerFactory.getLogger(TenantLifecycleController.class);

    private final TenantLifecycleService lifecycleService;
    private final TenantService tenantService;
    private final PlatformActionStateCapture stateCapture;

    public TenantLifecycleController(TenantLifecycleService lifecycleService,
                                      TenantService tenantService,
                                      PlatformActionStateCapture stateCapture) {
        this.lifecycleService = lifecycleService;
        this.tenantService = tenantService;
        this.stateCapture = stateCapture;
    }

    @Operation(
            summary = "Suspend a tenant",
            description = "Flips tenant.state ACTIVE → SUSPENDED. Suspended tenants reject all non-platform "
                    + "JWT-issuance and login flows; existing JWTs continue to validate until expiry "
                    + "(token_version remains stable). Idempotent only via the state-machine — calling "
                    + "suspend on an already-SUSPENDED tenant returns the existing service exception "
                    + "(HTTP 409 via TenantLifecycleExceptionAdvice).")
    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @PlatformAdminOnly(
            reason = "Tenant suspension — pauses all non-platform auth flows for the target tenant; platform authority required",
            emits = AuditEventType.PLATFORM_TENANT_SUSPENDED)
    public ResponseEntity<TenantResponse> suspend(
            // Parameter MUST be named exactly `tenantId` — PlatformAdminLogger.resolveActionTenantId
            // (G-4.3) walks the method's reflective parameters looking for a UUID parameter with
            // that name to populate the audit_event row's tenant_id (Decision 13's per-tenant chain).
            // Naming the param `id` (URL-friendly) silently falls back to SYSTEM_TENANT_ID — the
            // chained AE row lands in the wrong chain with no warning. Map the URL `{id}` to the
            // contract-required parameter name via @PathVariable("id").
            @Parameter(description = "UUID of the tenant to suspend") @PathVariable("id") UUID tenantId,
            @Parameter(description = "Operator-asserted reason — also used by the @PlatformAdminOnly aspect")
            @RequestHeader("X-Platform-Justification") String justification,
            Authentication authentication) {
        UUID actorUserId = parseActorOrNull(authentication);
        captureLifecycleBefore(tenantId);

        Tenant updated = lifecycleService.suspend(tenantId, actorUserId, justification);

        captureLifecycleAfter(updated);
        log.info("PLATFORM_OPERATOR-triggered tenant suspend: tenant={} actor={}", tenantId, actorUserId);
        return ResponseEntity.ok(TenantResponse.from(updated));
    }

    @Operation(
            summary = "Unsuspend a tenant",
            description = "Flips tenant.state SUSPENDED → ACTIVE. Restores normal JWT-issuance + "
                    + "login flows. State-machine rejects unsuspend on a non-SUSPENDED tenant.")
    @PostMapping("/{id}/unsuspend")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @PlatformAdminOnly(
            reason = "Tenant unsuspension — restores non-platform auth flows for the target tenant; platform authority required",
            emits = AuditEventType.PLATFORM_TENANT_UNSUSPENDED)
    public ResponseEntity<TenantResponse> unsuspend(
            // See suspend() above for the @PathVariable("id") UUID tenantId rationale.
            @Parameter(description = "UUID of the tenant to unsuspend") @PathVariable("id") UUID tenantId,
            @RequestHeader("X-Platform-Justification") String justification,
            Authentication authentication) {
        UUID actorUserId = parseActorOrNull(authentication);
        captureLifecycleBefore(tenantId);

        Tenant updated = lifecycleService.unsuspend(tenantId, actorUserId, justification);

        captureLifecycleAfter(updated);
        log.info("PLATFORM_OPERATOR-triggered tenant unsuspend: tenant={} actor={}", tenantId, actorUserId);
        return ResponseEntity.ok(TenantResponse.from(updated));
    }

    @Operation(
            summary = "Offboard a tenant",
            description = "Flips tenant.state ACTIVE-or-SUSPENDED → OFFBOARDED, sets archived_at. "
                    + "Generates an offboard export receipt URI per Phase F (referenced via "
                    + "tenant.offboard_export_receipt_uri). Offboarded tenants are read-only "
                    + "destinations for the chain verifier + audit forensics; no new auth flows succeed. "
                    + "State-machine rejects offboard on an already-OFFBOARDED or HARD_DELETED tenant.")
    @PostMapping("/{id}/offboard")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @PlatformAdminOnly(
            reason = "Tenant offboarding — terminal-state transition that archives all auth flows and seeds the export receipt; platform authority required",
            emits = AuditEventType.PLATFORM_TENANT_OFFBOARDED)
    public ResponseEntity<TenantResponse> offboard(
            // See suspend() above for the @PathVariable("id") UUID tenantId rationale.
            @Parameter(description = "UUID of the tenant to offboard") @PathVariable("id") UUID tenantId,
            @RequestHeader("X-Platform-Justification") String justification,
            Authentication authentication) {
        UUID actorUserId = parseActorOrNull(authentication);
        captureLifecycleBefore(tenantId);

        Tenant updated = lifecycleService.offboard(tenantId, actorUserId, justification);

        captureLifecycleAfter(updated);
        log.info("PLATFORM_OPERATOR-triggered tenant offboard: tenant={} actor={}", tenantId, actorUserId);
        return ResponseEntity.ok(TenantResponse.from(updated));
    }

    @Operation(
            summary = "Hard-delete a tenant (destructive — Phase F crypto-shred)",
            description = "Final step of the Phase F crypto-shred lifecycle: cascades the tenant row "
                    + "delete + DEK-shred so subsequent reads of any tenant-encrypted ciphertext "
                    + "decrypt to bytes that are not recoverable through this system's key material. "
                    + "State-machine requires OFFBOARDED as the prior state. The audit row is forced "
                    + "to SYSTEM_TENANT_ID (Decision 13) so it survives the cascade delete and remains "
                    + "queryable for forensic and audit-trail review. Returns 204 No Content on "
                    + "success — there is no tenant left to return.")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @PlatformAdminOnly(
            reason = "Tenant hard-delete — cascades delete + DEK-shred, irreversible; platform authority required",
            emits = AuditEventType.PLATFORM_TENANT_HARD_DELETED)
    public ResponseEntity<Void> hardDelete(
            // See suspend() above for the @PathVariable("id") UUID tenantId rationale. Note
            // that PlatformAdminLogger.resolveActionTenantId hard-overrides the AE row's
            // tenant_id to SYSTEM_TENANT_ID for PLATFORM_TENANT_HARD_DELETED (Decision 13)
            // — the row must survive the cascade-delete of the target tenant. The parameter
            // name still matters for the resource_id field on the PAL row.
            @Parameter(description = "UUID of the tenant to hard-delete") @PathVariable("id") UUID tenantId,
            @RequestHeader("X-Platform-Justification") String justification,
            Authentication authentication) {
        UUID actorUserId = parseActorOrNull(authentication);
        captureLifecycleBefore(tenantId);

        lifecycleService.hardDelete(tenantId, actorUserId, justification);

        // After hard-delete, the tenant row is gone — synthesize the
        // post-state for in-memory forensics. (PAL row already committed
        // pre-proceed per Decision 11; this just decorates the application
        // log line via the aspect's MDC platform_action=true marker.)
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("state", "HARD_DELETED");
        after.put("hard_deleted_at", java.time.Instant.now().toString());
        stateCapture.captureAfter(after);

        log.info("PLATFORM_OPERATOR-triggered tenant hard-delete: tenant={} actor={}", tenantId, actorUserId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Captures the pre-action state of the target tenant for the
     * {@link PlatformActionStateCapture} bean. Allowlist mirrors the
     * task §6a.3 spec: {@code slug}, {@code name}, {@code state},
     * {@code archived_at} — never {@code id} (already in the path) or
     * crypto material. Quietly no-ops if the tenant is already gone
     * (lookup miss → nothing useful to capture).
     */
    private void captureLifecycleBefore(UUID tenantId) {
        tenantService.findById(tenantId).ifPresent(t -> {
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("slug", t.getSlug());
            before.put("name", t.getName());
            before.put("state", t.getState() != null ? t.getState().name() : null);
            before.put("archived_at", t.getArchivedAt() != null ? t.getArchivedAt().toString() : null);
            stateCapture.captureBefore(before);
        });
    }

    /**
     * Captures the post-action state from the {@link Tenant} returned
     * by the service. Same allowlist as before-state.
     */
    private void captureLifecycleAfter(Tenant updated) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("slug", updated.getSlug());
        after.put("name", updated.getName());
        after.put("state", updated.getState() != null ? updated.getState().name() : null);
        after.put("archived_at", updated.getArchivedAt() != null ? updated.getArchivedAt().toString() : null);
        stateCapture.captureAfter(after);
    }

    /**
     * Resolves the platform-operator UUID from the
     * {@link Authentication} principal. Returns null if the principal
     * is not a parseable UUID (e.g., legacy tenant JWT path that
     * shouldn't reach here per @PreAuthorize, but defensive handling
     * keeps the audit chain happy).
     */
    private static UUID parseActorOrNull(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            return null;
        }
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
