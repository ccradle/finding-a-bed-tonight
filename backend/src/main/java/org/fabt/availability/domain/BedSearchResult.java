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
        boolean dvShelter
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
