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
        // scenario is satisfiable.
        String shelterType,
        // transitional-reentry-support task 5.2 (slice 4 prereq, warroom H2).
        // `county` powers the §9.2 expanded-card jurisdictional display
        // (supervision navigators need to confirm the shelter's county
        // matches their client's supervision district).
        // `requiresVerificationCall` powers the §10.6 "call to verify"
        // badge that surfaces null-eligibility shelters in the
        // acceptsFelonies branch (c). The full `eligibilityCriteria`
        // JSONB is heavier and rides §10.7 — adding it later is purely
        // additive on this record.
        String county,
        boolean requiresVerificationCall
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
