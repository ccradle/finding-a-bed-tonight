package org.fabt.subscription.api;

import java.time.Instant;
import java.util.UUID;

import org.fabt.subscription.domain.Subscription;

public record SubscriptionResponse(
        UUID id,
        String eventType,
        String status,
        String callbackUrl,
        Instant expiresAt,
        String lastError,
        Instant createdAt
) {

    public static SubscriptionResponse from(Subscription subscription) {
        return new SubscriptionResponse(
                subscription.getId(),
                subscription.getEventType(),
                subscription.getStatus(),
                subscription.getCallbackUrl(),
                subscription.getExpiresAt(),
                subscription.getLastError(),
                subscription.getCreatedAt()
        );
    }
}
