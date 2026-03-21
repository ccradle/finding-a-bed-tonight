package org.fabt.reservation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateReservationRequest(
        @NotNull UUID shelterId,
        @NotBlank String populationType,
        @Size(max = 500) String notes
) {
}
