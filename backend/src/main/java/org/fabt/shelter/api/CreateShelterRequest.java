package org.fabt.shelter.api;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import org.fabt.shelter.domain.ShelterType;

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
        Boolean requiresVerificationCall,
        // transitional-reentry-support task 5.4 (slice 2D warroom H2).
        // Optional. When null + dvShelter=false → entity default EMERGENCY.
        // When dvShelter=true → forced to DV in the service (V91 lockstep).
        // When non-null + dvShelter=false → caller's choice from the
        // controlled ShelterType vocabulary. Service rejects an explicit
        // shelterType=DV combined with dvShelter=false (V91 CHECK would
        // reject anyway; service catches it earlier with a clearer error).
        ShelterType shelterType
) {
}
