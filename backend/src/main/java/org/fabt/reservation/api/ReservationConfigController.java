package org.fabt.reservation.api;

import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.fabt.shelter.api.HoldDurationRequest;
import org.fabt.tenant.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
 * <p>Audit: this endpoint relies on the standard tenant-update audit path
 * (TenantService.setHoldDurationMinutes → tenantRepository.save). Per
 * design D5 H4 / warroom M2: NOT annotated @PlatformAdminOnly, so the
 * Phase G JustificationValidationFilter does NOT apply (no
 * X-Platform-Justification header required). COC_ADMIN's role is
 * sufficient.
 *
 * <p>Demo-mode behavior: DemoGuardFilter explicitly blocks this endpoint
 * with a friendly message branch (see DemoGuardFilter.getBlockMessage,
 * pattern matches {@code /api/v1/admin/tenants/[^/]+/hold-duration}).
 */
@RestController
@RequestMapping("/api/v1/admin/tenants")
public class ReservationConfigController {

    private static final Logger log = LoggerFactory.getLogger(ReservationConfigController.class);

    private final TenantService tenantService;

    public ReservationConfigController(TenantService tenantService) {
        this.tenantService = tenantService;
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
            @Valid @RequestBody HoldDurationRequest request) {
        log.info("COC_ADMIN-triggered hold-duration update: tenant={} newMinutes={}",
                tenantId, request.holdDurationMinutes());

        tenantService.setHoldDurationMinutes(tenantId, request.holdDurationMinutes());

        return ResponseEntity.ok(Map.of(
                "tenantId", tenantId.toString(),
                "holdDurationMinutes", request.holdDurationMinutes()
        ));
    }
}
