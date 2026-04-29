package org.fabt.availability;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.shelter.api.ShelterResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the slice-2 BedSearchService filters
 * (transitional-reentry-support tasks 4.1, 4.2; spec section 13.1–13.4).
 *
 * <p>Slice-2 warroom 17.H1 (2026-04-29) called these out as load-bearing
 * for slice-2 merge: the filter logic was added in slice 2B but had zero
 * dedicated test coverage. Each filter branch has a positive case AND a
 * negative-control sibling so a green run actually proves the filter is
 * doing work, not silently no-op'ing.
 *
 * <p>Each test creates a fresh tenant (random slug suffix to avoid
 * collisions on repeat runs per Riley Cho's note in
 * {@link TestAuthHelper#setupSecondaryTenant}) and seeds five shelters
 * spanning every dimension under test:
 * <ul>
 *   <li>shelter_type: TRANSITIONAL × 2, REENTRY_TRANSITIONAL × 1, EMERGENCY × 2</li>
 *   <li>county: Wake × 3, Durham × 1, Mecklenburg × 1</li>
 *   <li>requires_verification_call: true × 2, false × 3</li>
 *   <li>eligibility_criteria.criminal_record_policy.accepts_felonies:
 *       true × 1, false × 1, null (object missing or whole JSON null) × 3</li>
 * </ul>
 *
 * <p>The shelter_type column is set via direct UPDATE because the slice-2
 * write DTO does not yet expose shelterType (task 5.4 deferred to slice
 * 2D §5 work). The value of this test is the filter behavior — the DTO
 * surface is tested separately once §5.4 lands.
 */
@DisplayName("BedSearchService — slice 2 filters (shelterTypes / county / acceptsFelonies)")
class BedSearchFilterIntegrationTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID shelterTransitWakeAcceptYes;       // (b) explicit accepts=true
    private UUID shelterReentryDurham;               // (a) explicit accepts=false (yet has sentinel=true)
    private UUID shelterTransitMecklenburgSentinel;  // (c) null + sentinel=true
    private UUID shelterEmergencyWakeNoSentinel;     // (c) null + sentinel=false
    private UUID shelterEmergencyWakeJsonNull;       // (c) eligibility_criteria explicitly null

    @BeforeEach
    void setUp() {
        // Unique slug per run avoids cross-test bleeding via the BedSearchService
        // tenant-scoped 60s cache (cache key includes tenant_id).
        String slug = "filter-it-" + UUID.randomUUID().toString().substring(0, 8);
        authHelper.setupTestTenant(slug);
        authHelper.setupCocAdminUser();
        authHelper.setupCoordinatorUser();
        authHelper.setupOutreachWorkerUser();

        shelterTransitWakeAcceptYes = createShelter(
                "Transit Wake AcceptYes",
                "Wake",
                /*requiresVerificationCall*/ false,
                "{\"criminal_record_policy\": {\"accepts_felonies\": true}}");
        forceShelterType(shelterTransitWakeAcceptYes, "TRANSITIONAL");

        shelterReentryDurham = createShelter(
                "Reentry Durham AcceptNo",
                "Durham",
                /*requiresVerificationCall*/ true,  // sentinel TRUE — proves (a) wins over (c)
                "{\"criminal_record_policy\": {\"accepts_felonies\": false}}");
        forceShelterType(shelterReentryDurham, "REENTRY_TRANSITIONAL");

        shelterTransitMecklenburgSentinel = createShelter(
                "Transit Mecklenburg WithSentinel",
                "Mecklenburg",
                /*requiresVerificationCall*/ true,
                /*eligibilityJson*/ null);          // null JSONB → branch (c)
        forceShelterType(shelterTransitMecklenburgSentinel, "TRANSITIONAL");

        shelterEmergencyWakeNoSentinel = createShelter(
                "Emergency Wake NoSentinel",
                "Wake",
                /*requiresVerificationCall*/ false,
                /*eligibilityJson*/ null);

        shelterEmergencyWakeJsonNull = createShelter(
                "Emergency Wake JsonObjectNoPolicy",
                "Wake",
                /*requiresVerificationCall*/ false,
                "{\"intake_hours\": \"9-17\"}");    // policy missing → branch (c) without sentinel

        // Submit availability for every shelter so they pass the "has rows" filter
        // in BedSearchService's groupByShelter step. Without availability, a shelter
        // is included BUT shows empty popAvail; the filter under test is
        // upstream of the population-served check so seeding availability isn't
        // strictly necessary for this filter's behavior — but it makes the
        // assertion failure-mode (`results does not contain shelter X`) easier
        // to reason about because every shelter has a real bed snapshot.
        submitAvailability(shelterTransitWakeAcceptYes);
        submitAvailability(shelterReentryDurham);
        submitAvailability(shelterTransitMecklenburgSentinel);
        submitAvailability(shelterEmergencyWakeNoSentinel);
        submitAvailability(shelterEmergencyWakeJsonNull);
    }

    // ---------------------------------------------------------------------
    // §13.1 — shelterType filter
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§13.1 shelterTypes=[TRANSITIONAL] returns only TRANSITIONAL shelters")
    void shelterTypeFilter_singleType_excludesOthers() {
        Map<String, Object> body = Map.of("shelterTypes", List.of("TRANSITIONAL"));

        List<UUID> ids = searchShelterIds(body);

        assertThat(ids)
                .as("TRANSITIONAL filter should INCLUDE the two TRANSITIONAL shelters")
                .contains(shelterTransitWakeAcceptYes, shelterTransitMecklenburgSentinel);
        assertThat(ids)
                .as("TRANSITIONAL filter should EXCLUDE REENTRY_TRANSITIONAL + EMERGENCY shelters")
                .doesNotContain(shelterReentryDurham,
                                shelterEmergencyWakeNoSentinel,
                                shelterEmergencyWakeJsonNull);
    }

    @Test
    @DisplayName("§13.1 shelterTypes=[TRANSITIONAL,REENTRY_TRANSITIONAL] returns both kinds; excludes EMERGENCY")
    void shelterTypeFilter_multipleTypes_unionMatch() {
        Map<String, Object> body = Map.of(
                "shelterTypes", List.of("TRANSITIONAL", "REENTRY_TRANSITIONAL"));

        List<UUID> ids = searchShelterIds(body);

        assertThat(ids).contains(
                shelterTransitWakeAcceptYes,
                shelterTransitMecklenburgSentinel,
                shelterReentryDurham);
        assertThat(ids).doesNotContain(
                shelterEmergencyWakeNoSentinel,
                shelterEmergencyWakeJsonNull);
    }

    @Test
    @DisplayName("§13.1 no shelterTypes filter returns ALL shelter types (negative control)")
    void noShelterTypeFilter_returnsAll() {
        // Proves the filter is opt-in. If a future change accidentally filtered
        // out non-EMERGENCY by default, this test catches it.
        List<UUID> ids = searchShelterIds(Map.of());

        assertThat(ids).contains(
                shelterTransitWakeAcceptYes,
                shelterReentryDurham,
                shelterTransitMecklenburgSentinel,
                shelterEmergencyWakeNoSentinel,
                shelterEmergencyWakeJsonNull);
    }

    // ---------------------------------------------------------------------
    // §13.2 — county filter
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§13.2 county=\"Wake\" returns only Wake shelters; excludes Durham + Mecklenburg")
    void countyFilter_exactMatch() {
        List<UUID> ids = searchShelterIds(Map.of("county", "Wake"));

        assertThat(ids).contains(
                shelterTransitWakeAcceptYes,
                shelterEmergencyWakeNoSentinel,
                shelterEmergencyWakeJsonNull);
        assertThat(ids).doesNotContain(shelterReentryDurham,
                                       shelterTransitMecklenburgSentinel);
    }

    @Test
    @DisplayName("§13.2 county filter is case-insensitive (\"WAKE\" returns the same set as \"Wake\")")
    void countyFilter_caseInsensitive() {
        List<UUID> wakeIds = searchShelterIds(Map.of("county", "Wake"));
        List<UUID> upperIds = searchShelterIds(Map.of("county", "WAKE"));
        List<UUID> lowerIds = searchShelterIds(Map.of("county", "wake"));

        assertThat(upperIds).containsExactlyInAnyOrderElementsOf(wakeIds);
        assertThat(lowerIds).containsExactlyInAnyOrderElementsOf(wakeIds);
    }

    @Test
    @DisplayName("§13.2 county=\"Manhattan\" returns no test shelter (negative control)")
    void countyFilter_noMatch_returnsEmpty() {
        List<UUID> ids = searchShelterIds(Map.of("county", "Manhattan"));

        assertThat(ids).doesNotContain(
                shelterTransitWakeAcceptYes,
                shelterReentryDurham,
                shelterTransitMecklenburgSentinel,
                shelterEmergencyWakeNoSentinel,
                shelterEmergencyWakeJsonNull);
    }

    // ---------------------------------------------------------------------
    // §13.3 — three-way acceptsFelonies filter
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§13.3 (a) acceptsFelonies=true EXCLUDES shelter with explicit accepts=false (sentinel does NOT override)")
    void acceptsFeloniesFilter_branchA_excludeExplicitFalse() {
        // shelterReentryDurham has explicit accepts_felonies=false AND sentinel=true.
        // Branch (a) MUST exclude regardless of sentinel — proven by sentinel=true here.
        List<UUID> ids = searchShelterIds(Map.of("acceptsFelonies", true));

        assertThat(ids)
                .as("branch (a): explicit accepts_felonies=false → EXCLUDE even when "
                        + "requires_verification_call=true")
                .doesNotContain(shelterReentryDurham);
    }

    @Test
    @DisplayName("§13.3 (b) acceptsFelonies=true INCLUDES shelter with explicit accepts=true")
    void acceptsFeloniesFilter_branchB_includeExplicitTrue() {
        List<UUID> ids = searchShelterIds(Map.of("acceptsFelonies", true));

        assertThat(ids).contains(shelterTransitWakeAcceptYes);
    }

    @Test
    @DisplayName("§13.3 (c) acceptsFelonies=true INCLUDES null-eligibility shelter when sentinel=true")
    void acceptsFeloniesFilter_branchC_includeNullPlusSentinel() {
        List<UUID> ids = searchShelterIds(Map.of("acceptsFelonies", true));

        assertThat(ids)
                .as("branch (c): null eligibility + requires_verification_call=true → INCLUDE")
                .contains(shelterTransitMecklenburgSentinel);
    }

    @Test
    @DisplayName("§13.3 (c) acceptsFelonies=true EXCLUDES null-eligibility shelter when sentinel=false")
    void acceptsFeloniesFilter_branchC_excludeNullNoSentinel() {
        List<UUID> ids = searchShelterIds(Map.of("acceptsFelonies", true));

        assertThat(ids)
                .as("branch (c): null eligibility + sentinel=false → EXCLUDE")
                .doesNotContain(shelterEmergencyWakeNoSentinel);
    }

    @Test
    @DisplayName("§13.3 (c) acceptsFelonies=true EXCLUDES policy-missing-but-non-null JSON when sentinel=false")
    void acceptsFeloniesFilter_branchC_policyMissing_noSentinel_excludes() {
        // Negative control for the parse-failure-vs-null-policy distinction:
        // valid JSON, but criminal_record_policy key absent → branch (c).
        List<UUID> ids = searchShelterIds(Map.of("acceptsFelonies", true));

        assertThat(ids).doesNotContain(shelterEmergencyWakeJsonNull);
    }

    @Test
    @DisplayName("§13.3 acceptsFelonies omitted (null) → no acceptsFelonies filter applied; all returned")
    void acceptsFeloniesFilter_null_noFilterApplied() {
        // Negative control: confirms acceptsFelonies=null (or omitted) does not
        // accidentally apply the filter. If it did, branch (a)+(c) shelters would
        // be excluded from this baseline call.
        List<UUID> ids = searchShelterIds(Map.of());

        assertThat(ids).contains(
                shelterTransitWakeAcceptYes,           // (b)
                shelterReentryDurham,                   // would-be (a) excluded
                shelterTransitMecklenburgSentinel,      // (c) include
                shelterEmergencyWakeNoSentinel,         // (c) exclude
                shelterEmergencyWakeJsonNull);          // (c) exclude
    }

    // ---------------------------------------------------------------------
    // §13.4 — combined filter: shelter passes acceptsFelonies + has both flags
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("§13.4 acceptsFelonies=true filtered shelter has both accepts_felonies=true AND can be confirmed via DB")
    void acceptsFeloniesFilter_branchB_shelterRetainsBothFlagsInDb() {
        List<UUID> ids = searchShelterIds(Map.of("acceptsFelonies", true));
        assertThat(ids).contains(shelterTransitWakeAcceptYes);

        // Sanity check at the DB layer: prove the included shelter actually has
        // accepts_felonies=true in the underlying JSONB column. This guards
        // against a future false-positive where the search returns the right
        // ID but the underlying row has been mutated (proves the filter is
        // routing on the value we believe it's routing on).
        Boolean acceptsExplicit = jdbcTemplate.queryForObject(
                "SELECT (eligibility_criteria->'criminal_record_policy'->>'accepts_felonies')::boolean "
                        + "FROM shelter_constraints WHERE shelter_id = ?",
                Boolean.class, shelterTransitWakeAcceptYes);
        assertThat(acceptsExplicit)
                .as("included shelter must have explicit accepts_felonies=true in JSONB")
                .isTrue();
    }

    @Test
    @DisplayName("§13.4 acceptsFelonies=true + sentinel-included shelter has requires_verification_call=true in DB")
    void acceptsFeloniesFilter_branchC_sentinelShelterFlaggedInDb() {
        List<UUID> ids = searchShelterIds(Map.of("acceptsFelonies", true));
        assertThat(ids).contains(shelterTransitMecklenburgSentinel);

        Boolean sentinel = jdbcTemplate.queryForObject(
                "SELECT requires_verification_call FROM shelter WHERE id = ?",
                Boolean.class, shelterTransitMecklenburgSentinel);
        assertThat(sentinel)
                .as("sentinel-included shelter must have requires_verification_call=true")
                .isTrue();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<UUID> searchShelterIds(Map<String, Object> filter) {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/queries/beds",
                HttpMethod.POST,
                new HttpEntity<>(filter, authHelper.outreachWorkerHeaders()),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.getBody().get("results");
        return results.stream()
                .map(r -> UUID.fromString((String) r.get("shelterId")))
                .toList();
    }

    private UUID createShelter(String name,
                                String county,
                                boolean requiresVerificationCall,
                                String eligibilityJson) {
        // The Map approach so we can stick a JsonString as the eligibility_criteria
        // value without dealing with @JsonValue serialization quirks.
        Map<String, Object> constraints = new java.util.HashMap<>();
        constraints.put("sobrietyRequired", false);
        constraints.put("idRequired", false);
        constraints.put("referralRequired", false);
        constraints.put("petsAllowed", false);
        constraints.put("wheelchairAccessible", true);
        constraints.put("populationTypesServed", new String[]{"SINGLE_ADULT"});
        if (eligibilityJson != null) {
            constraints.put("eligibilityCriteria", eligibilityJson);
        }

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", name);
        body.put("addressStreet", "1 Test St");
        body.put("addressCity", "TestCity");
        body.put("addressState", "NC");
        body.put("addressZip", "27000");
        body.put("phone", "919-555-0000");
        body.put("dvShelter", false);
        body.put("constraints", constraints);
        body.put("capacities", List.of(Map.of("populationType", "SINGLE_ADULT", "bedsTotal", 10)));
        body.put("county", county);
        body.put("requiresVerificationCall", requiresVerificationCall);

        ResponseEntity<ShelterResponse> response = restTemplate.exchange(
                "/api/v1/shelters",
                HttpMethod.POST,
                new HttpEntity<>(body, authHelper.cocAdminHeaders()),
                ShelterResponse.class);
        assertThat(response.getStatusCode())
                .as("create shelter '%s' must succeed", name)
                .isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private void forceShelterType(UUID shelterId, String shelterType) {
        // The slice-2 write DTO does not yet expose shelterType (slice 2D §5.4 work).
        // For test seeding we set the column directly. The V91
        // shelter_dv_implies_dv_type CHECK constraint is satisfied because
        // dvShelter=false here and shelterType ∈ {EMERGENCY, TRANSITIONAL,
        // REENTRY_TRANSITIONAL, ...} ≠ DV.
        int updated = jdbcTemplate.update(
                "UPDATE shelter SET shelter_type = ?::varchar WHERE id = ?",
                shelterType, shelterId);
        assertThat(updated)
                .as("shelter_type direct UPDATE must affect exactly one row")
                .isEqualTo(1);
    }

    private void submitAvailability(UUID shelterId) {
        // Coordinator must be assigned to the shelter to PATCH availability.
        UUID coordinatorId = authHelper.setupCoordinatorUser().getId();
        restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/coordinators",
                HttpMethod.POST,
                new HttpEntity<>("{\"userId\":\"" + coordinatorId + "\"}",
                        authHelper.cocAdminHeaders()),
                Void.class);

        String patchBody = """
                {
                    "populationType": "SINGLE_ADULT",
                    "bedsTotal": 10,
                    "bedsOccupied": 0,
                    "bedsOnHold": 0,
                    "acceptingNewGuests": true
                }
                """;
        restTemplate.exchange(
                "/api/v1/shelters/" + shelterId + "/availability",
                HttpMethod.PATCH,
                new HttpEntity<>(patchBody, authHelper.coordinatorHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }
}
