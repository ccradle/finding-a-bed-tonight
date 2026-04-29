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
        List<ShelterCapacityDto> capacities,
        // transitional-reentry-support task 4.3 (slice 2B). Optional —
        // nullable means "not provided" / entity defaults. Note:
        // eligibilityCriteria lives inside `constraints` (it persists on
        // shelter_constraints.eligibility_criteria per V92).
        String county,
        Boolean requiresVerificationCall
) {
}
