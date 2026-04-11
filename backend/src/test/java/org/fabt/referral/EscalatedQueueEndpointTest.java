package org.fabt.referral;

import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.shared.web.TenantContext;
import org.fabt.tenant.domain.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-24 — Integration test for {@code GET /api/v1/dv-referrals/escalated}.
 *
 * <p>Verifies the three contract guarantees Marcus Webb cares about:</p>
 * <ol>
 *   <li><b>Cross-tenant isolation:</b> a tenant A admin must NOT see tenant B
 *       referrals. The referral_token RLS policy only checks dvAccess and does
 *       not isolate by tenant — the service layer must apply the filter.</li>
 *   <li><b>Ordering:</b> results sorted by {@code expires_at ASC}
 *       (most-urgent first).</li>
 *   <li><b>Zero PII:</b> no callback_number or other client-identifying fields
 *       in the response body.</li>
 * </ol>
 *
 * <p>Also pen-tests authorization: COORDINATOR is rejected with 403.</p>
 */
class EscalatedQueueEndpointTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.fabt.tenant.service.TenantService tenantService;

    @Autowired
    private org.fabt.auth.repository.UserRepository userRepository;

    @Autowired
    private org.fabt.auth.service.PasswordService passwordService;

    @Autowired
    private org.fabt.auth.service.JwtService jwtService;

    private Tenant tenantA;
    private Tenant tenantB;
    private UUID shelterAId;
    private UUID shelterBId;
    private HttpHeaders outreachAHeaders;
    private HttpHeaders outreachBHeaders;
    private HttpHeaders cocAdminAHeaders;
    private HttpHeaders coordinatorAHeaders;

    @BeforeEach
    void setUp() {
        tenantA = authHelper.setupTestTenant("queue-tenant-a");
        // Pin tenantA as the active "test tenant" for the helper.
        authHelper.setupAdminUser();

        tenantB = tenantService.findBySlug("queue-tenant-b")
                .orElseGet(() -> tenantService.create("Queue Tenant B", "queue-tenant-b"));

        // Tenant A users — outreach worker, coordinator, CoC admin (all DV-flagged).
        var outreachA = authHelper.setupUserWithDvAccess(
                "queue-outreach-a@test.fabt.org", "Queue Outreach A", new String[]{"OUTREACH_WORKER"});
        outreachAHeaders = authHelper.headersForUser(outreachA);

        var cocAdminA = authHelper.setupUserWithDvAccess(
                "queue-cocadmin-a@test.fabt.org", "Queue CoC Admin A", new String[]{"COC_ADMIN"});
        cocAdminAHeaders = authHelper.headersForUser(cocAdminA);

        var coordA = authHelper.setupUserWithDvAccess(
                "queue-coord-a@test.fabt.org", "Queue Coord A", new String[]{"COORDINATOR"});
        coordinatorAHeaders = authHelper.headersForUser(coordA);

        // Tenant B users — direct creation since TestAuthHelper only manages one tenant.
        var outreachB = createUserInTenant(tenantB, "queue-outreach-b@test.fabt.org",
                "Queue Outreach B", new String[]{"OUTREACH_WORKER"}, true);
        outreachBHeaders = jwtHeaders(outreachB);

        // A platform admin in tenantA used for shelter creation in both tenants.
        var platformAdmin = authHelper.setupUserWithDvAccess(
                "queue-padmin@test.fabt.org", "Queue Platform Admin", new String[]{"PLATFORM_ADMIN"});
        HttpHeaders platformAdminHeaders = authHelper.headersForUser(platformAdmin);

        // Clean prior queue/notification state in both tenants so the assertion math is exact.
        cleanTenant(tenantA.getId());
        cleanTenant(tenantB.getId());

        // Create one DV shelter per tenant.
        TenantContext.runWithContext(tenantA.getId(), true, () ->
                shelterAId = createDvShelter(platformAdminHeaders, "Shelter Tenant A"));

        // Tenant B shelter creation needs an admin in tenantB context. Create one ad-hoc.
        var platformAdminB = createUserInTenant(tenantB, "queue-padmin-b@test.fabt.org",
                "Queue Platform Admin B", new String[]{"PLATFORM_ADMIN"}, true);
        HttpHeaders platformAdminBHeaders = jwtHeaders(platformAdminB);
        TenantContext.runWithContext(tenantB.getId(), true, () ->
                shelterBId = createDvShelter(platformAdminBHeaders, "Shelter Tenant B"));
    }

    @Test
    @DisplayName("T-24a: tenant A admin sees only tenant A referrals")
    void crossTenantIsolation() {
        // Create one referral in each tenant.
        String referralAId = createReferralInTenant(outreachAHeaders, shelterAId);
        String referralBId = createReferralInTenant(outreachBHeaders, shelterBId);

        // Tenant A CoC admin queries the escalated queue.
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals/escalated", HttpMethod.GET,
                new HttpEntity<>(cocAdminAHeaders), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = resp.getBody();
        assertThat(body).isNotNull();

        // The contract: tenant A's referral is present, tenant B's is NOT.
        // This is the load-bearing assertion — cross-tenant leak is the most
        // serious bug coc-admin-escalation could ship with (Marcus Webb).
        assertThat(body)
                .as("Tenant A referral must be visible to tenant A admin")
                .contains(referralAId);
        assertThat(body)
                .as("Tenant B referral must NOT leak into tenant A admin queue")
                .doesNotContain(referralBId);
    }

    @Test
    @DisplayName("T-24b: results sorted by expires_at ASC (most urgent first)")
    void orderedByExpiry() {
        // Create three referrals using three distinct shelters in tenant A — the
        // duplicate-pending guard means we need either three users or three
        // shelters per outreach worker. Three shelters is simpler.
        var platformAdmin = authHelper.setupUserWithDvAccess(
                "queue-padmin@test.fabt.org", "Queue Platform Admin", new String[]{"PLATFORM_ADMIN"});
        HttpHeaders padminHeaders = authHelper.headersForUser(platformAdmin);

        UUID[] shelters = new UUID[3];
        TenantContext.runWithContext(tenantA.getId(), true, () -> {
            shelters[0] = createDvShelter(padminHeaders, "Order Shelter 1");
            shelters[1] = createDvShelter(padminHeaders, "Order Shelter 2");
            shelters[2] = createDvShelter(padminHeaders, "Order Shelter 3");
        });

        String r1 = createReferralInTenant(outreachAHeaders, shelters[0]);
        String r2 = createReferralInTenant(outreachAHeaders, shelters[1]);
        String r3 = createReferralInTenant(outreachAHeaders, shelters[2]);

        // Force distinct expires_at values: r2 most-urgent, r1 next, r3 latest.
        TenantContext.runWithContext(tenantA.getId(), true, () -> {
            jdbcTemplate.update("UPDATE referral_token SET expires_at = NOW() + INTERVAL '5 minutes' WHERE id = ?::uuid", r2);
            jdbcTemplate.update("UPDATE referral_token SET expires_at = NOW() + INTERVAL '15 minutes' WHERE id = ?::uuid", r1);
            jdbcTemplate.update("UPDATE referral_token SET expires_at = NOW() + INTERVAL '30 minutes' WHERE id = ?::uuid", r3);
        });

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals/escalated", HttpMethod.GET,
                new HttpEntity<>(cocAdminAHeaders), String.class);
        String body = resp.getBody();
        assertThat(body).isNotNull();

        int idx2 = body.indexOf(r2);
        int idx1 = body.indexOf(r1);
        int idx3 = body.indexOf(r3);
        assertThat(idx2).as("r2 must be present").isGreaterThanOrEqualTo(0);
        assertThat(idx1).as("r1 must be present").isGreaterThanOrEqualTo(0);
        assertThat(idx3).as("r3 must be present").isGreaterThanOrEqualTo(0);
        assertThat(idx2).as("r2 (5m to expiry) must come before r1 (15m)").isLessThan(idx1);
        assertThat(idx1).as("r1 (15m to expiry) must come before r3 (30m)").isLessThan(idx3);
    }

    @Test
    @DisplayName("T-24c: response contains zero PII fields")
    void zeroPiiInResponse() {
        createReferralInTenant(outreachAHeaders, shelterAId);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals/escalated", HttpMethod.GET,
                new HttpEntity<>(cocAdminAHeaders), String.class);
        String body = resp.getBody();
        assertThat(body).isNotNull();

        // EscalatedReferralDto fields: id, shelterId, shelterName, populationType,
        // householdSize, urgency, createdAt, expiresAt, remainingMinutes,
        // assignedCoordinatorId/Name, claimedByAdminId/Name, claimExpiresAt.
        // Verify no PII keys are emitted.
        assertThat(body).doesNotContain("callback_number");
        assertThat(body).doesNotContain("callbackNumber");
        assertThat(body).doesNotContain("client_name");
        assertThat(body).doesNotContain("clientName");
        assertThat(body).doesNotContain("specialNeeds");
        // The phone number used in createReferralInTenant
        assertThat(body).doesNotContain("919-555-0099");
    }

    @Test
    @DisplayName("T-24d: COORDINATOR is rejected with 403")
    void coordinatorRejected() {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals/escalated", HttpMethod.GET,
                new HttpEntity<>(coordinatorAHeaders), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void cleanTenant(UUID tenantId) {
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            conn.createStatement().execute("RESET ROLE");
            try (var ps = conn.prepareStatement("DELETE FROM referral_token WHERE tenant_id = ?")) {
                ps.setObject(1, tenantId);
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("DELETE FROM notification WHERE tenant_id = ?")) {
                ps.setObject(1, tenantId);
                ps.executeUpdate();
            }
            conn.createStatement().execute("SET ROLE fabt_app");
        } catch (java.sql.SQLException e) {
            throw new RuntimeException("cleanTenant failed", e);
        }
    }

    private User createUserInTenant(Tenant tenant, String email, String displayName,
                                     String[] roles, boolean dvAccess) {
        return userRepository.findByTenantIdAndEmail(tenant.getId(), email)
                .orElseGet(() -> {
                    User u = new User();
                    u.setTenantId(tenant.getId());
                    u.setEmail(email);
                    u.setDisplayName(displayName);
                    u.setPasswordHash(passwordService.hash(TestAuthHelper.TEST_PASSWORD));
                    u.setRoles(roles);
                    u.setDvAccess(dvAccess);
                    u.setCreatedAt(java.time.Instant.now());
                    u.setUpdatedAt(java.time.Instant.now());
                    return userRepository.save(u);
                });
    }

    private HttpHeaders jwtHeaders(User user) {
        String token = jwtService.generateAccessToken(user);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Content-Type", "application/json");
        return headers;
    }

    private String createReferralInTenant(HttpHeaders headers, UUID shelterId) {
        String body = String.format("""
                {
                  "shelterId": "%s",
                  "householdSize": 2,
                  "populationType": "DV_SURVIVOR",
                  "urgency": "URGENT",
                  "specialNeeds": null,
                  "callbackNumber": "919-555-0099"
                }
                """, shelterId);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return extractField(resp.getBody(), "id");
    }

    private UUID createDvShelter(HttpHeaders headers, String name) {
        String body = String.format("""
                {
                  "name": "%s %s",
                  "addressStreet": "123 Test St",
                  "addressCity": "Raleigh",
                  "addressState": "NC",
                  "addressZip": "27601",
                  "phone": "919-555-%04d",
                  "dvShelter": true,
                  "constraints": {"populationTypesServed": ["DV_SURVIVOR"]},
                  "capacities": [{"populationType": "DV_SURVIVOR", "bedsTotal": 10}]
                }
                """, name, UUID.randomUUID().toString().substring(0, 8),
                (int) (Math.random() * 9999));
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/shelters", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID shelterId = UUID.fromString(extractField(resp.getBody(), "id"));
        String availBody = """
                {"populationType": "DV_SURVIVOR", "bedsTotal": 10, "bedsOccupied": 3, "bedsOnHold": 0, "acceptingNewGuests": true}
                """;
        restTemplate.exchange("/api/v1/shelters/" + shelterId + "/availability",
                org.springframework.http.HttpMethod.PATCH,
                new HttpEntity<>(availBody, headers), String.class);
        return shelterId;
    }

    private String extractField(String json, String field) {
        int idx = json.indexOf("\"" + field + "\":\"");
        if (idx < 0) throw new AssertionError("Field '" + field + "' not found: " + json);
        int start = idx + field.length() + 4;
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }
}
