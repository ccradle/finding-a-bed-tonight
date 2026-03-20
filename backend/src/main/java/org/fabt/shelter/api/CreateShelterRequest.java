package org.fabt.shelter.api;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

public record CreateShelterRequest(
        @NotBlank(message = "Name is required") String name,
        String addressStreet,
        String addressCity,
        String addressState,
        String addressZip,
        String phone,
        Double latitude,
        Double longitude,
        boolean dvShelter,
        ShelterConstraintsDto constraints,
        List<ShelterCapacityDto> capacities
) {
}
