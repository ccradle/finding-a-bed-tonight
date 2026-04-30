package org.fabt.shelter.api;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import org.fabt.shared.web.TenantContext;
import org.fabt.shelter.service.ShelterService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only access to the caller's tenant's resolved
 * {@code active_counties} list — used to populate the bed-search county
 * filter dropdown for OUTREACH_WORKER and the admin shelter-edit county
 * field for COC_ADMIN.
 *
 * <p>transitional-reentry-support slice 4 prereq, warroom H1.
 * {@code TenantConfigController} (the broader read of {@code tenant.config})
 * is COC_ADMIN-only — by design, since other config keys (DV address
 * visibility, observability settings, etc.) are sensitive. Exposing
 * {@code active_counties} on a separate, narrower endpoint avoids
 * widening that authorization scope while giving the bed-search UI the
 * single value it needs.
 *
 * <p>Authorization: any authenticated user of the caller's own tenant.
 * Tenant scoping is enforced via {@link TenantContext#getTenantId()} —
 * there is no path-tenantId, so cross-tenant abuse is structurally
 * impossible (verify-round-2 C1 lesson). <strong>Deliberate omission
 * (warroom-3 S3):</strong> if a future PR adds a path-tenantId variant
 * for "platform operator wants to peek at another tenant's counties,"
 * the cross-tenant guard from verify-round-2 C1 must be re-applied —
 * this no-path design is what makes the safety property structural
 * rather than guarded.
 *
 * <p>Rate-limit posture (warroom-3 M3): no dedicated Bucket4j tag.
 * Read-only response with at-most-100-entry payload, low DoS risk.
 * Falls back to whatever default per-user authenticated rate limits
 * the platform applies elsewhere. If a future profiler shows this
 * endpoint as a hot path, add a dedicated Bucket4j key.
 *
 * <p>Response shape: {@code {"activeCounties": ["..."]}}. Wrapping the
 * array in an object is forward-compatible — a future "validation
 * disabled" or "fallback applied" signal can ride alongside without
 * breaking the contract.
 */
@RestController
@RequestMapping("/api/v1")
public class ActiveCountiesController {

    private final ShelterService shelterService;

    public ActiveCountiesController(ShelterService shelterService) {
        this.shelterService = shelterService;
    }

    @Operation(
            summary = "Read the active counties list for the caller's tenant",
            description = "Returns the resolved list a UI dropdown should populate from. "
                    + "When the tenant has not configured active_counties, falls back to "
                    + "the NC 100-county default list. When configured to an empty array, "
                    + "returns an empty list — UI should render a free-text input. "
                    + "Authenticated; tenant-scoped via JWT context."
    )
    @GetMapping("/active-counties")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> getActiveCounties() {
        List<String> counties = shelterService.getActiveCounties(TenantContext.getTenantId());
        return ResponseEntity.ok(Map.of("activeCounties", counties));
    }
}
