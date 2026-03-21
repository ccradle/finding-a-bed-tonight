package org.fabt.availability.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AvailabilityUpdateRequest(
        @NotBlank String populationType,
        @NotNull @Min(0) Integer bedsTotal,
        @NotNull @Min(0) Integer bedsOccupied,
        @Min(0) Integer bedsOnHold,
        @NotNull Boolean acceptingNewGuests,
        @Size(max = 500) String notes,
        @Min(0) Integer overflowBeds
) {
    public int bedsOnHoldOrDefault() {
        return bedsOnHold != null ? bedsOnHold : 0;
    }

    public int overflowBedsOrDefault() {
        return overflowBeds != null ? overflowBeds : 0;
    }
}
