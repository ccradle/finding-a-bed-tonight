package org.fabt.reservation.api;

import java.time.Instant;
import java.util.UUID;

import org.fabt.reservation.domain.Reservation;

/**
 * Response DTO for a reservation.
 *
 * <p>The {@code shelterName} and {@code shelterPhone} fields are optional
 * enrichments populated by list endpoints that do a batch shelter lookup
 * (Phase 3 of notification-deep-linking: the My Past Holds view needs
 * display names, and task 6.4's tel: link needs the phone number). They
 * are null for single-reservation endpoints ({@code POST /reservations},
 * {@code PATCH /{id}/confirm}, etc.) that don't perform the join — the
 * single-reservation callers can fetch shelter details separately if they
 * need them.</p>
 */
public record ReservationResponse(
        UUID id,
        UUID shelterId,
        String shelterName,
        String shelterPhone,
        String populationType,
        String status,
        Instant expiresAt,
        long remainingSeconds,
        Instant createdAt,
        Instant confirmedAt,
        Instant cancelledAt,
        String notes
) {
    /**
     * Build a response without shelter enrichment. shelterName and
     * shelterPhone are null. Used by single-reservation endpoints.
     */
    public static ReservationResponse from(Reservation r) {
        return from(r, null, null);
    }

    /**
     * Build a response with shelter name + phone from a batch lookup.
     * Used by list endpoints that enrich after fetching reservations.
     */
    public static ReservationResponse from(Reservation r, String shelterName, String shelterPhone) {
        return new ReservationResponse(
                r.getId(), r.getShelterId(), shelterName, shelterPhone,
                r.getPopulationType(), r.getStatus().name(),
                r.getExpiresAt(), r.remainingSeconds(),
                r.getCreatedAt(), r.getConfirmedAt(), r.getCancelledAt(), r.getNotes()
        );
    }
}
