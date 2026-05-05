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
 * Admin endpoint for the per-tenant contact-email override
 * (info-email-contact OpenSpec change, task 3.3).
 *
 * <p>The JSONB key {@code tenant.config.contact.email} provides a per-tenant
 * override of the platform-default contact inbox surfaced via
 * {@code GET /api/v1/public/contact-info}. An empty / absent value signals
 * "inherit the platform default" — operators ALWAYS retain the ability to
 * revert to platform inheritance by PATCH'ing an empty string.
 *
 * <p><b>Why a new controller, not a method on
 * {@link org.fabt.tenant.api.TenantController}:</b> mirrors the rationale
 * recorded on {@code ReservationConfigController} and {@code DvPolicyController}.
 * TenantController is platform-operator-grade for most write endpoints; this
 * is COC_ADMIN scope. Keeping the COC_ADMIN auth boundary visible in the URL
 * surface makes the role separation legible.
 *
 * <p><b>Role gate ({@code @PreAuthorize}):</b> the SecurityConfig has no
 * specific {@code /api/v1/admin/**} URL rule — the catch-all at the end of
 * the matcher chain is {@code .anyRequest().authenticated()}, which only
 * requires authentication, not COC_ADMIN. The role gate therefore MUST live
 * at the method level. This corrects info-email-contact tasks.md §3.3's
 * instruction to "Do NOT annotate with @PreAuthorize" — that instruction
 * was based on a misread of the SecurityConfig surface, and following it
 * would create a privilege-escalation gap (any authenticated role —
 * COORDINATOR, OUTREACH_WORKER — would reach the method body).
 *
 * <p><b>Audit:</b> emits {@link AuditEventType#TENANT_CONFIG_UPDATED} on
 * every successful write AND on every rejected DV-policy attempt AND on
 * every cross-tenant probe. The details payload carries
 * {@code config_key, old_value, new_value, value_changed, outcome,
 * rejection_code} — same shape as {@code DvPolicyController.emitConfigUpdated},
 * minus DV-policy's {@code remaining_dv_shelter_count} (not relevant here).
 *
 * <p><b>Order-of-operations on rejected DV-policy attempt:</b> the
 * audit row MUST be emitted BEFORE the rejection exception is thrown
 * (mirrors {@code DvPolicyController}). If the throw runs first, the
 * audit is lost.
 *
 * <p><b>Tenant scoping:</b> the path's {@code tenantId} MUST equal the
 * caller's JWT-bound {@code TenantContext.getTenantId()}. The check
 * executes BEFORE the DV-policy lookup so a probe from Tenant A cannot
 * learn Tenant B's DV-policy state via timing or via the response body.
 * A 403 from cross-tenant access carries no body (no existence-leak).
 * A defense-in-depth audit row is emitted on the attempt.
 */
@RestController
@RequestMapping("/api/v1/admin/tenants")
public class ContactEmailController {

    private final TenantService tenantService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public ContactEmailController(TenantService tenantService,
                                  ApplicationEventPublisher eventPublisher,
                                  ObjectMapper objectMapper) {
        this.tenantService = tenantService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Operation(
            summary = "Update tenant contact-email override (COC_ADMIN)",
            description = "Sets the contact.email JSONB key on tenant.config. "
                    + "Empty string clears the per-tenant override and reverts "
                    + "to platform-default inheritance — clearing is always "
                    + "allowed even when the tenant DV-policy flag is true. "
                    + "Non-empty PATCH is FORBIDDEN when "
                    + "tenant.config.dv_policy_enabled = true (errorCode: "
                    + "tenant.contactEmail.dvPolicyForbidden) — DV-flagged "
                    + "tenants MUST stay on the platform-default inbox to "
                    + "avoid advertising a non-DV-safe address. Tenant-scoped: "
                    + "caller's JWT-bound tenant must equal the path tenant; "
                    + "cross-tenant access returns 403 with no body and a "
                    + "defense-in-depth audit row."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Email updated or cleared; response confirms tenantId + new value"),
            @ApiResponse(responseCode = "400", description = "Bean Validation rejection (malformed email or > 254 chars), or DV-policy rejection (errorCode: tenant.contactEmail.dvPolicyForbidden)"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Cross-tenant access or missing COC_ADMIN role")
    })
    @PatchMapping("/{tenantId}/contact-email")
    @PreAuthorize("hasRole('COC_ADMIN')")
    public ResponseEntity<Map<String, Object>> updateContactEmail(
            @Parameter(description = "UUID of the tenant whose contact-email override to update")
            @PathVariable UUID tenantId,
            @Valid @RequestBody ContactEmailRequest request,
            Authentication authentication) {

        UUID actorUserId = parseUserId(authentication);

        // STEP 1 — Tenant-scoping guard. Executes BEFORE the tenant lookup
        // so a probe from Tenant A cannot learn whether Tenant B exists or
        // has the DV-policy flag set via timing. 403 with no body —
        // no existence-leak.
        //
        // Defense-in-depth audit row carries the rejection code so incident
        // response can identify lateral-movement attempts. The audit lands
        // in the CALLER'S tenant audit chain (TenantContext-scoped by the
        // persister) so reading the actor's tenant audit shows the attempt.
        UUID callerTenantId = TenantContext.getTenantId();
        if (callerTenantId == null || !callerTenantId.equals(tenantId)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("config_key", "contact.email");
            details.put("outcome", "rejected");
            details.put("rejection_code", ErrorCodes.TENANT_CROSS_TENANT_ACCESS);
            details.put("actor_tenant_id", callerTenantId != null ? callerTenantId.toString() : null);
            details.put("target_tenant_id", tenantId.toString());
            if (callerTenantId == null) {
                details.put("note", "missing_tenant_context");
            }
            eventPublisher.publishEvent(new AuditEventRecord(
                    actorUserId, null, AuditEventType.TENANT_CONFIG_UPDATED, details, null));
            throw new AccessDeniedException(
                    "Contact-email changes are scoped to the caller's tenant");
        }

        // STEP 2 — Read current value + DV-policy state before the write so
        // the audit row records pre-change state. Tenant.isDvPolicyEnabled
        // defaults to false on absent key, malformed JSON, or non-boolean
        // value (conservative — corrupt config does NOT accidentally allow
        // a non-empty contact email on a DV-flagged tenant... actually the
        // opposite: it would WRONGLY allow it. The conservative posture is
        // that absent / malformed = false = unrestricted. This is a known
        // limitation of the DV-policy mechanism and is acceptable because
        // every tenant's config is operator-managed; corruption is
        // recoverable + auditable.)
        Tenant tenant = tenantService.findById(tenantId).orElseThrow(
                () -> new java.util.NoSuchElementException("Tenant not found: " + tenantId));
        String oldValue = Tenant.readContactEmail(tenant.getConfig(), objectMapper);
        boolean dvPolicyEnabled = Tenant.isDvPolicyEnabled(tenant.getConfig(), objectMapper);

        // Normalize null and blank to empty string (clear). The DTO permits
        // both at validation time; this controller treats them identically.
        String requestedEmail = (request.email() == null) ? "" : request.email().trim();
        boolean clearing = requestedEmail.isEmpty();
        String newValue = clearing ? null : requestedEmail;

        // STEP 3 — DV-policy guard (info-email-contact task 3.3 Q4=B). A
        // non-empty PATCH on a DV-flagged tenant is forbidden; an empty
        // PATCH (clearing) is ALWAYS allowed even on DV-flagged tenants.
        //
        // Order-of-operations: emit audit FIRST, THEN throw. If the throw
        // runs first, the audit is lost.
        if (dvPolicyEnabled && !clearing) {
            emitConfigUpdated(actorUserId, oldValue, oldValue, "rejected",
                    ErrorCodes.TENANT_CONTACT_EMAIL_DV_POLICY_FORBIDDEN);
            throw new StructuredErrorException(
                    ErrorCodes.TENANT_CONTACT_EMAIL_DV_POLICY_FORBIDDEN,
                    "Contact-email override is not allowed while the tenant DV-policy flag is enabled. "
                            + "Submit an empty value to revert to platform-default inheritance.",
                    Map.of("dv_policy_enabled", true));
        }

        // STEP 4 — Apply the write. Idempotent against the existing value.
        tenantService.setContactEmail(tenantId, newValue);

        // STEP 5 — Emit applied audit. value_changed distinguishes real
        // flips from idempotent re-sets in audit-replay tooling.
        emitConfigUpdated(actorUserId, oldValue, newValue, "applied", null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("contactEmail", newValue);
        return ResponseEntity.ok(body);
    }

    /**
     * Emits {@link AuditEventType#TENANT_CONFIG_UPDATED} with the
     * info-email-contact details shape:
     * {@code config_key, old_value, new_value, value_changed, outcome,
     * rejection_code}. Mirrors {@code DvPolicyController.emitConfigUpdated}
     * minus the DV-shelter-count field (not relevant for contact-email).
     */
    private void emitConfigUpdated(UUID actorUserId, String oldValue, String newValue,
                                   String outcome, String rejectionCode) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("config_key", "contact.email");
        details.put("old_value", oldValue);
        details.put("new_value", newValue);
        details.put("value_changed", !java.util.Objects.equals(oldValue, newValue));
        details.put("outcome", outcome);
        details.put("rejection_code", rejectionCode);
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
