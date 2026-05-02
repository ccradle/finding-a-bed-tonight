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
import org.fabt.shelter.service.ShelterService;
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
    private final ShelterService shelterService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * Depends on {@link ShelterService} (not {@link org.fabt.shelter.repository.ShelterRepository}
     * directly) because the modular-monolith ArchitectureTest forbids
     * cross-module repository injection. ShelterService exposes
     * {@code countActiveDvShelters} for this purpose.
     */
    public DvPolicyController(TenantService tenantService,
                              ShelterService shelterService,
                              ApplicationEventPublisher eventPublisher,
                              ObjectMapper objectMapper) {
        this.tenantService = tenantService;
        this.shelterService = shelterService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Operation(
            summary = "Update tenant DV-policy flag (COC_ADMIN, dvAccess=true)",
            description = "Sets the dv_policy_enabled JSONB flag on tenant.config. "
                    + "True authorizes per-shelter dv_shelter=true writes (enforced by "
                    + "ShelterService.create / update / activate). False is forbidden "
                    + "while any active DV shelter exists on the tenant — the operator "
                    + "must first deactivate every active DV shelter, then re-attempt "
                    + "the disable. Tenant-scoped: caller's JWT-bound tenant must equal "
                    + "the path tenant; cross-tenant access returns 403 with no body "
                    + "and no DV-shelter-count leak (a defense-in-depth audit row "
                    + "is emitted on the attempt). Requires dvAccess=true on the JWT "
                    + "(see design D10) so the disable-path inventory query, which "
                    + "reads shelter through RLS, sees DV shelters.\n"
                    + "\nExample request body: `{\"dvPolicyEnabled\": true}`\n"
                    + "Example success response: `{\"tenantId\": \"...\", \"dvPolicyEnabled\": true}`\n"
                    + "Example disable-rejection: `{\"error\": \"bad_request\", \"context\": "
                    + "{\"errorCode\": \"tenant.dvPolicy.cannotDisableWhileDvSheltersExist\", "
                    + "\"remaining_dv_shelter_count\": 2, ...}}`\n"
                    + "\nSpec capability: dv-policy-tenant-flag (OpenSpec change 2026-05). "
                    + "Future ADR: openspec change 16.1 will extract the shared "
                    + "JSONB-config-key endpoint convention."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Flag updated; response confirms tenantId + new value"),
            @ApiResponse(responseCode = "400", description = "Disable rejected because active DV shelters exist (errorCode: tenant.dvPolicy.cannotDisableWhileDvSheltersExist; remaining_dv_shelter_count in context)"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Cross-tenant access, missing COC_ADMIN role, or dvAccess=false")
    })
    @PatchMapping("/{tenantId}/dv-policy")
    @PreAuthorize("hasRole('COC_ADMIN')")
    public ResponseEntity<Map<String, Object>> updateDvPolicy(
            @Parameter(description = "UUID of the tenant whose DV-policy flag to update")
            @PathVariable UUID tenantId,
            @Valid @RequestBody DvPolicyRequest request,
            Authentication authentication) {

        // Parse actor up-front — needed by both the cross-tenant audit
        // (STEP 1) and the rejected-disable audit (STEP 3).
        UUID actorUserIdEarly = parseUserId(authentication);

        // STEP 1 — Tenant-scoping guard. Executes BEFORE any inventory query
        // so a probe from Tenant A cannot learn whether Tenant B has DV
        // shelters via timing or via the error message (warroom round 1,
        // Marcus). 403 with no body — no existence-leak.
        //
        // Defense-in-depth (warroom round 2, §6.3): emit a
        // TENANT_CONFIG_UPDATED audit row with rejection_code
        // tenant.crossTenantAccess BEFORE throwing. The 403 response
        // itself carries no body / no leak; the audit row is the
        // forensic signal that records the lateral-movement attempt
        // for incident response. The audit lands in the CALLER'S
        // tenant audit chain (TenantContext-scoped by the persister)
        // so reading the actor's tenant audit shows the attempt.
        UUID callerTenantId = TenantContext.getTenantId();
        if (callerTenantId == null || !callerTenantId.equals(tenantId)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("config_key", "dv_policy_enabled");
            details.put("outcome", "rejected");
            details.put("rejection_code", ErrorCodes.TENANT_CROSS_TENANT_ACCESS);
            details.put("actor_tenant_id", callerTenantId != null ? callerTenantId.toString() : null);
            details.put("target_tenant_id", tenantId.toString());
            // Warroom round 3 M4: surface the rare null-tenant-context branch
            // explicitly so incident response can distinguish "JWT bypassed
            // tenant context" from "different valid tenant probing this one".
            // Both still 403; the audit row tells them apart.
            if (callerTenantId == null) {
                details.put("note", "missing_tenant_context");
            }
            eventPublisher.publishEvent(new AuditEventRecord(
                    actorUserIdEarly, null, AuditEventType.TENANT_CONFIG_UPDATED, details, null));
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

        // Reuse actorUserIdEarly parsed above (STEP 1 prep).
        UUID actorUserId = actorUserIdEarly;

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
            long activeDvCount = shelterService.countActiveDvShelters(tenantId);
            if (activeDvCount > 0) {
                emitConfigUpdated(actorUserId, oldValue, oldValue, "rejected",
                        ErrorCodes.TENANT_DV_POLICY_CANNOT_DISABLE_WHILE_DV_SHELTERS_EXIST,
                        activeDvCount);
                // Warroom round 3 M1: short structured-only breadcrumb. The
                // user-facing copy lives in the frontend i18n bundle
                // ({@code admin.dvPolicy.disableRejectedWithCount}, ICU
                // plural-aware), keyed off {@code errorCode} +
                // {@code remaining_dv_shelter_count}. This message is what
                // non-frontend clients (cURL, ops debugging) see — keep it
                // short, neutral, and locale-agnostic. Don't duplicate the
                // user-facing English text here so it doesn't drift.
                String message = "Disable rejected: " + activeDvCount
                        + " active DV shelter(s) remain on this tenant.";
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
     * dv-policy-tenant-flag details shape (warroom design D7 + round 3 M3):
     * {@code config_key, old_value, new_value, value_changed, outcome,
     * rejection_code, remaining_dv_shelter_count}. The applied path passes
     * {@code rejection_code = null} and {@code remaining_dv_shelter_count = null}.
     * The rejected path passes both as non-null.
     *
     * <p>{@code value_changed} (warroom round 3 M3) lets audit-replay tooling
     * filter idempotent re-sets without comparing old_value/new_value pairs.
     * On a no-op re-set (old == new) the field is {@code false}; on a real
     * flip the field is {@code true}. On rejected disables it's {@code false}
     * because the value didn't actually change.
     */
    private void emitConfigUpdated(UUID actorUserId, boolean oldValue, boolean newValue,
                                   String outcome, String rejectionCode, Long remainingDvShelterCount) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("config_key", "dv_policy_enabled");
        details.put("old_value", oldValue);
        details.put("new_value", newValue);
        details.put("value_changed", oldValue != newValue);
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
