package org.fabt.shelter;

import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
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

/**
 * Integration tests for DV shelter address redaction based on tenant policy.
 * Verifies FVPSA compliance: address visibility controlled by dv_address_visibility policy.
 */
class DvAddressRedactionTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID dvShelterId;
    private UUID nonDvShelterId;
    private HttpHeaders adminHeaders;
    private HttpHeaders outreachHeaders;
    private HttpHeaders assignedCoordHeaders;
    private HttpHeaders unassignedCoordHeaders;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        authHelper.setupOutreachWorkerUser();

        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            // Admin with dvAccess
            var dvAdmin = authHelper.setupUserWithDvAccess(
                    "dvadmin-redact@test.fabt.org", "DV Admin", new String[]{"PLATFORM_ADMIN"});
            adminHeaders = authHelper.headersForUser(dvAdmin);

            // Outreach with dvAccess
            var dvOutreach = authHelper.setupUserWithDvAccess(
                    "dvoutreach-redact@test.fabt.org", "DV Outreach", new String[]{"OUTREACH_WORKER"});
            outreachHeaders = authHelper.headersForUser(dvOutreach);

            // Assigned coordinator with dvAccess
            var assignedCoord = authHelper.setupUserWithDvAccess(
                    "assigned-coord@test.fabt.org", "Assigned Coord", new String[]{"COORDINATOR"});
            assignedCoordHeaders = authHelper.headersForUser(assignedCoord);

            // Unassigned coordinator with dvAccess
            var unassignedCoord = authHelper.setupUserWithDvAccess(
                    "unassigned-coord@test.fabt.org", "Unassigned Coord", new String[]{"COORDINATOR"});
            unassignedCoordHeaders = authHelper.headersForUser(unassignedCoord);

            // Create shelters
            dvShelterId = createShelter(true);
            nonDvShelterId = createShelter(false);

            // Assign coordinator to DV shelter
            assignCoordinator(assignedCoord.getId(), dvShelterId);

            // Set default policy
            setPolicy("ADMIN_AND_ASSIGNED");
        });
    }

    // =========================================================================
    // ADMIN_AND_ASSIGNED policy (default)
    // =========================================================================

    @Test
    void adminAndAssigned_adminSeesAddress() {
        ResponseEntity<Map<String, Object>> resp = getDetail(dvShelterId, adminHeaders);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> shelter = getShelterFromDetail(resp);
        assertNotNull(shelter.get("addressStreet"), "Admin should see address under ADMIN_AND_ASSIGNED");
    }

    @Test
    void adminAndAssigned_assignedCoordinatorSeesAddress() {
        ResponseEntity<Map<String, Object>> resp = getDetail(dvShelterId, assignedCoordHeaders);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> shelter = getShelterFromDetail(resp);
        assertNotNull(shelter.get("addressStreet"), "Assigned coordinator should see address under ADMIN_AND_ASSIGNED");
    }

    @Test
    void adminAndAssigned_unassignedCoordinatorDoesNotSeeAddress() {
        ResponseEntity<Map<String, Object>> resp = getDetail(dvShelterId, unassignedCoordHeaders);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> shelter = getShelterFromDetail(resp);
        assertNull(shelter.get("addressStreet"), "Unassigned coordinator should NOT see address under ADMIN_AND_ASSIGNED");
        assertNull(shelter.get("latitude"), "Unassigned coordinator should NOT see coordinates");
    }

    @Test
    void adminAndAssigned_outreachDoesNotSeeAddress() {
        ResponseEntity<Map<String, Object>> resp = getDetail(dvShelterId, outreachHeaders);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> shelter = getShelterFromDetail(resp);
        assertNull(shelter.get("addressStreet"), "Outreach should NOT see address under ADMIN_AND_ASSIGNED");
    }

    // =========================================================================
    // ADMIN_ONLY policy
    // =========================================================================

    @Test
    void adminOnly_assignedCoordinatorDoesNotSeeAddress() {
        setPolicy("ADMIN_ONLY");
        ResponseEntity<Map<String, Object>> resp = getDetail(dvShelterId, assignedCoordHeaders);
        Map<String, Object> shelter = getShelterFromDetail(resp);
        assertNull(shelter.get("addressStreet"), "Even assigned coordinator should NOT see address under ADMIN_ONLY");
    }

    // =========================================================================
    // ALL_DV_ACCESS policy
    // =========================================================================

    @Test
    void allDvAccess_outreachSeesAddress() {
        setPolicy("ALL_DV_ACCESS");
        ResponseEntity<Map<String, Object>> resp = getDetail(dvShelterId, outreachHeaders);
        Map<String, Object> shelter = getShelterFromDetail(resp);
        assertNotNull(shelter.get("addressStreet"), "Outreach should see address under ALL_DV_ACCESS");
    }

    // =========================================================================
    // NONE policy
    // =========================================================================

    @Test
    void none_adminDoesNotSeeAddress() {
        setPolicy("NONE");
        ResponseEntity<Map<String, Object>> resp = getDetail(dvShelterId, adminHeaders);
        Map<String, Object> shelter = getShelterFromDetail(resp);
        assertNull(shelter.get("addressStreet"), "Even admin should NOT see address under NONE");
    }

    // =========================================================================
    // Non-DV shelters always return address
    // =========================================================================

    @Test
    void nonDvShelter_alwaysReturnsAddress() {
        setPolicy("NONE");
        ResponseEntity<Map<String, Object>> resp = getDetail(nonDvShelterId, outreachHeaders);
        Map<String, Object> shelter = getShelterFromDetail(resp);
        assertNotNull(shelter.get("addressStreet"), "Non-DV shelter always shows address regardless of policy");
    }

    // =========================================================================
    // Policy change endpoint safeguards
    // =========================================================================

    @Test
    void policyChange_withoutConfirmation_rejected() {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/tenants/" + authHelper.getTestTenantId() + "/dv-address-policy",
                HttpMethod.PUT, new HttpEntity<>("{\"policy\": \"NONE\"}", adminHeaders), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void policyChange_nonAdmin_rejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(outreachHeaders);
        headers.set("X-Confirm-Policy-Change", "CONFIRM");
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/tenants/" + authHelper.getTestTenantId() + "/dv-address-policy",
                HttpMethod.PUT, new HttpEntity<>("{\"policy\": \"NONE\"}", headers), String.class);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void policyChange_invalidValue_rejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(adminHeaders);
        headers.set("X-Confirm-Policy-Change", "CONFIRM");
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/tenants/" + authHelper.getTestTenantId() + "/dv-address-policy",
                HttpMethod.PUT, new HttpEntity<>("{\"policy\": \"INVALID\"}", headers), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().contains("ADMIN_AND_ASSIGNED"), "Response should list valid policies");
    }

    // =========================================================================
    // List endpoint redacts
    // =========================================================================

    @Test
    void listEndpoint_redactsDvShelterAddress() {
        setPolicy("NONE");
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/shelters", HttpMethod.GET,
                new HttpEntity<>(outreachHeaders), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        // The DV shelter in the list should have null address
        // Non-DV shelters should still have addresses
        String body = resp.getBody();
        assertNotNull(body);
        // At least one shelter should have an address (non-DV)
        assertTrue(body.contains("addressStreet"), "Non-DV shelters in list should have address");
    }

    // =========================================================================
    // HSDS export redacts
    // =========================================================================

    @Test
    void hsdsExport_redactsDvShelterAddress() {
        setPolicy("ADMIN_AND_ASSIGNED");
        ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                "/api/v1/shelters/" + dvShelterId + "?format=hsds",
                HttpMethod.GET, new HttpEntity<>(outreachHeaders),
                new ParameterizedTypeReference<>() {});
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> location = (Map<String, Object>) resp.getBody().get("location");
        assertNotNull(location);
        assertNull(location.get("physical_address"), "HSDS should NOT include physical_address for DV shelter");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UUID createShelter(boolean dvShelter) {
        String body = String.format("""
                {
                  "name": "%s Redact Test %s",
                  "addressStreet": "123 Redact St",
                  "addressCity": "Raleigh",
                  "addressState": "NC",
                  "addressZip": "27601",
                  "phone": "919-555-%04d",
                  "latitude": 35.78,
                  "longitude": -78.64,
                  "dvShelter": %s,
                  "constraints": { "populationTypesServed": ["%s"] },
                  "capacities": [{"populationType": "%s", "bedsTotal": 5}]
                }
                """,
                dvShelter ? "DV" : "Regular",
                UUID.randomUUID().toString().substring(0, 8),
                (int) (Math.random() * 9999),
                dvShelter,
                dvShelter ? "DV_SURVIVOR" : "SINGLE_ADULT",
                dvShelter ? "DV_SURVIVOR" : "SINGLE_ADULT");

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/shelters", HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders), String.class);
        String respBody = resp.getBody();
        int idx = respBody.indexOf("\"id\":\"") + 6;
        return UUID.fromString(respBody.substring(idx, respBody.indexOf("\"", idx)));
    }

    private void assignCoordinator(UUID userId, UUID shelterId) {
        String body = String.format("{\"userId\": \"%s\"}", userId);
        restTemplate.exchange("/api/v1/shelters/" + shelterId + "/coordinators",
                HttpMethod.POST, new HttpEntity<>(body, adminHeaders), String.class);
    }

    private void setPolicy(String policy) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(adminHeaders);
        headers.set("X-Confirm-Policy-Change", "CONFIRM");
        restTemplate.exchange(
                "/api/v1/tenants/" + authHelper.getTestTenantId() + "/dv-address-policy",
                HttpMethod.PUT, new HttpEntity<>("{\"policy\": \"" + policy + "\"}", headers), String.class);
    }

    private ResponseEntity<Map<String, Object>> getDetail(UUID shelterId, HttpHeaders headers) {
        return restTemplate.exchange(
                "/api/v1/shelters/" + shelterId, HttpMethod.GET,
                new HttpEntity<>(headers), new ParameterizedTypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getShelterFromDetail(ResponseEntity<Map<String, Object>> resp) {
        return (Map<String, Object>) resp.getBody().get("shelter");
    }
}
