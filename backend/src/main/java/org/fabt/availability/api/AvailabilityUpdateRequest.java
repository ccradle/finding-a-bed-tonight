package org.fabt.availability.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Coordinator-supplied availability update request.
 *
 * <p><b>{@code bedsOnHold} is deprecated since v0.34.0 (Issue #102 RCA).</b> The field
 * is accepted for backward compatibility but any supplied value is ignored — the
 * snapshot is written with the actual count of {@code HELD} reservations from the
 * source-of-truth {@code reservation} table. Non-null, non-zero values trigger a
 * WARN log so legacy clients can be identified and migrated. Hard rejection
 * (HTTP 400) is planned for v0.35.0. To create an "offline hold" (e.g., a phone
 * reservation), use {@code POST /api/v1/shelters/{id}/manual-hold} instead.</p>
 */
public record AvailabilityUpdateRequest(
        @NotBlank String populationType,
        @NotNull @Min(0) Integer bedsTotal,
        @NotNull @Min(0) Integer bedsOccupied,
        @Min(0) Integer bedsOnHold,
        @NotNull Boolean acceptingNewGuests,
        @Size(max = 500) String notes,
        @Min(0) Integer overflowBeds
) {
    public int overflowBedsOrDefault() {
        return overflowBeds != null ? overflowBeds : 0;
    }
}
