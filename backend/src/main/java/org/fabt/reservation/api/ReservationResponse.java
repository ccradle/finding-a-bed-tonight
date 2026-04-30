package org.fabt.reservation.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.fabt.reservation.domain.Reservation;
import org.fabt.shared.web.TenantContext;

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
 *
 * <p>The {@code heldForClientName}, {@code heldForClientDob}, and
 * {@code holdNotes} fields are the slice-2C third-party-hold attribution
 * surface (transitional-reentry-support task 5.5, slice 2D warroom H1
 * fix). They are returned plaintext after the
 * {@link org.fabt.reservation.repository.ReservationRepository} row mapper
 * decrypts the {@code _encrypted} columns. Per design D4 the 24h post-
 * resolution purge nulls the underlying ciphertext columns; reads of
 * resolved-and-aged reservations will therefore see these as null.</p>
 *
 * <p>DV-shelter holds: the values flow through the same response — DV
 * gating is on the shelter (RLS), not on whether attribution PII is
 * shown to the holding user. The user who created the hold is the right
 * audience for the attribution they themselves entered.</p>
 *
 * <p>Round 5 §16.B — API serialization gate. The {@code heldForClientName},
 * {@code heldForClientDob}, and {@code holdNotes} fields are only populated
 * when the request scope has {@code features.reentryMode = true} (read from
 * {@link TenantContext#getReentryMode()}, which is bound from the JWT claim
 * by {@code JwtAuthenticationFilter}). When the flag is unset (e.g., demo
 * tenants without reentry, batch jobs, system contexts), the three fields
 * serialize as {@code null} regardless of the underlying ciphertext. This
 * is defense-in-depth: even if a frontend gating bug surfaces these fields
 * in the UI, the API will not return their values.</p>
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
        String notes,
        String heldForClientName,
        LocalDate heldForClientDob,
        String holdNotes
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
        boolean reentry = TenantContext.getReentryMode();
        String clientName = reentry ? r.getHeldForClientName() : null;
        LocalDate clientDob = reentry ? r.getHeldForClientDob() : null;
        String hNotes = reentry ? r.getHoldNotes() : null;
        return new ReservationResponse(
                r.getId(), r.getShelterId(), shelterName, shelterPhone,
                r.getPopulationType(), r.getStatus().name(),
                r.getExpiresAt(), r.remainingSeconds(),
                r.getCreatedAt(), r.getConfirmedAt(), r.getCancelledAt(), r.getNotes(),
                clientName, clientDob, hNotes
        );
    }
}
