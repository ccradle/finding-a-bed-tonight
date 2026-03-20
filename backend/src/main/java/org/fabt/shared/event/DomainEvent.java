package org.fabt.shared.event;

import java.time.Instant;
import java.util.UUID;

public record DomainEvent(
        UUID id,
        String type,
        UUID tenantId,
        Object payload,
        Instant timestamp
) {
    public DomainEvent(String type, UUID tenantId, Object payload) {
        this(UUID.randomUUID(), type, tenantId, payload, Instant.now());
    }
}
