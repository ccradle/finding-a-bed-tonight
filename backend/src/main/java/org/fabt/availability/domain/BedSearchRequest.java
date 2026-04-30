package org.fabt.availability.domain;

import java.util.List;

public record BedSearchRequest(
        String populationType,
        ConstraintFilters constraints,
        LocationFilter location,
        Integer limit,
        // transitional-reentry-support task 4.1 (slice 2B). All optional /
        // null = no filter. Three new shelter-level filters:
        //
        //   shelterTypes — restrict to a set of `shelter_type` enum values
        //                  (e.g. ["TRANSITIONAL", "REENTRY_TRANSITIONAL"]).
        //                  null/empty = no restriction.
        //   county       — case-insensitive county exact match. null = any.
        //   acceptsFelonies — when Boolean.TRUE, apply the three-way logic
        //                     (design D1, warroom H1) over each shelter's
        //                     eligibility_criteria + requires_verification_call:
        //                       (a) explicit false → EXCLUDE
        //                       (b) explicit true  → INCLUDE
        //                       (c) any-null path  → INCLUDE if
        //                           requires_verification_call=true, else EXCLUDE
        //                     null = no filter.
        List<String> shelterTypes,
        String county,
        Boolean acceptsFelonies
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
