package org.fabt.referral.api;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO for the CoC Admin escalated referral queue (T-13).
 * Contains ZERO client PII (no callback_number, no client_name).
 *
 * <p>Marcus Webb (Security): "The DTO is the contract. Verify zero PII before merge."</p>
 *
 * <p>{@code escalationChainBroken} (war room round 5, Marcus Okafor): TRUE
 * when an admin previously reassigned this referral to a SPECIFIC_USER and
 * the system has stopped auto-escalating it. The Session 5 queue UI uses
 * this to render an "Owned by: &lt;name&gt;" badge so other admins know not
 * to reassign or claim it again. The named owner is recoverable by the
 * frontend via the audit trail; the DTO surfaces only the boolean.</p>
 */
public record EscalatedReferralDto(
    UUID id,
    UUID shelterId,
    String shelterName,
    String populationType,
    int householdSize,
    String urgency,
    Instant createdAt,
    Instant expiresAt,
    long remainingMinutes,
    UUID assignedCoordinatorId,
    String assignedCoordinatorName,
    UUID claimedByAdminId,
    String claimedByAdminName,
    Instant claimExpiresAt,
    boolean escalationChainBroken
) {}
