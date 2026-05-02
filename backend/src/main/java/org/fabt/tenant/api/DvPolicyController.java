package org.fabt.tenant.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.audit.AuditEventType;
import org.fabt.shared.errors.ErrorCodes;
import org.fabt.shared.errors.StructuredErrorException;
import org.fabt.shared.web.TenantContext;
import org.fabt.shelter.repository.ShelterRepository;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Admin endpoint for the tenant-scoped DV-policy flag
 * (dv-policy-tenant-flag OpenSpec change, capability
 * {@code dv-policy-tenant-flag}).
 *
 * <p>The flag {@code tenant.config.dv_policy_enabled} acknowledges that the
 * Continuum of Care operates Domestic Violence shelters. Setting the flag
 * to {@code true} authorizes per-shelter {@code dv_shelter = true} writes
 * elsewhere (enforced in {@code ShelterService.create / update / activate}).
 *
 * <p><b>Why a new controller, not a method on
 * {@link org.fabt.tenant.api.TenantController}:</b> mirrors the rationale
 * recorded on {@code ReservationConfigController}. TenantController is
 * platform-operator-grade for most write endpoints; this is COC_ADMIN
 * scope (operator decision 2026-05-01 — "Platform Operator likely doesn't
 * have the training to understand domestic violence"). Keeping the
 * COC_ADMIN auth boundary visible in the URL surface makes the role
 * separation legible.
 *
 * <p><b>Audit:</b> emits {@link AuditEventType#TENANT_CONFIG_UPDATED} on
 * every successful write AND on every rejected disable attempt (the latter
 * for forensic visibility per Marcus's warroom guidance — "the attempt
 * itself is forensically interesting"). The details payload carries
 * {@code outcome}, {@code rejection_code}, and {@code remaining_dv_shelter_count}
 * fields that distinguish applied vs. rejected outcomes.
 *
 * <p><b>Order-of-operations on rejected disable (warroom round 1):</b> the
 * audit row MUST be emitted BEFORE the rejection exception is thrown. If
 * the throw runs first, the audit is lost (the exception propagates through
 * the controller before the publishEvent call executes).
 *
 * <p><b>Tenant scoping (warroom round 1):</b> the path's {@code tenantId}
 * MUST equal the caller's JWT-bound {@code TenantContext.getTenantId()}.
 * The check executes BEFORE any DV-shelter inventory query so a probe from
 * Tenant A cannot learn whether Tenant B has DV shelters via timing or via
 * the error message. A 403 from cross-tenant access carries no body
 * (no existence-leak).
 *
 * <p>TODO(arch): with this third instance of the JSONB-config-key
 * dedicated-PATCH-endpoint pattern (after {@code hold_duration_minutes}
 * and {@code features.reentryMode} indirectly), the next config endpoint
 * should trigger an ADR for extracting a generic config-key endpoint
 * convention. Captured as openspec follow-up §16.1.
 */
@RestController
@RequestMapping("/api/v1/admin/tenants")
public class DvPolicyController {

    private final TenantService tenantService;
    private final ShelterRepository shelterRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public DvPolicyController(TenantService tenantService,
                              ShelterRepository shelterRepository,
                              ApplicationEventPublisher eventPublisher,
                              ObjectMapper objectMapper) {
        this.tenantService = tenantService;
        this.shelterRepository = shelterRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Operation(
            summary = "Update tenant DV-policy flag (COC_ADMIN)",
            description = "Sets the dv_policy_enabled JSONB flag on tenant.config. "
                    + "True authorizes per-shelter dv_shelter=true writes. "
                    + "False is forbidden while any active DV shelter exists on the "
                    + "tenant — the operator must first deactivate every active DV "
                    + "shelter, then re-attempt the disable. Tenant-scoped: caller's "
                    + "JWT-bound tenant must equal the path tenant. Spec capability: "
                    + "dv-policy-tenant-flag."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Flag updated; response confirms tenantId + new value"),
            @ApiResponse(responseCode = "400", description = "Disable rejected because active DV shelters exist (errorCode: tenant.dvPolicy.cannotDisableWhileDvSheltersExist)"),
            @ApiResponse(responseCode = "403", description = "Cross-tenant access or insufficient role")
    })
    @PatchMapping("/{tenantId}/dv-policy")
    @PreAuthorize("hasRole('COC_ADMIN')")
    public ResponseEntity<Map<String, Object>> updateDvPolicy(
            @Parameter(description = "UUID of the tenant whose DV-policy flag to update")
            @PathVariable UUID tenantId,
            @Valid @RequestBody DvPolicyRequest request,
            Authentication authentication) {

        // STEP 1 — Tenant-scoping guard. Executes BEFORE any inventory query
        // so a probe from Tenant A cannot learn whether Tenant B has DV
        // shelters via timing or via the error message (warroom round 1,
        // Marcus). 403 with no body — no existence-leak.
        UUID callerTenantId = TenantContext.getTenantId();
        if (callerTenantId == null || !callerTenantId.equals(tenantId)) {
            throw new AccessDeniedException(
                    "DV-policy changes are scoped to the caller's tenant");
        }

        // STEP 1b — dvAccess guard (warroom round 2, 2026-05-02 IT discovery).
        // The disable-path count query (STEP 3) reads {@code shelter} via
        // fabt_app + RLS. RLS filters DV shelters out for callers with
        // {@code dvAccess=false}, which would make the count return 0 and
        // wrongly allow a disable while DV shelters actually exist —
        // precisely the failure mode the invariant was added to prevent.
        // Forbid the endpoint to non-dvAccess callers entirely. Matches the
        // warroom rationale that COC_ADMIN owns this flag because they have
        // DV-context awareness platform operators don't — dvAccess is the
        // formal claim representing that awareness.
        if (!TenantContext.getDvAccess()) {
            throw new AccessDeniedException(
                    "DV-policy management requires dvAccess=true");
        }

        UUID actorUserId = parseUserId(authentication);

        // STEP 2 — Read current value before the write so the audit row can
        // record the pre-change state. Tenant.isDvPolicyEnabled defaults to
        // false on absent key, malformed JSON, or non-boolean value.
        Tenant tenant = tenantService.findById(tenantId).orElseThrow(
                () -> new java.util.NoSuchElementException("Tenant not found: " + tenantId));
        boolean oldValue = Tenant.isDvPolicyEnabled(tenant.getConfig(), objectMapper);
        boolean newValue = request.dvPolicyEnabled();

        // STEP 3 — Disable-path guard: when transitioning true → false,
        // count active DV shelters. If non-zero, the disable is FORBIDDEN
        // (warroom design D4 + operator decision 2026-05-01).
        //
        // Order-of-operations is critical (warroom round 1, Marcus): emit
        // the rejected audit row FIRST, THEN throw. If the throw runs
        // first, the audit is lost.
        //
        // Casey's guardrail: the rejection branch MUST NOT log shelter
        // UUIDs, names, addresses, or any per-shelter identifier — only
        // the integer count.
        if (oldValue && !newValue) {
            long activeDvCount = shelterRepository.countActiveDvSheltersByTenantId(tenantId);
            if (activeDvCount > 0) {
                emitConfigUpdated(actorUserId, oldValue, oldValue, "rejected",
                        ErrorCodes.TENANT_DV_POLICY_CANNOT_DISABLE_WHILE_DV_SHELTERS_EXIST,
                        activeDvCount);
                String message = String.format(
                        "This CoC currently operates %d active Domestic Violence shelter%s. "
                                + "To turn off DV shelter operations, deactivate %s DV shelter first, "
                                + "then return to this setting.",
                        activeDvCount,
                        activeDvCount == 1 ? "" : "s",
                        activeDvCount == 1 ? "the" : "each");
                throw new StructuredErrorException(
                        ErrorCodes.TENANT_DV_POLICY_CANNOT_DISABLE_WHILE_DV_SHELTERS_EXIST,
                        message,
                        Map.of("remaining_dv_shelter_count", activeDvCount));
            }
        }

        // STEP 4 — Apply the write. Idempotent against the existing value
        // (the merge produces the same JSONB output if old == new).
        tenantService.setDvPolicyEnabled(tenantId, newValue);

        // STEP 5 — Emit applied audit. For idempotent re-sets the row still
        // emits with old == new — the audit reader can distinguish "actual
        // change" from "no-op re-confirm" by comparing old_value and
        // new_value in the details payload.
        emitConfigUpdated(actorUserId, oldValue, newValue, "applied", null, null);

        return ResponseEntity.ok(Map.of(
                "tenantId", tenantId.toString(),
                "dvPolicyEnabled", newValue
        ));
    }

    /**
     * Emits {@link AuditEventType#TENANT_CONFIG_UPDATED} with the
     * dv-policy-tenant-flag details shape (warroom design D7):
     * {@code config_key, old_value, new_value, outcome, rejection_code,
     * remaining_dv_shelter_count}. The applied path passes
     * {@code rejection_code = null} and {@code remaining_dv_shelter_count = null}.
     * The rejected path passes both as non-null.
     */
    private void emitConfigUpdated(UUID actorUserId, boolean oldValue, boolean newValue,
                                   String outcome, String rejectionCode, Long remainingDvShelterCount) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("config_key", "dv_policy_enabled");
        details.put("old_value", oldValue);
        details.put("new_value", newValue);
        details.put("outcome", outcome);
        details.put("rejection_code", rejectionCode);
        details.put("remaining_dv_shelter_count", remainingDvShelterCount);
        eventPublisher.publishEvent(new AuditEventRecord(
                actorUserId, null, AuditEventType.TENANT_CONFIG_UPDATED, details, null));
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
