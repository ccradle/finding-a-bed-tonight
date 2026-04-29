package org.fabt.shelter.api;

import org.fabt.shared.config.JsonString;

public record ShelterConstraintsDto(
        boolean sobrietyRequired,
        boolean idRequired,
        boolean referralRequired,
        boolean petsAllowed,
        boolean wheelchairAccessible,
        String curfewTime,
        Integer maxStayDays,
        String[] populationTypesServed,
        // transitional-reentry-support task 4.3 — slice 2B addition. Optional;
        // null means "not provided," entity stays null. Persisted to V92's
        // shelter_constraints.eligibility_criteria JSONB column.
        JsonString eligibilityCriteria
) {
}
