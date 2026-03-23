package org.fabt.referral.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectReferralRequest(
        @NotBlank @Size(max = 500) String reason
) {}
