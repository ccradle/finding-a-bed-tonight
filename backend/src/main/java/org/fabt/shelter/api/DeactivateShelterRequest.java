package org.fabt.shelter.api;

public record DeactivateShelterRequest(
        String reason,
        boolean confirmDv
) {
}
