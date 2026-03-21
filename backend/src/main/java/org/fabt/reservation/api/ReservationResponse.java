package org.fabt.reservation.api;

import java.time.Instant;
import java.util.UUID;

import org.fabt.reservation.domain.Reservation;

public record ReservationResponse(
        UUID id,
        UUID shelterId,
        String populationType,
        String status,
        Instant expiresAt,
        long remainingSeconds,
        Instant createdAt,
        Instant confirmedAt,
        Instant cancelledAt,
        String notes
) {
    public static ReservationResponse from(Reservation r) {
        return new ReservationResponse(
                r.getId(), r.getShelterId(), r.getPopulationType(),
                r.getStatus().name(), r.getExpiresAt(), r.remainingSeconds(),
                r.getCreatedAt(), r.getConfirmedAt(), r.getCancelledAt(), r.getNotes()
        );
    }
}
