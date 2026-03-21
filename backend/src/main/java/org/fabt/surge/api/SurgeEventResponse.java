package org.fabt.surge.api;

import java.time.Instant;
import java.util.UUID;

import org.fabt.surge.domain.SurgeEvent;

public record SurgeEventResponse(
        UUID id,
        String status,
        String reason,
        String boundingBox,
        UUID activatedBy,
        Instant activatedAt,
        Instant deactivatedAt,
        UUID deactivatedBy,
        Instant scheduledEnd,
        Instant createdAt
) {
    public static SurgeEventResponse from(SurgeEvent e) {
        return new SurgeEventResponse(
                e.getId(), e.getStatus().name(), e.getReason(), e.getBoundingBox(),
                e.getActivatedBy(), e.getActivatedAt(), e.getDeactivatedAt(),
                e.getDeactivatedBy(), e.getScheduledEnd(), e.getCreatedAt()
        );
    }
}
