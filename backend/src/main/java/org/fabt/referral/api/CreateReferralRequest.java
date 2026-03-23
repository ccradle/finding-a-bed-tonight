package org.fabt.referral.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for creating a DV referral token.
 * Contains zero client PII — only operational data for the referral.
 */
public record CreateReferralRequest(
        @NotNull UUID shelterId,
        @Min(1) @Max(20) int householdSize,
        @NotBlank String populationType,
        @NotBlank @Pattern(regexp = "STANDARD|URGENT|EMERGENCY") String urgency,
        @Size(max = 500) String specialNeeds,
        @NotBlank @Size(max = 50) String callbackNumber
) {}
