package org.fabt.subscription.api;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;

public record CreateSubscriptionRequest(
        @NotBlank String eventType,
        Map<String, Object> filter,
        @NotBlank String callbackUrl,
        @NotBlank String callbackSecret
) {
}
