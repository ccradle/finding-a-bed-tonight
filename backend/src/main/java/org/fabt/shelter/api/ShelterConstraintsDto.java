package org.fabt.shelter.api;

public record ShelterConstraintsDto(
        boolean sobrietyRequired,
        boolean idRequired,
        boolean referralRequired,
        boolean petsAllowed,
        boolean wheelchairAccessible,
        String curfewTime,
        Integer maxStayDays,
        String[] populationTypesServed
) {
}
