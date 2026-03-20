package org.fabt.shelter.api;

import java.time.Instant;

import org.fabt.shelter.domain.Shelter;

public record ShelterListResponse(
        ShelterResponse shelter,
        AvailabilitySummary availabilitySummary
) {
    public record AvailabilitySummary(
            Integer totalBedsAvailable,
            int populationTypesServed,
            Instant lastUpdated,
            Long dataAgeSeconds,
            String dataFreshness
    ) {}

    public static ShelterListResponse from(Shelter shelter, AvailabilitySummary summary) {
        return new ShelterListResponse(ShelterResponse.from(shelter), summary);
    }
}
