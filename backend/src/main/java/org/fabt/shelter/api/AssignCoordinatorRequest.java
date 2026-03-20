package org.fabt.shelter.api;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record AssignCoordinatorRequest(
        @NotNull(message = "userId is required") UUID userId
) {
}
