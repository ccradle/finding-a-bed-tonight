package org.fabt.availability.api;

import java.util.Arrays;
import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.Operation;
import org.fabt.availability.domain.BedSearchRequest;
import org.fabt.availability.service.BedSearchService;
import org.fabt.availability.service.BedSearchService.BedSearchResponse;
import org.fabt.shelter.domain.ShelterType;
import org.fabt.surge.service.SurgeEventService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/queries")
public class BedSearchController {

    private final BedSearchService bedSearchService;
    private final SurgeEventService surgeEventService;

    public BedSearchController(BedSearchService bedSearchService, SurgeEventService surgeEventService) {
        this.bedSearchService = bedSearchService;
        this.surgeEventService = surgeEventService;
    }

    @Operation(
            summary = "Search for available shelter beds with constraint filters",
            description = "Searches for shelters with real-time bed availability data, ranked to surface " +
                    "the most actionable placements first. Accepts optional filters: populationType " +
                    "(e.g., SINGLE_ADULT, FAMILY_WITH_CHILDREN), constraint filters (petsAllowed, " +
                    "wheelchairAccessible, sobrietyRequired, idRequired, referralRequired), location " +
                    "(latitude, longitude, radiusMiles — accepted for schema compatibility but distance " +
                    "ranking is deferred until PostGIS geo-search change), and limit (default 20). " +
                    "Amenity filters (petsAllowed, wheelchairAccessible): set to true to require the " +
                    "amenity. Barrier filters (sobrietyRequired, idRequired, referralRequired): set to " +
                    "false to exclude shelters with that barrier. Omit any filter for no preference. " +
                    "Results are ranked: (1) shelters with " +
                    "beds_available > 0 appear first, (2) shelters with fewer barriers rank higher, " +
                    "(3) among equal barrier levels, more beds_available ranks higher. Each result " +
                    "includes beds_available (derived: beds_total - beds_occupied - beds_on_hold), " +
                    "data_age_seconds (seconds since last availability snapshot), and data_freshness " +
                    "(FRESH < 2h, AGING 2-8h, STALE > 8h, UNKNOWN if no snapshot). DV shelters are " +
                    "excluded via row-level security for users without dvAccess. " +
                    "Requires any authenticated role."
    )
    @PostMapping("/beds")
    public ResponseEntity<BedSearchResponse> searchBeds(@RequestBody(required = false) BedSearchRequest request) {
        if (request == null) {
            request = new BedSearchRequest(null, null, null, null, null, null, null);
        }
        // Validate shelterTypes against the ShelterType enum at the controller
        // boundary (slice 2D verify W2). Unknown values previously matched
        // nothing and silently returned an empty result — confusing UX. The
        // shelter-type-taxonomy "Invalid shelter type value returns 400"
        // spec scenario expects a 400 with a list of valid values; we surface
        // it as IllegalArgumentException → GlobalExceptionHandler 400.
        validateShelterTypes(request.shelterTypes());

        boolean surgeActive = surgeEventService.getActive().isPresent();
        BedSearchResponse response = bedSearchService.search(request, surgeActive);
        return ResponseEntity.ok(response);
    }

    private static void validateShelterTypes(java.util.List<String> shelterTypes) {
        if (shelterTypes == null || shelterTypes.isEmpty()) return;
        for (String s : shelterTypes) {
            if (s == null || s.isBlank()) continue;
            try {
                ShelterType.valueOf(s);
            } catch (IllegalArgumentException e) {
                String valid = Arrays.stream(ShelterType.values())
                        .map(Enum::name)
                        .collect(Collectors.joining(", "));
                throw new IllegalArgumentException(
                        "Invalid shelterType '" + s + "'; valid values: " + valid);
            }
        }
    }
}
