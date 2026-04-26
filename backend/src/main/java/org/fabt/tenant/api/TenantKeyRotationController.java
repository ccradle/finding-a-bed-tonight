package org.fabt.tenant.api;

import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.fabt.shared.security.TenantKeyRotationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoint to rotate a tenant's JWT signing key, per A4 design Q4
 * + warroom W3 (Casey audit) + W4 (rate limit). Emits 202 +
 * {@code JWT_KEY_GENERATION_BUMPED} audit event (handled inside
 * {@link TenantKeyRotationService} so it joins the rotation
 * transaction).
 *
 * <p>G-4.4 migrated this endpoint to {@code @PreAuthorize PLATFORM_OPERATOR
 * + @PlatformAdminOnly} so the action requires (a) the platform-JWT
 * iss=fabt-platform identity, (b) MFA_VERIFIED authority on that JWT,
 * (c) X-Platform-Justification header (≥10 chars ASCII), and (d) lands
 * a PAL row + chained audit_event. The PLATFORM_OPERATOR role is
 * intentionally cross-tenant — they can rotate ANY tenant's key, which
 * is the intended platform-level capability for emergency response.
 *
 * <p><b>Rate limit (deferred):</b> warroom Q4 + Marcus M3 + Jordan call
 * for 1 rotation/tenant/min to prevent accidental rapid-rotation that
 * could exhaust DB connections via the dual-key-accept grace window's
 * jwt_revocations growth. Phase A4.3 ships without the rate limiter
 * (current FABT rate-limit infrastructure is per-IP via Bucket4j; per-
 * tenant rate-limit-config table lives in Phase E task 6.1). Tracked as
 * a follow-up issue; safe interim because PLATFORM_OPERATOR is a small
 * trusted group with mandatory MFA, the @PlatformAdminOnly aspect
 * captures every invocation in PAL, and every rotation also produces
 * an audit_event row visible to incident responders.
 */
@RestController
@RequestMapping("/api/v1/admin/tenants")
public class TenantKeyRotationController {

    private static final Logger log = LoggerFactory.getLogger(TenantKeyRotationController.class);

    private final TenantKeyRotationService rotationService;

    public TenantKeyRotationController(TenantKeyRotationService rotationService) {
        this.rotationService = rotationService;
    }

    @Operation(
            summary = "Rotate a tenant's JWT signing key (PLATFORM_OPERATOR + MFA + justification header)",
            description = "Bumps the tenant's jwt_key_generation, deactivates the prior "
                    + "generation, adds all outstanding kids to the jwt_revocations "
                    + "blocklist (7-day expires_at ceiling), and invalidates the "
                    + "kid-resolution caches. New JWTs issued after rotation use the "
                    + "new generation's signing key; in-flight tokens of the prior "
                    + "generation are rejected with 401 token_revoked on next validate. "
                    + "Emits a JWT_KEY_GENERATION_BUMPED audit event with "
                    + "{tenantId, oldGen, newGen, actorUserId, revokedKidCount} for "
                    + "compliance trail. Returns 202 + summary.")
    @PostMapping("/{tenantId}/rotate-jwt-key")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @org.fabt.auth.platform.PlatformAdminOnly(
            reason = "Per-tenant JWT signing-key rotation — invalidates every in-flight token for the target tenant; platform authority required",
            emits = org.fabt.shared.audit.AuditEventType.PLATFORM_KEY_ROTATED)
    public ResponseEntity<Map<String, Object>> rotateJwtKey(
            @Parameter(description = "UUID of the tenant whose JWT key to rotate")
            @PathVariable UUID tenantId,
            Authentication auth) {
        UUID actorUserId = parseActorOrNull(auth);
        log.info("PLATFORM_OPERATOR-triggered JWT key rotation: tenant={} actor={}",
                tenantId, actorUserId);

        TenantKeyRotationService.RotationResult result =
                rotationService.bumpJwtKeyGeneration(tenantId, actorUserId);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "status", "rotated",
                "tenantId", result.tenantId().toString(),
                "oldGeneration", result.oldGeneration(),
                "newGeneration", result.newGeneration(),
                "revokedKidCount", result.revokedKidCount(),
                "rotatedAt", result.rotatedAt().toString()));
    }

    private static UUID parseActorOrNull(Authentication auth) {
        if (auth == null || auth.getName() == null) return null;
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
