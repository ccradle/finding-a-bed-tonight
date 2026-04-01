package org.fabt.availability;

import java.util.List;
import java.util.Map;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: DV-authorized outreach worker bed search.
 *
 * Verifies the API contract for OUTREACH_WORKER with dvAccess=true:
 * - Can call the bed search endpoint (authenticated, correct role)
 * - Results include expected response structure
 * - Non-DV shelters have full address
 *
 * NOTE: DV shelter visibility is controlled by PostgreSQL RLS which depends on
 * SET ROLE fabt_app + app.dv_access session variable. In Testcontainers, the DB
 * user may be a superuser so RLS may not apply. DV visibility is verified in
 * DvAccessRlsTest which sets app.dv_access directly at the SQL level.
 * This test verifies the API layer contract, not RLS enforcement.
 */
@DisplayName("DV Outreach Worker — Bed Search API")
class DvOutreachBedSearchTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    private User dvOutreachUser;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupCocAdminUser();
        authHelper.setupCoordinatorUser();

        dvOutreachUser = authHelper.setupUserWithDvAccess(
                "dv-outreach@test.fabt.org", "DV Outreach Worker",
                new String[]{"OUTREACH_WORKER"});
    }

    @Test
    @DisplayName("DV outreach worker can search beds (authenticated, correct role)")
    void dvOutreachWorker_canSearchBeds() {
        HttpHeaders headers = authHelper.headersForUser(dvOutreachUser);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/queries/beds",
                HttpMethod.POST,
                new HttpEntity<>("{}", headers),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("results");
        assertThat(response.getBody()).containsKey("totalCount");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.getBody().get("results");
        assertThat(results).isNotEmpty();
    }

    @Test
    @DisplayName("Non-DV shelters in results have full address")
    void nonDvShelter_returnsFullAddress() {
        HttpHeaders headers = authHelper.headersForUser(dvOutreachUser);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/queries/beds",
                HttpMethod.POST,
                new HttpEntity<>("{}", headers),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.getBody().get("results");
        assertThat(results).isNotEmpty();

        // Find any non-DV shelter in results (may be from this test or shared context)
        Map<String, Object> nonDvResult = results.stream()
                .filter(r -> Boolean.FALSE.equals(r.get("dvShelter")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No non-DV shelter found in results"));

        // Non-DV shelter should have full address
        assertThat(nonDvResult.get("address"))
                .as("Non-DV shelter should have full address")
                .isNotNull();
    }

    @Test
    @DisplayName("Search results include expected response structure")
    void searchResults_haveExpectedStructure() {
        HttpHeaders headers = authHelper.headersForUser(dvOutreachUser);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/queries/beds",
                HttpMethod.POST,
                new HttpEntity<>("{}", headers),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.getBody().get("results");
        assertThat(results).isNotEmpty();

        Map<String, Object> firstResult = results.get(0);
        assertThat(firstResult).containsKey("shelterId");
        assertThat(firstResult).containsKey("shelterName");
        assertThat(firstResult).containsKey("availability");
        assertThat(firstResult).containsKey("dataFreshness");
        assertThat(firstResult).containsKey("dvShelter");
    }
}
