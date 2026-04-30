package org.fabt.shelter.api;

import java.util.List;

import org.fabt.shelter.domain.ShelterType;

public record UpdateShelterRequest(
        String name,
        String addressStreet,
        String addressCity,
        String addressState,
        String addressZip,
        String phone,
        Double latitude,
        Double longitude,
        Boolean dvShelter,
        ShelterConstraintsDto constraints,
        List<ShelterCapacityDto> capacities,
        // transitional-reentry-support task 4.3 (slice 2B). PATCH semantics:
        // null means "leave existing value unchanged." `eligibilityCriteria`
        // lives inside `constraints` (it persists on
        // shelter_constraints.eligibility_criteria per V92).
        String county,
        Boolean requiresVerificationCall,
        // transitional-reentry-support task 5.4 (slice 2D warroom H2).
        // PATCH semantics: null = leave unchanged. dvShelter dominates per
        // V91 lockstep — if dvShelter is updated to true in the same PATCH,
        // the service forces shelterType=DV regardless of this field.
        ShelterType shelterType
) {
}
