package org.fabt.referral.api;

import java.time.Instant;
import java.util.UUID;

import org.fabt.referral.domain.ReferralToken;

/**
 * Response DTO for DV referral tokens.
 * For ACCEPTED tokens, includes shelter phone (warm handoff) but NEVER shelter address.
 */
public record ReferralTokenResponse(
        UUID id,
        UUID shelterId,
        int householdSize,
        String populationType,
        String urgency,
        String specialNeeds,
        String callbackNumber,
        String status,
        Instant createdAt,
        Instant respondedAt,
        Instant expiresAt,
        Long remainingSeconds,
        String rejectionReason,
        String shelterPhone
) {
    public static ReferralTokenResponse from(ReferralToken token, String shelterPhone) {
        Long remaining = null;
        if ("PENDING".equals(token.getStatus()) && token.getExpiresAt() != null) {
            long secs = java.time.Duration.between(Instant.now(), token.getExpiresAt()).getSeconds();
            remaining = Math.max(0, secs);
        }
        return new ReferralTokenResponse(
                token.getId(),
                token.getShelterId(),
                token.getHouseholdSize(),
                token.getPopulationType(),
                token.getUrgency(),
                token.getSpecialNeeds(),
                token.getCallbackNumber(),
                token.getStatus(),
                token.getCreatedAt(),
                token.getRespondedAt(),
                token.getExpiresAt(),
                remaining,
                token.getRejectionReason(),
                shelterPhone
        );
    }
}
