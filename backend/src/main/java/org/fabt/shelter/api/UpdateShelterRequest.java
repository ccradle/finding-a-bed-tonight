package org.fabt.shelter.api;

import java.util.List;

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
        Boolean requiresVerificationCall
) {
}
