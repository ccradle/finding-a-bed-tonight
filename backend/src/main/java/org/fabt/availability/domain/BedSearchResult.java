package org.fabt.availability.domain;

import java.util.List;
import java.util.UUID;

public record BedSearchResult(
        UUID shelterId,
        String shelterName,
        String address,
        String phone,
        Double latitude,
        Double longitude,
        List<PopulationAvailability> availability,
        Long dataAgeSeconds,
        String dataFreshness,
        Double distanceMiles,
        ConstraintsSummary constraints,
        boolean surgeActive,
        boolean dvShelter,
        // transitional-reentry-support task 5.2 / verify-round-2 S3.
        // Surfaces the shelter_type taxonomy value in search responses so the
        // shelter-type-taxonomy "Filter by TRANSITIONAL returns shelters
        // where shelterType field equals TRANSITIONAL in the response"
        // scenario is satisfiable. Other 5.2 fields (county,
        // eligibilityCriteria, requiresVerificationCall, derived
        // acceptsFelonies) ride alongside slice 4 frontend search-results
        // work; shelterType lands now because the spec scenario explicitly
        // references it on the response shape.
        String shelterType
) {
    public record PopulationAvailability(
            String populationType,
            int bedsTotal,
            int bedsOccupied,
            int bedsOnHold,
            int bedsAvailable,
            boolean acceptingNewGuests,
            int overflowBeds
    ) {}

    public record ConstraintsSummary(
            boolean petsAllowed,
            boolean wheelchairAccessible,
            boolean sobrietyRequired,
            boolean idRequired,
            boolean referralRequired
    ) {
        public int barrierCount() {
            int count = 0;
            if (sobrietyRequired) count++;
            if (idRequired) count++;
            if (referralRequired) count++;
            return count;
        }
    }
}
