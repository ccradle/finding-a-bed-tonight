package org.fabt.availability.domain;

public record BedSearchRequest(
        String populationType,
        ConstraintFilters constraints,
        LocationFilter location,
        Integer limit
) {
    public record ConstraintFilters(
            Boolean petsAllowed,
            Boolean wheelchairAccessible,
            Boolean sobrietyRequired,
            Boolean idRequired,
            Boolean referralRequired
    ) {}

    public record LocationFilter(
            Double latitude,
            Double longitude,
            Double radiusMiles
    ) {}

    public int limitOrDefault() {
        return limit != null && limit > 0 ? limit : 20;
    }
}
