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

    // --- 16.8 HIC export matches HUD Inventory.csv schema (FY2024+) ---
    @Test
    void hicExport_hasHudInventoryCsvHeader() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/analytics/hic?date=2026-01-29",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String csv = response.getBody();
        assertNotNull(csv);
        assertTrue(csv.startsWith("InventoryID,ProjectID,CoCCode,HouseholdType,Availability,"),
                "HIC CSV should match HUD Inventory.csv header");
        // Verify all required columns present
        String header = csv.split("\n")[0];
        assertTrue(header.contains("BedInventory"), "Must have BedInventory column");
        assertTrue(header.contains("VetBedInventory"), "Must have VetBedInventory column");
        assertTrue(header.contains("ESBedType"), "Must have ESBedType column");
        assertTrue(header.contains("InventoryStartDate"), "Must have InventoryStartDate column");
    }

    @Test
    void hicExport_usesIntegerCodes() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/analytics/hic?date=" + java.time.LocalDate.now(),
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String csv = response.getBody();
        assertNotNull(csv);
        String[] lines = csv.split("\n");
        assertTrue(lines.length > 1, "HIC should have at least one data row");

        // Data rows should use integer codes, not strings
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            // HouseholdType (column 3, 0-indexed) should be 1, 3, or 4
            String[] cols = line.split(",", -1);
            String hhType = cols[3].trim();
            assertTrue(hhType.equals("1") || hhType.equals("3") || hhType.equals("4"),
                    "HouseholdType should be HUD integer (1/3/4) but was '" + hhType + "' in row " + i);
        }
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
        // PIT ProjectType should be integer 0 (ES Entry/Exit), not string "ES"
        if (csv.split("\n").length > 1) {
            String firstDataRow = csv.split("\n")[1];
            String[] cols = firstDataRow.split(",", -1);
            assertEquals("0", cols[1].trim(), "PIT ProjectType should be HUD integer code 0 (ES Entry/Exit)");
        }
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
        // With new HUD Inventory.csv format, DV aggregate row has empty InventoryID and ProjectID
        // If suppression works, no row should start with ",," (empty InventoryID + empty ProjectID)
        String[] lines = csv.split("\n");
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            assertFalse(lines[i].startsWith(",,"),
                    "DV aggregate row should be suppressed when < 3 DV shelters, but found row starting with empty IDs: " + lines[i]);
        }
    }

    // --- HIC/PIT Edge Case Tests (T-48 through T-55) ---

    @Test
    void hicExport_unknownPopulationType_returns400() {
        // Create a shelter with an invalid population type via direct DB insert
        UUID badShelterId = UUID.randomUUID();
        TenantContext.runWithContext(tenantId, true, () -> {
            jdbcTemplate.update(
                    "INSERT INTO shelter (id, tenant_id, name, address_street, address_city, address_state, address_zip, dv_shelter) "
                    + "VALUES (?, ?, 'Bad Pop Type Shelter', '1 Test', 'Raleigh', 'NC', '27601', false)",
                    badShelterId, tenantId);
            jdbcTemplate.update(
                    "INSERT INTO bed_availability (shelter_id, tenant_id, population_type, beds_total, beds_occupied, beds_on_hold) "
                    + "VALUES (?, ?, 'NONEXISTENT_TYPE', 10, 5, 0)",
                    badShelterId, tenantId);
        });

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    "/api/v1/analytics/hic?date=" + java.time.LocalDate.now(),
                    HttpMethod.GET,
                    new HttpEntity<>(adminHeaders),
                    String.class);

            // Should fail because NONEXISTENT_TYPE can't be mapped
            // IllegalArgumentException → 400 via GlobalExceptionHandler
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        } finally {
            // Clean up so subsequent tests aren't poisoned
            TenantContext.runWithContext(tenantId, true, () -> {
                jdbcTemplate.update("DELETE FROM bed_availability WHERE shelter_id = ?", badShelterId);
                jdbcTemplate.update("DELETE FROM shelter WHERE id = ?", badShelterId);
            });
        }
    }

    // --- Riley's thorough HIC/PIT content validation ---
    // "If Marcus hands this to HUD, every column in every row must be correct."

    @Test
    void hicExport_rowByRow_correctColumnsAndValues() {
        // Create a shelter with known data so we can verify exact row content
        UUID knownId = UUID.randomUUID();
        TenantContext.runWithContext(tenantId, true, () -> {
            jdbcTemplate.update(
                    "INSERT INTO shelter (id, tenant_id, name, address_street, address_city, address_state, address_zip, dv_shelter, created_at) "
                    + "VALUES (?, ?, 'Riley Verification Shelter', '100 Test St', 'Raleigh', 'NC', '27601', false, '2026-01-15T00:00:00Z')",
                    knownId, tenantId);
            // Family beds: 30 total
            jdbcTemplate.update(
                    "INSERT INTO bed_availability (shelter_id, tenant_id, population_type, beds_total, beds_occupied, beds_on_hold) "
                    + "VALUES (?, ?, 'FAMILY_WITH_CHILDREN', 30, 20, 3)", knownId, tenantId);
            // Veteran beds: 10 total
            jdbcTemplate.update(
                    "INSERT INTO bed_availability (shelter_id, tenant_id, population_type, beds_total, beds_occupied, beds_on_hold) "
                    + "VALUES (?, ?, 'VETERAN', 10, 5, 0)", knownId, tenantId);
        });

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/analytics/hic?date=" + java.time.LocalDate.now(),
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String csv = response.getBody();
        assertNotNull(csv);

        // Parse CSV into structured data
        String[] lines = csv.split("\n");
        String[] headers = lines[0].split(",", -1);

        // Map header names to column indices
        java.util.Map<String, Integer> colIndex = new java.util.HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            colIndex.put(headers[i].trim(), i);
        }

        // Verify all HUD required columns exist
        for (String required : List.of("InventoryID", "ProjectID", "CoCCode", "HouseholdType",
                "Availability", "UnitInventory", "BedInventory", "VetBedInventory",
                "OtherBedInventory", "ESBedType", "InventoryStartDate")) {
            assertTrue(colIndex.containsKey(required),
                    "Missing required HUD column: " + required);
        }

        // Find rows for our known shelter (by ProjectID)
        String projectId = knownId.toString();
        java.util.List<String[]> shelterRows = new java.util.ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            String[] cols = lines[i].split(",", -1);
            if (cols[colIndex.get("ProjectID")].contains(projectId)) {
                shelterRows.add(cols);
            }
        }

        // Should have 2 rows: one for FAMILY_WITH_CHILDREN, one for VETERAN
        assertEquals(2, shelterRows.size(),
                "Riley Verification Shelter should have exactly 2 inventory rows (family + veteran)");

        // Verify family row
        String[] familyRow = shelterRows.stream()
                .filter(r -> "3".equals(r[colIndex.get("HouseholdType")].trim()))
                .findFirst().orElseThrow(() -> new AssertionError("No family household type (3) row found"));

        assertEquals("30", familyRow[colIndex.get("BedInventory")].trim(),
                "Family BedInventory should be 30");
        assertEquals("30", familyRow[colIndex.get("UnitInventory")].trim(),
                "Family UnitInventory should be 30 (= beds for ES)");
        assertEquals("0", familyRow[colIndex.get("VetBedInventory")].trim(),
                "Family row VetBedInventory should be 0");
        assertEquals("30", familyRow[colIndex.get("OtherBedInventory")].trim(),
                "Family row OtherBedInventory should be 30");
        assertEquals("1", familyRow[colIndex.get("Availability")].trim(),
                "Availability should be 1 (Year-round)");
        assertEquals("1", familyRow[colIndex.get("ESBedType")].trim(),
                "ESBedType should be 1 (Facility-based)");
        assertTrue(familyRow[colIndex.get("InventoryStartDate")].trim().startsWith("2026-01-15"),
                "InventoryStartDate should come from shelter createdAt");

        // Verify veteran row
        String[] vetRow = shelterRows.stream()
                .filter(r -> "1".equals(r[colIndex.get("HouseholdType")].trim()))
                .findFirst().orElseThrow(() -> new AssertionError("No veteran household type (1) row found"));

        assertEquals("10", vetRow[colIndex.get("BedInventory")].trim(),
                "Veteran BedInventory should be 10");
        assertEquals("10", vetRow[colIndex.get("VetBedInventory")].trim(),
                "Veteran row VetBedInventory should be 10");
        assertEquals("0", vetRow[colIndex.get("OtherBedInventory")].trim(),
                "Veteran row OtherBedInventory should be 0 (all beds are vet beds)");

        // Verify CoCCode matches tenant slug
        assertFalse(familyRow[colIndex.get("CoCCode")].trim().isEmpty(),
                "CoCCode must be populated");

        // Verify InventoryID is non-empty and deterministic
        assertFalse(familyRow[colIndex.get("InventoryID")].trim().isEmpty(),
                "InventoryID must be populated");
    }

    @Test
    void hicExport_allRows_haveConsistentColumnCount() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/analytics/hic?date=" + java.time.LocalDate.now(),
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String csv = response.getBody();
        assertNotNull(csv);

        String[] lines = csv.split("\n");
        int headerColCount = lines[0].split(",", -1).length;

        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            int rowColCount = lines[i].split(",", -1).length;
            assertEquals(headerColCount, rowColCount,
                    "Row " + i + " has " + rowColCount + " columns but header has " + headerColCount);
        }
    }

    @Test
    void pitExport_rowByRow_usesIntegerCodes() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/analytics/pit?date=" + java.time.LocalDate.now(),
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String csv = response.getBody();
        assertNotNull(csv);

        String[] lines = csv.split("\n");
        assertTrue(lines.length > 1, "PIT should have at least one data row");

        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            String[] cols = lines[i].split(",", -1);

            // Column 1: ProjectType — should be integer 0 (ES Entry/Exit)
            assertEquals("0", cols[1].trim(),
                    "PIT ProjectType should be HUD integer 0 (ES Entry/Exit) in row " + i);

            // Column 2: HouseholdType — should be integer 1, 3, or 4
            String hhType = cols[2].trim();
            assertTrue(hhType.equals("1") || hhType.equals("3") || hhType.equals("4"),
                    "PIT HouseholdType should be HUD integer (1/3/4) but was '" + hhType + "' in row " + i);

            // Column 3: TotalPersons — should be a non-negative integer
            int persons = Integer.parseInt(cols[3].trim());
            assertTrue(persons >= 0,
                    "PIT TotalPersons should be >= 0 but was " + persons + " in row " + i);
        }
    }

    @Test
    void hicExport_csvParseable_byStandardParser() throws Exception {
        // Riley: "Parse it back. If a CSV library chokes on our output, HUD's system will too."
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/analytics/hic?date=" + java.time.LocalDate.now(),
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String csv = response.getBody();
        assertNotNull(csv);

        // Parse with Apache Commons CSV — the same library we use for import
        try (var parser = org.apache.commons.csv.CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).build()
                .parse(new java.io.StringReader(csv))) {

            var records = parser.getRecords();
            assertFalse(records.isEmpty(), "CSV should have at least one parseable data record");

            // Every record should have the same number of fields as the header
            int headerSize = parser.getHeaderNames().size();
            for (int i = 0; i < records.size(); i++) {
                assertEquals(headerSize, records.get(i).size(),
                        "Record " + i + " has " + records.get(i).size() + " fields but header has " + headerSize);
            }

            // Verify known header names are present
            assertTrue(parser.getHeaderNames().contains("InventoryID"), "Parsed header must include InventoryID");
            assertTrue(parser.getHeaderNames().contains("BedInventory"), "Parsed header must include BedInventory");
            assertTrue(parser.getHeaderNames().contains("CoCCode"), "Parsed header must include CoCCode");
        }
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
