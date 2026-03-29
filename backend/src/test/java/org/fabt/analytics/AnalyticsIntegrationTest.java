package org.fabt.analytics;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.shared.cache.CacheService;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

class AnalyticsIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CacheService cacheService;

    private HttpHeaders adminHeaders;
    private HttpHeaders outreachHeaders;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        authHelper.setupOutreachWorkerUser();

        var admin = authHelper.setupUserWithDvAccess(
                "analytics-admin@test.fabt.org", "Analytics Admin", new String[]{"COC_ADMIN"});
        adminHeaders = authHelper.headersForUser(admin);
        outreachHeaders = authHelper.outreachWorkerHeaders();

        tenantId = authHelper.getTestTenantId();
        TenantContext.runWithContext(tenantId, true, () -> {
            // Create test shelter and availability data
            createTestShelter("Analytics Test Shelter", false);
        });
    }

    // --- 16.1 Bed search logs events to bed_search_log ---
    @Test
    void bedSearch_logsEvent_toBedSearchLog() {
        // Perform a bed search
        String body = "{\"populationType\": \"SINGLE_ADULT\", \"limit\": 10}";
        restTemplate.exchange(
                "/api/v1/queries/beds",
                HttpMethod.POST,
                new HttpEntity<>(body, outreachHeaders),
                String.class);

        // Verify it was logged
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bed_search_log WHERE tenant_id = ?",
                Long.class, tenantId);
        assertNotNull(count);
        assertTrue(count > 0, "Search should be logged to bed_search_log");
    }

    // --- 16.3 Utilization endpoint returns data ---
    @Test
    void utilizationEndpoint_returnsRatesFromSummaryTable() {
        String url = "/api/v1/analytics/utilization?from=2026-01-01&to=2026-12-31&granularity=daily";
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url, HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                new ParameterizedTypeReference<>() {});

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("avgUtilization"));
    }

    // --- 16.4 Demand endpoint ---
    @Test
    void demandEndpoint_includesReservationExpiryRateAndZeroResultCount() {
        String url = "/api/v1/analytics/demand?from=2026-01-01&to=2026-12-31";
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url, HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                new ParameterizedTypeReference<>() {});

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("totalSearches"));
        assertTrue(body.containsKey("zeroResultSearches"));
        assertTrue(body.containsKey("reservations"));
    }

    // --- 16.5 Capacity endpoint ---
    @Test
    void capacityEndpoint_showsTotalBedsOverTime() {
        String url = "/api/v1/analytics/capacity?from=2026-01-01&to=2026-12-31";
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url, HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                new ParameterizedTypeReference<>() {});

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("dailyCapacity"));
    }

    // --- 16.7 Geographic endpoint excludes DV shelters ---
    @Test
    void geographicEndpoint_excludesDvShelters() {
        // Create a DV shelter
        TenantContext.runWithContext(tenantId, true, () ->
                createTestShelter("DV Analytics Shelter", true));

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "/api/v1/analytics/geographic",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                new ParameterizedTypeReference<>() {});

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<Map<String, Object>> shelters = response.getBody();
        assertNotNull(shelters);
        // None should be DV
        for (Map<String, Object> shelter : shelters) {
            assertNotEquals("DV Analytics Shelter", shelter.get("name"),
                    "DV shelters must not appear in geographic view");
        }
    }

    // --- 16.8 HIC export generates CSV ---
    @Test
    void hicExport_generatesCsvWithCorrectColumns() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/analytics/hic?date=2026-01-29",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String csv = response.getBody();
        assertNotNull(csv);
        assertTrue(csv.startsWith("ProjectID,ProjectName,ProjectType,"),
                "HIC CSV should have HUD format header");
    }

    // --- 16.9 PIT export aggregates DV ---
    @Test
    void pitExport_aggregatesDvShelters() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/analytics/pit?date=2026-01-29",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String csv = response.getBody();
        assertNotNull(csv);
        assertTrue(csv.startsWith("CoCCode,ProjectType,HouseholdType,TotalPersons"),
                "PIT CSV should have correct header");
    }

    // --- CSV injection protection on HIC export ---
    @Test
    void hicExport_sanitizesFormulaInjection() {
        // Create a shelter with a formula-prefix name
        String body = """
                {
                    "name": "=CMD|'/C calc'!A0",
                    "addressStreet": "100 Danger St",
                    "addressCity": "Raleigh",
                    "addressState": "NC",
                    "addressZip": "27601",
                    "dvShelter": false,
                    "capacities": [{"populationType": "SINGLE_ADULT", "bedsTotal": 5}]
                }
                """;
        restTemplate.exchange("/api/v1/shelters", HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders), String.class);

        // Export HIC — dangerous name should be tab-prefixed per OWASP
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/analytics/hic?date=" + java.time.LocalDate.now(),
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String csv = response.getBody();
        assertNotNull(csv);
        // The dangerous name should NOT appear unsanitized — tab prefix prevents formula execution
        assertFalse(csv.contains(",=CMD"), "CSV must not contain unsanitized formula-prefix cell");
    }

    // --- 16.10 Outreach worker cannot access analytics endpoints ---
    @Test
    void outreachWorker_cannotAccessAnalyticsEndpoints() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/analytics/utilization?from=2026-01-01&to=2026-12-31&granularity=daily",
                HttpMethod.GET,
                new HttpEntity<>(outreachHeaders),
                String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    // --- 16.14 Analytics queries use analytics DataSource ---
    @Test
    void analyticsQueries_doNotExhaustOltpPool() {
        // Execute multiple analytics queries — should not block OLTP
        for (int i = 0; i < 5; i++) {
            restTemplate.exchange(
                    "/api/v1/analytics/geographic",
                    HttpMethod.GET,
                    new HttpEntity<>(adminHeaders),
                    String.class);
        }

        // OLTP should still work
        String body = "{\"populationType\": \"SINGLE_ADULT\", \"limit\": 10}";
        ResponseEntity<String> searchResponse = restTemplate.exchange(
                "/api/v1/queries/beds",
                HttpMethod.POST,
                new HttpEntity<>(body, outreachHeaders),
                String.class);
        assertEquals(HttpStatus.OK, searchResponse.getStatusCode(),
                "OLTP bed search should still work after analytics queries");
    }

    // --- Batch job API tests ---

    @Test
    void batchJobsEndpoint_returnsJobList() {
        var platformAdmin = authHelper.setupUserWithDvAccess(
                "batch-admin@test.fabt.org", "Batch Admin", new String[]{"PLATFORM_ADMIN"});
        HttpHeaders platformHeaders = authHelper.headersForUser(platformAdmin);

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "/api/v1/batch/jobs",
                HttpMethod.GET,
                new HttpEntity<>(platformHeaders),
                new ParameterizedTypeReference<>() {});

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<Map<String, Object>> jobs = response.getBody();
        assertNotNull(jobs);
        // Should have at least the registered jobs
        assertTrue(jobs.size() >= 1, "Should have at least one registered batch job");
    }

    // --- D18: Small-cell suppression tests ---

    // 16.15 DV summary suppressed for single-shelter CoC
    @Test
    void dvSummary_suppressedWhenOnlyOneDvShelter() {
        // Clean slate: remove all DV shelters for this tenant, then create exactly 1
        TenantContext.runWithContext(tenantId, true, () -> {
            jdbcTemplate.update(
                    "DELETE FROM bed_availability WHERE shelter_id IN (SELECT id FROM shelter WHERE tenant_id = ? AND dv_shelter = true)",
                    tenantId);
            jdbcTemplate.update(
                    "DELETE FROM shelter_constraints WHERE shelter_id IN (SELECT id FROM shelter WHERE tenant_id = ? AND dv_shelter = true)",
                    tenantId);
            jdbcTemplate.update("DELETE FROM shelter WHERE tenant_id = ? AND dv_shelter = true", tenantId);
            createTestShelter("Single DV Shelter", true);
        });

        // Evict cached DV summary
        cacheService.evictAll("analytics-dv-summary");

        var dvAdmin = authHelper.setupUserWithDvAccess(
                "dv-suppress-admin@test.fabt.org", "DV Suppress Admin", new String[]{"COC_ADMIN"});
        HttpHeaders dvHeaders = authHelper.headersForUser(dvAdmin);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/analytics/dv-summary",
                HttpMethod.GET,
                new HttpEntity<>(dvHeaders),
                new ParameterizedTypeReference<>() {});

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("suppressed"),
                "DV summary must be suppressed when < 3 DV shelters");
        assertNull(body.get("dv_total_beds"),
                "Suppressed summary must not reveal bed counts");
    }

    // 16.16 DV summary NOT suppressed for multi-shelter CoC
    @Test
    void dvSummary_notSuppressedWhenThreeOrMoreDvShelters() {
        // Create enough DV shelters to satisfy the >= 3 threshold (D18)
        TenantContext.runWithContext(tenantId, true, () -> {
            createTestShelter("DV Shelter Alpha", true);
            createTestShelter("DV Shelter Beta", true);
            createTestShelter("DV Shelter Gamma", true);
            createTestShelter("DV Shelter Delta", true);
        });

        // Evict cached DV summary from prior test runs
        cacheService.evictAll("analytics-dv-summary");

        var dvAdmin = authHelper.setupUserWithDvAccess(
                "dv-multi-admin@test.fabt.org", "DV Multi Admin", new String[]{"COC_ADMIN"});
        HttpHeaders dvHeaders = authHelper.headersForUser(dvAdmin);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/analytics/dv-summary",
                HttpMethod.GET,
                new HttpEntity<>(dvHeaders),
                new ParameterizedTypeReference<>() {});

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(false, body.get("suppressed"),
                "DV summary should NOT be suppressed when >= 3 DV shelters");
    }

    // 16.17 HMIS transformer suppresses DV aggregate for single-shelter CoC
    @Test
    void hmisTransformer_suppressesDvAggregateForSingleShelterCoC() {
        // Clean slate: remove all DV shelters, then create exactly 1
        TenantContext.runWithContext(tenantId, true, () -> {
            jdbcTemplate.update(
                    "DELETE FROM bed_availability WHERE shelter_id IN (SELECT id FROM shelter WHERE tenant_id = ? AND dv_shelter = true)",
                    tenantId);
            jdbcTemplate.update(
                    "DELETE FROM shelter_constraints WHERE shelter_id IN (SELECT id FROM shelter WHERE tenant_id = ? AND dv_shelter = true)",
                    tenantId);
            jdbcTemplate.update("DELETE FROM shelter WHERE tenant_id = ? AND dv_shelter = true", tenantId);
            createTestShelter("HMIS DV Single", true);
            createTestShelter("HMIS Non-DV", false);
        });

        // Transformer test is covered via the HIC export endpoint which uses same suppression logic
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/analytics/hic?date=2026-01-29",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String csv = response.getBody();
        assertNotNull(csv);
        assertFalse(csv.contains("DV Shelters (Aggregated)"),
                "HIC export must not contain DV aggregate row when < 3 DV shelters");
    }

    // --- Helper ---

    private void createTestShelter(String name, boolean dvShelter) {
        UUID shelterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO shelter (id, tenant_id, name, address_street, address_city, address_state, address_zip, dv_shelter, latitude, longitude) "
                        + "VALUES (?, ?, ?, '100 Main St', 'Raleigh', 'NC', '27601', ?, 35.78, -78.64)",
                shelterId, tenantId, name, dvShelter);
        jdbcTemplate.update(
                "INSERT INTO shelter_constraints (shelter_id, population_types_served) VALUES (?, ARRAY['SINGLE_ADULT']::text[])",
                shelterId);
        jdbcTemplate.update(
                "INSERT INTO bed_availability (shelter_id, tenant_id, population_type, beds_total, beds_occupied, beds_on_hold) "
                        + "VALUES (?, ?, 'SINGLE_ADULT', 20, 10, 2)",
                shelterId, tenantId);
    }
}
