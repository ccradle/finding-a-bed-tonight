package org.fabt.referral.api;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/dv-referrals/{id}/reassign}.
 *
 * <p>Three target types per the coc-admin-escalation spec (D5):</p>
 * <ul>
 *   <li>{@code COORDINATOR_GROUP} — re-fires the T+1h reminder to the
 *       shelter's assigned coordinators. Escalation continues normally
 *       afterward.</li>
 *   <li>{@code COC_ADMIN_GROUP} — re-fires a CRITICAL notification to all
 *       active CoC admins in the tenant. Escalation continues normally.</li>
 *   <li>{@code SPECIFIC_USER} — notifies a single user identified by
 *       {@code targetUserId}. <b>Breaks the escalation chain</b>: the system
 *       sets {@code escalation_chain_broken=true} on the referral and the
 *       batch tasklet stops auto-escalating it. The named user is now the
 *       single thread of accountability.</li>
 * </ul>
 *
 * <p>{@code targetUserId} is REQUIRED for {@code SPECIFIC_USER} and IGNORED
 * for the two group types — the service validates this rather than relying
 * on the field being null in two cases out of three.</p>
 *
 * <p>{@code reason} is required and is recorded verbatim in the audit
 * details. Marcus Webb's PII discipline applies: the admin is responsible
 * for keeping it free of client-identifying information; the frontend
 * Reassign modal displays a prominent warning above the field.</p>
 */
public record ReassignReferralRequest(
        @NotNull TargetType targetType,
        UUID targetUserId,
        @NotBlank @Size(max = 500) String reason
) {

    public enum TargetType {
        COORDINATOR_GROUP,
        COC_ADMIN_GROUP,
        SPECIFIC_USER
    }
}
