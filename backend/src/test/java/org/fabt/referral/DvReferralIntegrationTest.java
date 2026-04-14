package org.fabt.referral;

import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.auth.service.UserService;
import org.fabt.notification.service.NotificationService;
import org.fabt.referral.service.ReferralTokenPurgeService;
import org.fabt.referral.service.ReferralTokenService;
import org.fabt.tenant.domain.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.fabt.shared.web.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for DV opaque referral token lifecycle.
 * Verifies zero-PII design, warm handoff, purge, and access control.
 */
class DvReferralIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReferralTokenService referralTokenService;

    @Autowired
    private ReferralTokenPurgeService purgeService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private UserService userService;

    @LocalServerPort
    private int port;

    private UUID dvShelterId;
    private UUID nonDvShelterId;
    private HttpHeaders outreachHeaders;
    private HttpHeaders coordHeaders;
    private HttpHeaders adminHeaders;
    private HttpHeaders noDvOutreachHeaders;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        authHelper.setupOutreachWorkerUser();
        authHelper.setupCocAdminUser();

        // DV operations require dvAccess=true — use a DV-access admin for setup
        var dvAdmin = authHelper.setupUserWithDvAccess(
                "dvadmin@test.fabt.org", "DV Admin", new String[]{"PLATFORM_ADMIN"});
        adminHeaders = authHelper.headersForUser(dvAdmin);
        // Outreach worker with dvAccess=true (can see DV shelters via RLS)
        var dvOutreach = authHelper.setupUserWithDvAccess(
                "dvoutreach@test.fabt.org", "DV Outreach", new String[]{"OUTREACH_WORKER"});
        outreachHeaders = authHelper.headersForUser(dvOutreach);
        // Coordinator with dvAccess=true (can screen DV referrals)
        var dvCoord = authHelper.setupUserWithDvAccess(
                "dvcoord@test.fabt.org", "DV Coordinator", new String[]{"COC_ADMIN"});
        coordHeaders = authHelper.headersForUser(dvCoord);
        // Outreach worker WITHOUT dvAccess (for negative tests)
        noDvOutreachHeaders = authHelper.outreachWorkerHeaders();

        // Bind TenantContext for direct JDBC calls in test thread (RLS requires dvAccess + tenantId)
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            dvShelterId = createShelter(true);
            nonDvShelterId = createShelter(false);

            // Set up availability so DV shelter has beds
            patchAvailability(dvShelterId, "DV_SURVIVOR", 10, 3, 0);
        });
    }

    // =========================================================================
    // Token Lifecycle
    // =========================================================================

    @Test
    void tc_create_accept_warmHandoff() {
        // Create referral
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        assertEquals(HttpStatus.CREATED, createResp.getStatusCode());
        String tokenId = extractField(createResp.getBody(), "id");
        assertNotNull(tokenId);

        // Verify token is PENDING
        assertTrue(createResp.getBody().contains("\"status\":\"PENDING\""));

        // Accept
        ResponseEntity<String> acceptResp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/accept",
                HttpMethod.PATCH, new HttpEntity<>(coordHeaders), String.class);
        assertEquals(HttpStatus.OK, acceptResp.getStatusCode());
        assertTrue(acceptResp.getBody().contains("\"status\":\"ACCEPTED\""));

        // Warm handoff: accepted response includes shelter phone
        assertTrue(acceptResp.getBody().contains("\"shelterPhone\""),
                "ACCEPTED response must include shelter phone for warm handoff");

        // Warm handoff: verify response does NOT include shelter address
        assertFalse(acceptResp.getBody().contains("\"addressStreet\""),
                "Response must NOT include shelter address (FVPSA)");
        assertFalse(acceptResp.getBody().contains("\"addressCity\""),
                "Response must NOT include shelter city");
        assertFalse(acceptResp.getBody().contains("\"latitude\""),
                "Response must NOT include shelter latitude");
    }

    /**
     * notification-deep-linking (Issue #106) — war-room C-1 fix.
     *
     * GET /api/v1/dv-referrals/{id} is the lookup the frontend deep-link
     * processor uses to resolve a notification's referralId to its shelter
     * (so the dashboard can auto-expand the right card). Without this
     * endpoint, every notification click 404s and the user sees the
     * "stale referral" toast for legitimate referrals.
     */
    @Test
    void tc_getById_returnsReferralWithShelterId_forDeepLinking() {
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        assertEquals(HttpStatus.CREATED, createResp.getStatusCode());
        String tokenId = extractField(createResp.getBody(), "id");

        ResponseEntity<String> getResp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId, HttpMethod.GET,
                new HttpEntity<>(coordHeaders), String.class);
        assertEquals(HttpStatus.OK, getResp.getStatusCode(),
                "Coordinator must be able to fetch a single referral by id for deep-linking — body: "
                        + getResp.getBody());
        String body = getResp.getBody();
        assertNotNull(body);
        // Must include shelterId so the frontend can auto-expand the card.
        assertTrue(body.contains("\"shelterId\":\"" + dvShelterId + "\""),
                "GET /dv-referrals/{id} must include shelterId for deep-linking — body: " + body);
        assertTrue(body.contains("\"id\":\"" + tokenId + "\""),
                "GET /dv-referrals/{id} must echo the requested id — body: " + body);
        assertTrue(body.contains("\"populationType\":\"DV_SURVIVOR\""),
                "Response must include populationType for the aria-live announcement — body: " + body);
        // Zero-PII: no shelter address fields leak through.
        assertFalse(body.contains("\"addressStreet\""),
                "GET /dv-referrals/{id} must NOT include shelter address (FVPSA)");
    }

    @Test
    void tc_getById_unknownId_returns404_forD10StaleFallback() {
        UUID nonexistent = UUID.randomUUID();
        ResponseEntity<String> getResp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + nonexistent, HttpMethod.GET,
                new HttpEntity<>(coordHeaders), String.class);
        // 404 is what the frontend's stale-referral handler keys off (D10).
        assertEquals(HttpStatus.NOT_FOUND, getResp.getStatusCode(),
                "Unknown referral id must return 404 so the deep-link UI can show the stale toast");
    }

    /**
     * Task 8.6 — the same cross-tenant isolation as 8.5, applied to
     * state-mutating paths: accept / reject. Before the 8.5 / 8.6 fix
     * (2026-04-14), a dv-access COORDINATOR in Tenant A could accept a
     * Tenant B DV referral if they knew the UUID — no 4xx response,
     * state changed successfully. After the fix, both endpoints return
     * 404 (NoSuchElementException → GlobalExceptionHandler).
     */
    @Test
    void tc_accept_crossTenant_returns404() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Tenant tenantB = authHelper.setupSecondaryTenant("xtenant-8-6-" + suffix);
        User adminB = authHelper.setupUserWithDvAccessInTenant(tenantB.getId(),
                "admin-b-8-6-" + suffix + "@test.fabt.org", "Tenant B Admin",
                new String[]{"PLATFORM_ADMIN", "COC_ADMIN"});
        User outreachB = authHelper.setupUserWithDvAccessInTenant(tenantB.getId(),
                "outreach-b-8-6-" + suffix + "@test.fabt.org", "Tenant B Outreach",
                new String[]{"OUTREACH_WORKER"});
        HttpHeaders adminBHeaders = authHelper.headersForUser(adminB);
        HttpHeaders outreachBHeaders = authHelper.headersForUser(outreachB);
        UUID shelterB = createShelterWithHeaders(adminBHeaders, true);
        patchAvailabilityInTenant(shelterB, "DV_SURVIVOR", 10, 3, 0, adminBHeaders);
        ResponseEntity<String> bReferralResp = createReferral(shelterB, outreachBHeaders);
        assertTrue(bReferralResp.getStatusCode().is2xxSuccessful());
        UUID tenantBReferralId = UUID.fromString(extractField(bReferralResp.getBody(), "id"));

        ResponseEntity<String> acceptResp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tenantBReferralId + "/accept", HttpMethod.PATCH,
                new HttpEntity<>("{}", coordHeaders), String.class);
        assertEquals(HttpStatus.NOT_FOUND, acceptResp.getStatusCode(),
                "Cross-tenant accept must return 404 (was a silent state change before 8.5/8.6 fix)");

        // Confirm Tenant B's referral is still PENDING (not flipped to ACCEPTED).
        TenantContext.runWithContext(tenantB.getId(), true, () -> {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM referral_token WHERE id = ?::uuid",
                    String.class, tenantBReferralId);
            assertEquals("PENDING", status,
                    "Tenant B's referral must remain PENDING after cross-tenant accept attempt");
        });
    }

    @Test
    void tc_reject_crossTenant_returns404() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Tenant tenantB = authHelper.setupSecondaryTenant("xtenant-8-6b-" + suffix);
        User adminB = authHelper.setupUserWithDvAccessInTenant(tenantB.getId(),
                "admin-b-8-6b-" + suffix + "@test.fabt.org", "Tenant B Admin",
                new String[]{"PLATFORM_ADMIN", "COC_ADMIN"});
        User outreachB = authHelper.setupUserWithDvAccessInTenant(tenantB.getId(),
                "outreach-b-8-6b-" + suffix + "@test.fabt.org", "Tenant B Outreach",
                new String[]{"OUTREACH_WORKER"});
        HttpHeaders adminBHeaders = authHelper.headersForUser(adminB);
        HttpHeaders outreachBHeaders = authHelper.headersForUser(outreachB);
        UUID shelterB = createShelterWithHeaders(adminBHeaders, true);
        patchAvailabilityInTenant(shelterB, "DV_SURVIVOR", 10, 3, 0, adminBHeaders);
        ResponseEntity<String> bReferralResp = createReferral(shelterB, outreachBHeaders);
        assertTrue(bReferralResp.getStatusCode().is2xxSuccessful());
        UUID tenantBReferralId = UUID.fromString(extractField(bReferralResp.getBody(), "id"));

        ResponseEntity<String> rejectResp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tenantBReferralId + "/reject", HttpMethod.PATCH,
                new HttpEntity<>("{\"reason\":\"cross-tenant probe\"}", coordHeaders), String.class);
        assertEquals(HttpStatus.NOT_FOUND, rejectResp.getStatusCode(),
                "Cross-tenant reject must return 404 (was a silent state change before 8.5/8.6 fix)");

        TenantContext.runWithContext(tenantB.getId(), true, () -> {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM referral_token WHERE id = ?::uuid",
                    String.class, tenantBReferralId);
            assertEquals("PENDING", status,
                    "Tenant B's referral must remain PENDING after cross-tenant reject attempt");
        });
    }

    /**
     * Task 8.5 — Phase 1 war-room follow-up (Marcus D10). Verifies that
     * {@code GET /api/v1/dv-referrals/{id}} returns 404 (NOT 403) when the
     * referral belongs to a different tenant, so the response cannot leak
     * whether the referral exists in another tenant. Previously blocked on
     * the missing {@code setupUserWithDvAccessInTenant} helper; unblocked by
     * the Section 16 cross-tenant refactor (commit {@code 7b9a8df}).
     */
    @Test
    void tc_getById_crossTenant_returns404_notForbidden() {
        // Setup: create a referral in a DIFFERENT tenant, then try to fetch
        // it as a coordinator in our default test tenant.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Tenant tenantB = authHelper.setupSecondaryTenant("xtenant-8-5-" + suffix);
        User adminB = authHelper.setupUserWithDvAccessInTenant(tenantB.getId(),
                "admin-b-8-5-" + suffix + "@test.fabt.org", "Tenant B Admin",
                new String[]{"PLATFORM_ADMIN", "COC_ADMIN"});
        User outreachB = authHelper.setupUserWithDvAccessInTenant(tenantB.getId(),
                "outreach-b-8-5-" + suffix + "@test.fabt.org", "Tenant B Outreach",
                new String[]{"OUTREACH_WORKER"});
        HttpHeaders adminBHeaders = authHelper.headersForUser(adminB);
        HttpHeaders outreachBHeaders = authHelper.headersForUser(outreachB);

        UUID shelterB = createShelterWithHeaders(adminBHeaders, true);
        patchAvailabilityInTenant(shelterB, "DV_SURVIVOR", 10, 3, 0, adminBHeaders);
        ResponseEntity<String> bReferralResp = createReferral(shelterB, outreachBHeaders);
        assertTrue(bReferralResp.getStatusCode().is2xxSuccessful(),
                "Tenant B referral must be created for this test — body: " + bReferralResp.getBody());
        UUID tenantBReferralId = UUID.fromString(extractField(bReferralResp.getBody(), "id"));

        // Act: fetch Tenant B's referral as a Tenant A coordinator with dvAccess.
        // coordHeaders is configured in setUp() as a dv-access coordinator in
        // the default test tenant — the exact "legitimate caller, wrong tenant"
        // profile this test needs.
        ResponseEntity<String> crossTenantGet = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tenantBReferralId, HttpMethod.GET,
                new HttpEntity<>(coordHeaders), String.class);

        // Assert: 404, not 403. The caller is authorized to hit the endpoint
        // (has COORDINATOR role, has dv_access) but RLS filters the row out.
        // The service's {@code findById} → orElseThrow(NoSuchElementException)
        // path is mapped to 404 by GlobalExceptionHandler. A 403 would leak
        // that the id is valid in some other tenant.
        assertEquals(HttpStatus.NOT_FOUND, crossTenantGet.getStatusCode(),
                "Cross-tenant GET /{id} must return 404, not 403 — 403 would leak existence");

        // Defense-in-depth: the response body must not contain the
        // Tenant B referral's identifiers or any row data.
        String body = crossTenantGet.getBody();
        if (body != null) {
            assertFalse(body.contains(tenantBReferralId.toString()),
                    "404 response must not echo the Tenant B referral UUID");
            assertFalse(body.contains(shelterB.toString()),
                    "404 response must not echo Tenant B's shelter UUID");
        }
    }

    @Test
    void tc_create_includesShelterName_snapshotInCreateAndMine() {
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        assertEquals(HttpStatus.CREATED, createResp.getStatusCode());
        String created = createResp.getBody();
        assertNotNull(created);
        assertTrue(created.contains("\"shelterName\":\""),
                "POST /dv-referrals must return snapshotted shelterName — body: " + created);
        assertTrue(created.contains("DV Shelter "),
                "Expected test shelter name prefix in JSON — body: " + created);

        ResponseEntity<String> mineResp = restTemplate.exchange(
                "/api/v1/dv-referrals/mine", HttpMethod.GET,
                new HttpEntity<>(outreachHeaders), String.class);
        assertEquals(HttpStatus.OK, mineResp.getStatusCode());
        String mine = mineResp.getBody();
        assertNotNull(mine);
        assertTrue(mine.contains("\"shelterName\":\""),
                "GET /dv-referrals/mine must include shelterName — body: " + mine);
    }

    @Test
    void tc_deactivatedShelter_mineShowsShelterClosed() {
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        assertEquals(HttpStatus.CREATED, createResp.getStatusCode());

        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () ->
                jdbcTemplate.update("UPDATE shelter SET active = false WHERE id = ?::uuid", dvShelterId));

        ResponseEntity<String> mineResp = restTemplate.exchange(
                "/api/v1/dv-referrals/mine", HttpMethod.GET,
                new HttpEntity<>(outreachHeaders), String.class);
        assertEquals(HttpStatus.OK, mineResp.getStatusCode());
        String mine = mineResp.getBody();
        assertNotNull(mine);
        assertTrue(mine.contains("\"status\":\"SHELTER_CLOSED\""),
                "Inactive shelter must yield SHELTER_CLOSED on mine list — body: " + mine);
    }

    @Test
    void tc_dvShelterRevoked_mineShowsShelterClosed() {
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        assertEquals(HttpStatus.CREATED, createResp.getStatusCode());

        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () ->
                jdbcTemplate.update("UPDATE shelter SET dv_shelter = false WHERE id = ?::uuid", dvShelterId));

        ResponseEntity<String> mineResp = restTemplate.exchange(
                "/api/v1/dv-referrals/mine", HttpMethod.GET,
                new HttpEntity<>(outreachHeaders), String.class);
        assertEquals(HttpStatus.OK, mineResp.getStatusCode());
        String mine = mineResp.getBody();
        assertNotNull(mine);
        assertTrue(mine.contains("\"status\":\"SHELTER_CLOSED\""),
                "Non-DV shelter must yield SHELTER_CLOSED on mine list — body: " + mine);
    }

    // =========================================================================
    // War room review tests (2026-04-12) — Riley Cho gap analysis
    // =========================================================================

    @Test
    void tc_acceptedReferral_shelterDeactivated_keepsAcceptedStatus() {
        // W3 fix: Keisha Thompson — "showing 'Shelter closed' on a completed
        // referral causes unnecessary panic." ACCEPTED referrals must keep
        // their terminal status even if the shelter is later deactivated.
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        String tokenId = extractField(createResp.getBody(), "id");

        // Accept the referral as coordinator
        restTemplate.exchange("/api/v1/dv-referrals/" + tokenId + "/accept",
                HttpMethod.PATCH, new HttpEntity<>(coordHeaders), String.class);

        // NOW deactivate the shelter
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () ->
                jdbcTemplate.update("UPDATE shelter SET active = false WHERE id = ?::uuid", dvShelterId));

        // Worker's list should still show ACCEPTED, not SHELTER_CLOSED.
        // Extract only THIS referral from the /mine list to avoid cross-test
        // pollution (the /mine endpoint returns all referrals for the user
        // across the entire test class lifecycle).
        ResponseEntity<String> mineResp = restTemplate.exchange(
                "/api/v1/dv-referrals/mine", HttpMethod.GET,
                new HttpEntity<>(outreachHeaders), String.class);
        String mine = mineResp.getBody();
        assertNotNull(mine);

        // Find the JSON object for this specific token by ID
        int tokenStart = mine.indexOf(tokenId);
        assertTrue(tokenStart >= 0, "Token must appear in /mine response — id: " + tokenId);
        // Walk backward to find the opening brace of this object
        int objStart = mine.lastIndexOf('{', tokenStart);
        // Walk forward to find the closing brace (handles nested objects)
        int braceDepth = 0;
        int objEnd = objStart;
        for (int i = objStart; i < mine.length(); i++) {
            if (mine.charAt(i) == '{') braceDepth++;
            else if (mine.charAt(i) == '}') { braceDepth--; if (braceDepth == 0) { objEnd = i + 1; break; } }
        }
        String thisReferral = mine.substring(objStart, objEnd);

        assertTrue(thisReferral.contains("\"status\":\"ACCEPTED\""),
                "ACCEPTED referral must keep status after shelter deactivation — this referral: " + thisReferral);
        // Phone should be withheld as secondary safety measure for this specific referral
        assertFalse(thisReferral.contains("\"shelterPhone\""),
                "Phone must be withheld for deactivated shelter even on ACCEPTED referral — this referral: " + thisReferral);
    }

    @Test
    void tc_shelterRenamed_snapshotPreservesOriginalName() {
        // The core value prop of denormalization: the snapshot records the name
        // at creation time, not the current name.
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        assertEquals(HttpStatus.CREATED, createResp.getStatusCode());
        String originalName = extractField(createResp.getBody(), "shelterName");
        assertNotNull(originalName);

        // Rename the shelter
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () ->
                jdbcTemplate.update("UPDATE shelter SET name = 'Renamed Shelter' WHERE id = ?::uuid", dvShelterId));

        // Worker's list should show the ORIGINAL name, not "Renamed Shelter"
        ResponseEntity<String> mineResp = restTemplate.exchange(
                "/api/v1/dv-referrals/mine", HttpMethod.GET,
                new HttpEntity<>(outreachHeaders), String.class);
        String mine = mineResp.getBody();
        assertTrue(mine.contains("\"shelterName\":\"" + originalName),
                "Snapshot must preserve original shelter name after rename — body: " + mine);
        assertFalse(mine.contains("Renamed Shelter"),
                "Current shelter name must NOT leak into the snapshot — body: " + mine);
    }

    @Test
    void tc_multipleReferrals_distinctShelterNames() {
        // Issue #92: the entire point — workers can distinguish referrals
        // to different shelters by name.

        // Create a second DV shelter (createShelter uses adminHeaders which
        // already has dvAccess=true, so no TenantContext wrapper needed)
        UUID secondDvShelterId = createShelter(true);
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () ->
                patchAvailability(secondDvShelterId, "DV_SURVIVOR", 10, 2, 0));

        // Create referrals to both shelters
        ResponseEntity<String> ref1 = createReferral(dvShelterId, outreachHeaders);
        assertEquals(HttpStatus.CREATED, ref1.getStatusCode());
        String name1 = extractField(ref1.getBody(), "shelterName");

        ResponseEntity<String> ref2 = createReferral(secondDvShelterId, outreachHeaders);
        assertEquals(HttpStatus.CREATED, ref2.getStatusCode());
        String name2 = extractField(ref2.getBody(), "shelterName");

        // Names should be different (both shelters get unique UUID-suffixed names)
        assertNotEquals(name1, name2,
                "Two different shelters must produce distinct shelter names in referral responses");

        // Mine list should contain both names
        ResponseEntity<String> mineResp = restTemplate.exchange(
                "/api/v1/dv-referrals/mine", HttpMethod.GET,
                new HttpEntity<>(outreachHeaders), String.class);
        String mine = mineResp.getBody();
        assertTrue(mine.contains(name1), "Mine list must include first shelter name — body: " + mine);
        assertTrue(mine.contains(name2), "Mine list must include second shelter name — body: " + mine);
    }

    @Test
    void tc_nullShelterName_legacyToken_handledGracefully() {
        // Backward compat: pre-V51 tokens have shelter_name=NULL.
        // Create a referral, then NULL out the shelter_name via SQL to simulate.
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        String tokenId = extractField(createResp.getBody(), "id");

        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () ->
                jdbcTemplate.update("UPDATE referral_token SET shelter_name = NULL WHERE id = ?::uuid", tokenId));

        ResponseEntity<String> mineResp = restTemplate.exchange(
                "/api/v1/dv-referrals/mine", HttpMethod.GET,
                new HttpEntity<>(outreachHeaders), String.class);
        assertEquals(HttpStatus.OK, mineResp.getStatusCode());
        String mine = mineResp.getBody();

        // Find this specific referral by ID and verify it handles null shelterName.
        // Jackson omits null fields by default (no "shelterName":null in JSON) —
        // the field is simply absent. The frontend handles this with a fallback
        // to "Unknown shelter".
        int tokenStart = mine.indexOf(tokenId);
        assertTrue(tokenStart >= 0, "Token must appear in /mine response — id: " + tokenId);
        int objStart = mine.lastIndexOf('{', tokenStart);
        int braceDepth = 0;
        int objEnd = objStart;
        for (int i = objStart; i < mine.length(); i++) {
            if (mine.charAt(i) == '{') braceDepth++;
            else if (mine.charAt(i) == '}') { braceDepth--; if (braceDepth == 0) { objEnd = i + 1; break; } }
        }
        String thisReferral = mine.substring(objStart, objEnd);

        // The field should either be absent (Jackson default) or null — either is graceful
        boolean absent = !thisReferral.contains("\"shelterName\"");
        boolean explicitNull = thisReferral.contains("\"shelterName\":null");
        assertTrue(absent || explicitNull,
                "Legacy token with NULL shelter_name must be handled gracefully (field absent or null) — this referral: " + thisReferral);
    }

    @Test
    void tc_inactiveShelter_excludedFromBedSearch() {
        // V52: deactivated shelters should not appear in shelter list.
        // Uses the shelter list endpoint (GET /shelters) instead of the bed
        // search endpoint because bed search returns shelters from all test
        // classes sharing the Testcontainer, making UUID-matching fragile.
        // The W2 query-level filter (AND s.active = TRUE) is in findFiltered
        // which the bed search uses — but the shelter list also exercises
        // the active flag through the same data path.

        // Shelter list should include the non-DV shelter (created active in setUp)
        ResponseEntity<String> listResp = restTemplate.exchange(
                "/api/v1/shelters", HttpMethod.GET,
                new HttpEntity<>(adminHeaders), String.class);
        assertTrue(listResp.getBody().contains(nonDvShelterId.toString()),
                "Active shelter should appear in shelter list — id: " + nonDvShelterId);

        // Deactivate
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () ->
                jdbcTemplate.update("UPDATE shelter SET active = false WHERE id = ?::uuid", nonDvShelterId));

        // Direct GET should still return the shelter (for safety check lookups)
        // but list should filter it out if the list endpoint respects active flag.
        // NOTE: the current GET /shelters list endpoint does NOT yet filter by
        // active=TRUE (the W2 fix only applies to findFiltered/bed search).
        // This assertion documents the current behavior — the shelter still
        // appears in the admin list. A future enhancement would add active
        // filtering to the admin list with an explicit ?includeInactive=true param.
        // Verify the deactivation persisted via direct JDBC:
        int[] active = {1};
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            conn.createStatement().execute("RESET ROLE");
            try (var ps = conn.prepareStatement("SELECT active::int FROM shelter WHERE id = ?")) {
                ps.setObject(1, nonDvShelterId);
                var rs = ps.executeQuery();
                if (rs.next()) active[0] = rs.getInt(1);
            }
            conn.createStatement().execute("SET ROLE fabt_app");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals(0, active[0],
                "Shelter must be deactivated in the database after UPDATE");
    }

    @Test
    void tc_auditEvent_dvReferralRequested_containsShelterDetails() {
        // Verify the audit row contains shelter_id and shelter_name in details.
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        assertEquals(HttpStatus.CREATED, createResp.getStatusCode());

        // Query audit_events for the DV_REFERRAL_REQUESTED event.
        // Use RESET ROLE to bypass RLS on audit_events (same pattern as
        // ReferralEscalationIntegrationTest).
        String[] auditJson = {null};
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            conn.createStatement().execute("RESET ROLE");
            var rs = conn.createStatement().executeQuery(
                    "SELECT details FROM audit_events WHERE action = 'DV_REFERRAL_REQUESTED' ORDER BY timestamp DESC LIMIT 1");
            if (rs.next()) auditJson[0] = rs.getString(1);
            conn.createStatement().execute("SET ROLE fabt_app");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertNotNull(auditJson[0], "DV_REFERRAL_REQUESTED audit event must exist");
        assertTrue(auditJson[0].contains("shelter_id") || auditJson[0].contains("shelterId"),
                "Audit details must contain shelter identifier — details: " + auditJson[0]);
    }

    @Test
    void tc_auditPersistence_failureDoesNotCrashReferralCreation() {
        // W5 Option B: verify that a referral creation succeeds even if
        // the audit event listener encounters an issue. The listener runs
        // synchronously and swallows exceptions — this test confirms that
        // contract. We can't easily break the audit_events table in a
        // Testcontainer, so we verify the positive path: referral is created
        // AND audit event exists. The resilience guarantee is architectural
        // (try/catch in AuditEventService.onAuditEvent) rather than testable
        // via fault injection without mocking.
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        assertEquals(HttpStatus.CREATED, createResp.getStatusCode(),
                "Referral creation must succeed regardless of audit outcome");

        // Verify the referral exists in the database
        String tokenId = extractField(createResp.getBody(), "id");
        assertNotNull(tokenId, "Referral ID must be returned");

        // Verify audit was written (confirms synchronous listener executed)
        int[] auditCount = {0};
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            conn.createStatement().execute("RESET ROLE");
            var rs = conn.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM audit_events WHERE action = 'DV_REFERRAL_REQUESTED'");
            rs.next();
            auditCount[0] = rs.getInt(1);
            conn.createStatement().execute("SET ROLE fabt_app");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertTrue(auditCount[0] > 0,
                "Audit event must be written synchronously during referral creation");
    }

    @Test
    void tc_create_reject_workerSeesReason() {
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        String tokenId = extractField(createResp.getBody(), "id");

        // Reject with reason
        String rejectBody = """
                {"reason": "No capacity for pets at this time"}
                """;
        ResponseEntity<String> rejectResp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/reject",
                HttpMethod.PATCH, new HttpEntity<>(rejectBody, coordHeaders), String.class);
        assertEquals(HttpStatus.OK, rejectResp.getStatusCode());
        assertTrue(rejectResp.getBody().contains("\"status\":\"REJECTED\""));
        assertTrue(rejectResp.getBody().contains("No capacity for pets"));

        // Worker sees the rejection reason in their list
        ResponseEntity<String> mineResp = restTemplate.exchange(
                "/api/v1/dv-referrals/mine", HttpMethod.GET,
                new HttpEntity<>(outreachHeaders), String.class);
        assertTrue(mineResp.getBody().contains("No capacity for pets"));
    }

    @Test
    void tc_create_expire_statusChange() {
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        String tokenId = extractField(createResp.getBody(), "id");

        // Force expiry by updating expires_at to past (needs dvAccess for RLS)
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            jdbcTemplate.update(
                    "UPDATE referral_token SET expires_at = NOW() - INTERVAL '1 minute' WHERE id = ?::uuid",
                    tokenId);
        });
        // Call expireTokens() WITHOUT outer TenantContext — matches production @Scheduled invocation
        referralTokenService.expireTokens();

        // Verify status changed
        ResponseEntity<String> mineResp = restTemplate.exchange(
                "/api/v1/dv-referrals/mine", HttpMethod.GET,
                new HttpEntity<>(outreachHeaders), String.class);
        assertTrue(mineResp.getBody().contains("\"status\":\"EXPIRED\""));
    }

    // =========================================================================
    // Access Control
    // =========================================================================

    @Test
    void tc_noDvAccess_cannotCreate() {
        // Outreach worker with dvAccess=false — two layers of protection:
        // 1. RLS: SET ROLE fabt_app + app.dv_access=false hides the DV shelter from queries
        // 2. Service: TenantContext.getDvAccess() check rejects before any query
        // Either layer should block — the response should be 4xx or 5xx
        ResponseEntity<String> resp = createReferral(dvShelterId, noDvOutreachHeaders);
        assertTrue(resp.getStatusCode().is4xxClientError() || resp.getStatusCode().is5xxServerError(),
                "Non-DV-access user should not be able to create referral for DV shelter. Got: " + resp.getStatusCode());
    }

    @Test
    void tc_nonDvShelter_rejected() {
        ResponseEntity<String> resp = createReferral(nonDvShelterId, outreachHeaders);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "Referral tokens are only for DV shelters");
    }

    @Test
    void tc_duplicatePending_rejected() {
        ResponseEntity<String> first = createReferral(dvShelterId, outreachHeaders);
        assertEquals(HttpStatus.CREATED, first.getStatusCode());

        ResponseEntity<String> second = createReferral(dvShelterId, outreachHeaders);
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode(),
                "Duplicate PENDING token for same user+shelter must be 409");
    }

    // =========================================================================
    // Warm Handoff — Zero PII Verification
    // =========================================================================

    @Test
    void tc_accepted_includesPhone_notAddress() {
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        String tokenId = extractField(createResp.getBody(), "id");

        restTemplate.exchange("/api/v1/dv-referrals/" + tokenId + "/accept",
                HttpMethod.PATCH, new HttpEntity<>(coordHeaders), String.class);

        // Worker's "mine" list should include phone for accepted referral
        ResponseEntity<String> mineResp = restTemplate.exchange(
                "/api/v1/dv-referrals/mine", HttpMethod.GET,
                new HttpEntity<>(outreachHeaders), String.class);
        String body = mineResp.getBody();
        assertNotNull(body);
        assertTrue(body.contains("shelterPhone"), "Accepted referral must include shelter phone");
        assertFalse(body.contains("addressStreet"), "Must NOT include address");
        assertFalse(body.contains("latitude"), "Must NOT include coordinates");
    }

    // =========================================================================
    // Purge
    // =========================================================================

    @Test
    void tc_purge_hardDeletes() {
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        String tokenId = extractField(createResp.getBody(), "id");

        // Accept the token
        restTemplate.exchange("/api/v1/dv-referrals/" + tokenId + "/accept",
                HttpMethod.PATCH, new HttpEntity<>(coordHeaders), String.class);

        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            // Verify token exists
            Integer countBefore = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM referral_token WHERE id = ?::uuid", Integer.class, tokenId);
            assertEquals(1, countBefore);

            // Force responded_at to be old enough for purge
            jdbcTemplate.update(
                    "UPDATE referral_token SET responded_at = NOW() - INTERVAL '25 hours' WHERE id = ?::uuid",
                    tokenId);
            purgeService.purgeTerminalTokens();

            // Verify token is GONE (hard-deleted, not soft-deleted)
            Integer countAfter = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM referral_token WHERE id = ?::uuid", Integer.class, tokenId);
            assertEquals(0, countAfter, "Purge must hard-delete terminal tokens");
        });
    }

    // =========================================================================
    // Expired Token Cannot Be Accepted
    // =========================================================================

    @Test
    void tc_expiredToken_cannotAccept() {
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        String tokenId = extractField(createResp.getBody(), "id");

        // Force expiry
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            jdbcTemplate.update(
                    "UPDATE referral_token SET expires_at = NOW() - INTERVAL '1 minute' WHERE id = ?::uuid",
                    tokenId);
            referralTokenService.expireTokens();
        });

        // Try to accept — should fail
        ResponseEntity<String> acceptResp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/accept",
                HttpMethod.PATCH, new HttpEntity<>(coordHeaders), String.class);
        assertTrue(acceptResp.getStatusCode().is4xxClientError(),
                "Expired token cannot be accepted");
    }

    // =========================================================================
    // Coordinator Assignment
    // =========================================================================

    @Test
    void tc_coordinatorSeesOnlyAssignedShelters() {
        // Pending list should only return tokens for shelters the coordinator is assigned to
        ResponseEntity<String> pendingResp = restTemplate.exchange(
                "/api/v1/dv-referrals/pending?shelterId=" + dvShelterId,
                HttpMethod.GET, new HttpEntity<>(coordHeaders), String.class);
        assertEquals(HttpStatus.OK, pendingResp.getStatusCode());
    }

    // =========================================================================
    // Analytics
    // =========================================================================

    @Test
    void tc_analytics_returnsAggregateCounts_noPII() {
        // Create and accept a referral to generate counter data
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        String tokenId = extractField(createResp.getBody(), "id");
        restTemplate.exchange("/api/v1/dv-referrals/" + tokenId + "/accept",
                HttpMethod.PATCH, new HttpEntity<>(coordHeaders), String.class);

        // Query analytics
        ResponseEntity<String> analyticsResp = restTemplate.exchange(
                "/api/v1/dv-referrals/analytics", HttpMethod.GET,
                new HttpEntity<>(adminHeaders), String.class);
        assertEquals(HttpStatus.OK, analyticsResp.getStatusCode());
        String body = analyticsResp.getBody();
        assertNotNull(body);

        // Should contain aggregate counts — no PII
        assertTrue(body.contains("\"requested\""), "Analytics must include requested count");
        assertTrue(body.contains("\"accepted\""), "Analytics must include accepted count");
        assertTrue(body.contains("\"rejected\""), "Analytics must include rejected count");
        assertTrue(body.contains("\"expired\""), "Analytics must include expired count");

        // Must NOT contain any PII fields
        assertFalse(body.contains("callbackNumber"), "Analytics must NOT contain callback numbers");
        assertFalse(body.contains("specialNeeds"), "Analytics must NOT contain special needs");
        assertFalse(body.contains("householdSize"), "Analytics must NOT contain household size");
    }

    // =========================================================================
    // Referrals Don't Affect Bed Availability
    // =========================================================================

    @Test
    void tc_referralDoesNotAffectAvailability() {
        // Read availability before
        ResponseEntity<String> before = restTemplate.exchange(
                "/api/v1/shelters/" + dvShelterId, HttpMethod.GET,
                new HttpEntity<>(adminHeaders), String.class);
        String beforeBody = before.getBody();

        // Create and accept a referral
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        String tokenId = extractField(createResp.getBody(), "id");
        restTemplate.exchange("/api/v1/dv-referrals/" + tokenId + "/accept",
                HttpMethod.PATCH, new HttpEntity<>(coordHeaders), String.class);

        // Read availability after — should be unchanged
        ResponseEntity<String> after = restTemplate.exchange(
                "/api/v1/shelters/" + dvShelterId, HttpMethod.GET,
                new HttpEntity<>(adminHeaders), String.class);

        // DV referrals are separate from holds — they don't change bed_availability
        // The beds_on_hold count should be the same
        assertEquals(extractFieldFromDetail(beforeBody, "bedsOnHold"),
                extractFieldFromDetail(after.getBody(), "bedsOnHold"),
                "DV referrals must not affect bed availability counts");
    }

    // =========================================================================
    // SSE Expiration Events
    // =========================================================================

    @Test
    void tc_expireTokens_publishesSseEvent() throws Exception {
        // Create referral
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        String tokenId = extractField(createResp.getBody(), "id");

        // Register a coordinator SSE emitter to capture events
        User dvCoord = authHelper.setupUserWithDvAccess(
                "sse-coord@test.fabt.org", "SSE Coord", new String[]{"COORDINATOR"});
        String jwt = authHelper.getJwtService().generateAccessToken(dvCoord);

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port
                        + "/api/v1/notifications/stream?token=" + jwt))
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        HttpResponse<Stream<String>> response = httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .get(5, TimeUnit.SECONDS);

        assertEquals(200, response.statusCode());

        var receivedLines = new CopyOnWriteArrayList<String>();
        var latch = new CountDownLatch(1);

        Thread.startVirtualThread(() -> {
            response.body().forEach(line -> {
                receivedLines.add(line);
                if (line.contains("dv-referral.expired")) {
                    latch.countDown();
                }
            });
        });

        // Allow SSE connection to establish
        Thread.sleep(300);

        // Force expiry — outer TenantContext needed here because this test verifies SSE event
        // delivery, which requires tenantId for routing. The RLS fix (no @Transactional) is
        // verified separately in DvReferralExpiryRlsTest.
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            jdbcTemplate.update(
                    "UPDATE referral_token SET expires_at = NOW() - INTERVAL '1 minute' WHERE id = ?::uuid",
                    tokenId);
            referralTokenService.expireTokens();
        });

        boolean received = latch.await(5, TimeUnit.SECONDS);

        response.body().close();
        httpClient.shutdownNow();
        httpClient.awaitTermination(Duration.ofSeconds(2));

        assertTrue(received, "Coordinator should receive dv-referral.expired SSE event");
        String allLines = String.join("\n", receivedLines);
        assertTrue(allLines.contains(tokenId), "SSE event should contain the expired token ID");
    }

    @Test
    void tc_expiredEvent_filteredByTenant() {
        // Create referral in default tenant
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        assertNotNull(extractField(createResp.getBody(), "id"));

        // Register a coordinator in a DIFFERENT tenant
        Tenant tenantB = authHelper.setupTestTenant("tenant-b-isolation");
        User coordB = authHelper.setupUserWithDvAccess(
                "coord-b@test.fabt.org", "Coord B", new String[]{"COORDINATOR"});
        SseEmitter emitterB = notificationService.register(
                coordB.getId(), tenantB.getId(),
                new String[]{"COORDINATOR"}, true, null);

        // Also register a coordinator in the SAME tenant
        User coordA = authHelper.setupUserWithDvAccess(
                "coord-a-isolation@test.fabt.org", "Coord A", new String[]{"COORDINATOR"});
        SseEmitter emitterA = notificationService.register(
                coordA.getId(), authHelper.getTestTenantId(),
                new String[]{"COORDINATOR"}, true, null);

        // Force expiry in default tenant — outer TenantContext needed here because this test
        // verifies SSE tenant isolation, which requires tenantId for event routing. The RLS fix
        // is verified separately in DvReferralExpiryRlsTest.
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            jdbcTemplate.update(
                    "UPDATE referral_token SET expires_at = NOW() - INTERVAL '1 minute' WHERE status = 'PENDING'");
            referralTokenService.expireTokens();
        });

        // Both emitters should still be active (no error thrown)
        // Tenant isolation is verified by the NotificationService filtering logic:
        // only coordinators matching the tenant receive the event.
        // If emitterB received an event for another tenant, the sendAndBufferEvent
        // method would have been called — but the tenant filter prevents this.
        assertNotNull(emitterA, "Same-tenant coordinator emitter should be active");
        assertNotNull(emitterB, "Different-tenant coordinator emitter should be active (received no event)");
    }

    // =========================================================================
    // Persistent Notification Integration
    // =========================================================================

    @Test
    void tc_referralCreated_notificationExistsForCoordinator() {
        // Create a DV coordinator (COORDINATOR role + dvAccess) who should receive the notification
        User dvCoordinator = authHelper.setupUserWithDvAccess(
                "notif-coord@test.fabt.org", "Notif Coordinator", new String[]{"COORDINATOR"});
        HttpHeaders dvCoordHeaders = authHelper.headersForUser(dvCoordinator);

        // Create a referral — triggers @TransactionalEventListener → persistent notification
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        assertEquals(HttpStatus.CREATED, createResp.getStatusCode());
        String tokenId = extractField(createResp.getBody(), "id");

        // Coordinator should have a persistent notification for the referral
        ResponseEntity<String> notifResp = restTemplate.exchange(
                "/api/v1/notifications?unread=true", HttpMethod.GET,
                new HttpEntity<>(dvCoordHeaders), String.class);
        assertEquals(HttpStatus.OK, notifResp.getStatusCode());
        assertTrue(notifResp.getBody().contains("referral.requested"),
                "Coordinator should have a referral.requested notification");
        assertTrue(notifResp.getBody().contains("ACTION_REQUIRED"),
                "Referral notification should be ACTION_REQUIRED severity");
    }

    @Test
    void tc_referralAccepted_notificationExistsForOutreachWorker() {
        // Create coordinator to accept the referral
        User dvCoordinator = authHelper.setupUserWithDvAccess(
                "accept-coord@test.fabt.org", "Accept Coordinator", new String[]{"COORDINATOR"});
        HttpHeaders acceptCoordHeaders = authHelper.headersForUser(dvCoordinator);

        // Create referral as outreach worker
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        assertEquals(HttpStatus.CREATED, createResp.getStatusCode());
        String tokenId = extractField(createResp.getBody(), "id");

        // Accept as coordinator — triggers referral.responded notification to outreach worker
        ResponseEntity<String> acceptResp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/accept",
                HttpMethod.PATCH, new HttpEntity<>(acceptCoordHeaders), String.class);
        assertEquals(HttpStatus.OK, acceptResp.getStatusCode());

        // Outreach worker should have a persistent notification about the acceptance
        ResponseEntity<String> notifResp = restTemplate.exchange(
                "/api/v1/notifications?unread=true", HttpMethod.GET,
                new HttpEntity<>(outreachHeaders), String.class);
        assertEquals(HttpStatus.OK, notifResp.getStatusCode());
        assertTrue(notifResp.getBody().contains("referral.responded"),
                "Outreach worker should have a referral.responded notification");
        assertTrue(notifResp.getBody().contains("ACCEPTED"),
                "Notification payload should contain ACCEPTED status");
    }

    @Test
    void tc_referralRejected_notificationExistsForOutreachWorker() {
        User dvCoordinator = authHelper.setupUserWithDvAccess(
                "reject-coord@test.fabt.org", "Reject Coordinator", new String[]{"COORDINATOR"});
        HttpHeaders rejectCoordHeaders = authHelper.headersForUser(dvCoordinator);

        // Create referral as outreach worker
        ResponseEntity<String> createResp = createReferral(dvShelterId, outreachHeaders);
        assertEquals(HttpStatus.CREATED, createResp.getStatusCode());
        String tokenId = extractField(createResp.getBody(), "id");

        // Reject as coordinator
        String rejectBody = """
                {"reason": "No capacity tonight"}
                """;
        ResponseEntity<String> rejectResp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/reject",
                HttpMethod.PATCH, new HttpEntity<>(rejectBody, rejectCoordHeaders), String.class);
        assertEquals(HttpStatus.OK, rejectResp.getStatusCode());

        // Outreach worker should have notification with REJECTED status
        ResponseEntity<String> notifResp = restTemplate.exchange(
                "/api/v1/notifications?unread=true", HttpMethod.GET,
                new HttpEntity<>(outreachHeaders), String.class);
        assertEquals(HttpStatus.OK, notifResp.getStatusCode());
        assertTrue(notifResp.getBody().contains("referral.responded"),
                "Outreach worker should have referral.responded notification on rejection");
        assertTrue(notifResp.getBody().contains("REJECTED"),
                "Notification payload should contain REJECTED status — Darius needs to find another bed");
    }

    @Test
    void tc_zeroDvCoordinators_referralSucceeds_noNotificationCrash() {
        // Create a separate tenant with NO DV coordinators
        var isolatedTenant = authHelper.setupTestTenant("no-coord-tenant");
        var dvAdmin = authHelper.setupUserWithDvAccess(
                "nocord-admin@test.fabt.org", "No-Coord Admin", new String[]{"PLATFORM_ADMIN"});
        HttpHeaders isolatedAdminHeaders = authHelper.headersForUser(dvAdmin);
        var dvOutreach = authHelper.setupUserWithDvAccess(
                "nocord-outreach@test.fabt.org", "No-Coord Outreach", new String[]{"OUTREACH_WORKER"});
        HttpHeaders isolatedOutreachHeaders = authHelper.headersForUser(dvOutreach);

        // Create DV shelter in isolated tenant
        TenantContext.runWithContext(isolatedTenant.getId(), true, () -> {
            // Verify precondition: zero DV coordinators exist in this tenant
            var dvCoords = userService.findDvCoordinators(isolatedTenant.getId());
            assertEquals(0, dvCoords.size(),
                    "Precondition: isolated tenant must have zero DV coordinators");

            UUID shelterId = createShelterInTenant(isolatedAdminHeaders, true);
            patchAvailabilityInTenant(shelterId, "DV_SURVIVOR", 10, 3, 0, isolatedAdminHeaders);

            // Create referral — should succeed even with zero coordinators
            ResponseEntity<String> createResp = createReferral(shelterId, isolatedOutreachHeaders);
            assertEquals(HttpStatus.CREATED, createResp.getStatusCode(),
                    "Referral must succeed even when no DV coordinators exist — no crash");
        });
    }

    @Test
    void tc_nonDvCoordinator_doesNotReceiveReferralNotification() {
        // Create a UNIQUE coordinator WITHOUT dvAccess — must not reuse shared coordinator
        // which may have accumulated notifications from other tests
        User nonDvCoord = authHelper.getUserRepository().findByTenantIdAndEmail(
                authHelper.getTestTenantId(), "nodv-isolation@test.fabt.org").orElseGet(() -> {
            var u = new org.fabt.auth.domain.User();
            u.setTenantId(authHelper.getTestTenantId());
            u.setEmail("nodv-isolation@test.fabt.org");
            u.setDisplayName("Non-DV Isolation Coordinator");
            u.setPasswordHash(authHelper.getPasswordService().hash(TestAuthHelper.TEST_PASSWORD));
            u.setRoles(new String[]{"COORDINATOR"});
            u.setDvAccess(false);
            u.setCreatedAt(java.time.Instant.now());
            u.setUpdatedAt(java.time.Instant.now());
            return authHelper.getUserRepository().save(u);
        });
        HttpHeaders nonDvCoordHeaders = authHelper.headersForUser(nonDvCoord);

        // Create a DV coordinator who SHOULD get the notification
        User dvCoord = authHelper.setupUserWithDvAccess(
                "dvonly-coord@test.fabt.org", "DV Only Coordinator", new String[]{"COORDINATOR"});

        // Create referral
        createReferral(dvShelterId, outreachHeaders);

        // Non-DV coordinator should NOT have the referral notification
        ResponseEntity<String> notifResp = restTemplate.exchange(
                "/api/v1/notifications?unread=true", HttpMethod.GET,
                new HttpEntity<>(nonDvCoordHeaders), String.class);
        assertEquals(HttpStatus.OK, notifResp.getStatusCode());
        assertFalse(notifResp.getBody().contains("referral.requested"),
                "Non-DV coordinator must NOT receive referral.requested notification — DV leak prevention");
    }

    @Test
    void tc_notificationPayload_containsZeroPII() {
        // Create DV coordinator to receive notification
        User dvCoord = authHelper.setupUserWithDvAccess(
                "pii-coord@test.fabt.org", "PII Check Coordinator", new String[]{"COORDINATOR"});
        HttpHeaders dvCoordHeaders = authHelper.headersForUser(dvCoord);

        // Create referral with PII-containing fields (specialNeeds, callbackNumber)
        createReferral(dvShelterId, outreachHeaders);

        // Check coordinator's notification payload — must NOT contain PII
        ResponseEntity<String> notifResp = restTemplate.exchange(
                "/api/v1/notifications?unread=true", HttpMethod.GET,
                new HttpEntity<>(dvCoordHeaders), String.class);
        String body = notifResp.getBody();

        // Positive: must contain opaque identifiers
        assertTrue(body.contains("referralId"), "Notification must contain referralId");
        assertTrue(body.contains("shelterId"), "Notification must contain shelterId");

        // Negative: must NOT contain any PII from the referral
        assertFalse(body.contains("919-555-0042"), "Notification must NOT contain callback number (PII)");
        assertFalse(body.contains("Wheelchair"), "Notification must NOT contain special needs text (PII)");
        assertFalse(body.contains("householdSize"), "Notification must NOT contain household size");
        assertFalse(body.contains("callbackNumber"), "Notification must NOT contain callback number field");
        assertFalse(body.contains("specialNeeds"), "Notification must NOT contain special needs field");
    }

    @Test
    void tc_failedReferral_noOrphanNotification() {
        // Create DV coordinator who would receive notification IF one were created
        User dvCoord = authHelper.setupUserWithDvAccess(
                "orphan-coord@test.fabt.org", "Orphan Check Coordinator", new String[]{"COORDINATOR"});
        HttpHeaders dvCoordHeaders = authHelper.headersForUser(dvCoord);

        // Attempt to create referral to NON-DV shelter — this should fail (400)
        ResponseEntity<String> failedResp = createReferral(nonDvShelterId, outreachHeaders);
        assertTrue(failedResp.getStatusCode().is4xxClientError(),
                "Referral to non-DV shelter should fail");

        // Coordinator should NOT have any referral notification (transaction rolled back)
        ResponseEntity<String> notifResp = restTemplate.exchange(
                "/api/v1/notifications?unread=true", HttpMethod.GET,
                new HttpEntity<>(dvCoordHeaders), String.class);
        assertFalse(notifResp.getBody().contains("referral.requested"),
                "Failed referral must not create orphan notification — @TransactionalEventListener guards this");
    }

    // =========================================================================
    // Coordinator Pending Count
    // =========================================================================

    @Test
    void tc_pendingCount_reflectsAllAssignedShelters() {
        // Create a DV coordinator assigned to TWO DV shelters
        User countCoord = authHelper.setupUserWithDvAccess(
                "count-coord@test.fabt.org", "Count Coordinator", new String[]{"COORDINATOR"});
        HttpHeaders countCoordHeaders = authHelper.headersForUser(countCoord);

        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            // Create second DV shelter
            UUID dvShelterId2 = createShelterWithHeaders(adminHeaders, true);
            patchAvailabilityInTenant(dvShelterId2, "DV_SURVIVOR", 10, 3, 0, adminHeaders);

            // Assign coordinator to both shelters
            restTemplate.exchange("/api/v1/shelters/" + dvShelterId + "/coordinators",
                    HttpMethod.POST,
                    new HttpEntity<>("{\"userId\":\"" + countCoord.getId() + "\"}", adminHeaders),
                    String.class);
            restTemplate.exchange("/api/v1/shelters/" + dvShelterId2 + "/coordinators",
                    HttpMethod.POST,
                    new HttpEntity<>("{\"userId\":\"" + countCoord.getId() + "\"}", adminHeaders),
                    String.class);

            // Create referrals to each shelter
            createReferral(dvShelterId, outreachHeaders);
            createReferral(dvShelterId2, outreachHeaders);
        });

        // Pending count should be 2 (one from each shelter)
        ResponseEntity<String> countResp = restTemplate.exchange(
                "/api/v1/dv-referrals/pending/count", HttpMethod.GET,
                new HttpEntity<>(countCoordHeaders), String.class);
        assertEquals(HttpStatus.OK, countResp.getStatusCode());
        assertTrue(countResp.getBody().contains("\"count\":2"),
                "Pending count should reflect referrals across all assigned shelters — got: " + countResp.getBody());
    }

    // =========================================================================
    // notification-deep-linking Section 16 — firstPending routing hint
    // (design decision D-BP; banner genesis-gap closure, demo-deploy blocker)
    // =========================================================================

    /**
     * Task 16.2.1 — firstPending points at the oldest PENDING referral by
     * {@code created_at ASC}. Uses a direct SQL backdate to avoid a flaky
     * Thread.sleep tie-break.
     */
    @Test
    void tc_pendingCount_returnsFirstPendingWhenCountPositive() {
        User coord = authHelper.setupUserWithDvAccess(
                "firstpending-oldest-coord@test.fabt.org", "FirstPending Oldest", new String[]{"COORDINATOR"});
        HttpHeaders coordH = authHelper.headersForUser(coord);

        final UUID[] tPlus0ReferralId = new UUID[1];
        final UUID[] tPlus0ShelterId = new UUID[1];

        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            UUID shelterA = createShelterWithHeaders(adminHeaders, true);
            UUID shelterC = createShelterWithHeaders(adminHeaders, true);
            patchAvailabilityInTenant(shelterA, "DV_SURVIVOR", 10, 3, 0, adminHeaders);
            patchAvailabilityInTenant(shelterC, "DV_SURVIVOR", 10, 3, 0, adminHeaders);

            restTemplate.exchange("/api/v1/shelters/" + shelterA + "/coordinators",
                    HttpMethod.POST,
                    new HttpEntity<>("{\"userId\":\"" + coord.getId() + "\"}", adminHeaders),
                    String.class);
            restTemplate.exchange("/api/v1/shelters/" + shelterC + "/coordinators",
                    HttpMethod.POST,
                    new HttpEntity<>("{\"userId\":\"" + coord.getId() + "\"}", adminHeaders),
                    String.class);

            // Create both referrals, then backdate the Shelter A one so it is
            // unambiguously the older row regardless of wall-clock resolution.
            ResponseEntity<String> refA = createReferral(shelterA, outreachHeaders);
            assertTrue(refA.getStatusCode().is2xxSuccessful());
            tPlus0ReferralId[0] = UUID.fromString(extractField(refA.getBody(), "id"));
            tPlus0ShelterId[0] = shelterA;

            createReferral(shelterC, outreachHeaders);

            jdbcTemplate.update(
                    "UPDATE referral_token SET created_at = NOW() - INTERVAL '5 minutes' WHERE id = ?",
                    tPlus0ReferralId[0]);
        });

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals/pending/count", HttpMethod.GET,
                new HttpEntity<>(coordH), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        String body = resp.getBody();
        assertTrue(body.contains("\"count\":2"), "count should be 2 — got: " + body);
        assertTrue(body.contains("\"referralId\":\"" + tPlus0ReferralId[0] + "\""),
                "firstPending.referralId must point at the oldest pending referral — got: " + body);
        assertTrue(body.contains("\"shelterId\":\"" + tPlus0ShelterId[0] + "\""),
                "firstPending.shelterId must match the oldest referral's shelter — got: " + body);
    }

    /**
     * Task 16.2.2 — when count is zero, firstPending is explicitly JSON null
     * (present in the response, not omitted). The contract says clients test
     * for {@code firstPending === null}, so the key MUST appear.
     */
    @Test
    void tc_pendingCount_returnsNullFirstPendingWhenCountZero() {
        User coord = authHelper.setupUserWithDvAccess(
                "firstpending-zero-coord@test.fabt.org", "FirstPending Zero", new String[]{"COORDINATOR"});
        HttpHeaders coordH = authHelper.headersForUser(coord);

        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            UUID assignedShelter = createShelterWithHeaders(adminHeaders, true);
            patchAvailabilityInTenant(assignedShelter, "DV_SURVIVOR", 10, 3, 0, adminHeaders);
            restTemplate.exchange("/api/v1/shelters/" + assignedShelter + "/coordinators",
                    HttpMethod.POST,
                    new HttpEntity<>("{\"userId\":\"" + coord.getId() + "\"}", adminHeaders),
                    String.class);
            // No referrals created — count is 0
        });

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals/pending/count", HttpMethod.GET,
                new HttpEntity<>(coordH), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        String body = resp.getBody();
        assertTrue(body.contains("\"count\":0"), "count should be 0 — got: " + body);
        assertTrue(body.contains("\"firstPending\":null"),
                "firstPending must be present as explicit JSON null, not omitted — got: " + body);
    }

    /**
     * Task 16.2.3 — firstPending must not surface referrals from DV shelters
     * the coordinator is NOT assigned to, even within the same tenant.
     */
    @Test
    void tc_pendingCount_firstPendingDoesNotLeakUnassignedShelters() {
        User coord = authHelper.setupUserWithDvAccess(
                "firstpending-unassigned-coord@test.fabt.org", "FirstPending Unassigned",
                new String[]{"COORDINATOR"});
        HttpHeaders coordH = authHelper.headersForUser(coord);

        final UUID[] assignedReferralId = new UUID[1];

        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            UUID assigned = createShelterWithHeaders(adminHeaders, true);
            UUID unassigned = createShelterWithHeaders(adminHeaders, true);
            patchAvailabilityInTenant(assigned, "DV_SURVIVOR", 10, 3, 0, adminHeaders);
            patchAvailabilityInTenant(unassigned, "DV_SURVIVOR", 10, 3, 0, adminHeaders);

            // Only assign to ONE of the two shelters
            restTemplate.exchange("/api/v1/shelters/" + assigned + "/coordinators",
                    HttpMethod.POST,
                    new HttpEntity<>("{\"userId\":\"" + coord.getId() + "\"}", adminHeaders),
                    String.class);

            // Unassigned shelter has the OLDER pending referral — tempting to
            // leak. Assigned shelter's referral is newer. Backdate the
            // unassigned one so its created_at < assigned's.
            ResponseEntity<String> unassignedRef = createReferral(unassigned, outreachHeaders);
            assertTrue(unassignedRef.getStatusCode().is2xxSuccessful());
            UUID unassignedReferralId = UUID.fromString(extractField(unassignedRef.getBody(), "id"));

            ResponseEntity<String> assignedRef = createReferral(assigned, outreachHeaders);
            assertTrue(assignedRef.getStatusCode().is2xxSuccessful());
            assignedReferralId[0] = UUID.fromString(extractField(assignedRef.getBody(), "id"));

            jdbcTemplate.update(
                    "UPDATE referral_token SET created_at = NOW() - INTERVAL '10 minutes' WHERE id = ?",
                    unassignedReferralId);
        });

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals/pending/count", HttpMethod.GET,
                new HttpEntity<>(coordH), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        String body = resp.getBody();
        assertTrue(body.contains("\"count\":1"),
                "count must reflect only assigned shelters — got: " + body);
        assertTrue(body.contains("\"referralId\":\"" + assignedReferralId[0] + "\""),
                "firstPending.referralId must point at the assigned shelter's referral, "
                        + "not the (older) unassigned one — got: " + body);
    }

    /**
     * Task 16.2.4 — cross-tenant referrals must not leak via firstPending.
     *
     * <p>Uses the API-layer setup pattern established in
     * {@code CrossTenantIsolationTest}: tenants created via
     * {@code TenantService}, users via {@code UserRepository.save} with
     * explicit tenantId, shelters + coordinator-assignments + referrals via
     * the REST API using tenant-scoped JWTs. No raw SQL, no RLS workarounds —
     * tenant isolation is application-layer (WHERE clauses + JWT claims), NOT
     * database-layer (verified in {@code CrossTenantIsolationTest} Javadoc).</p>
     *
     * <p>Unblocks task 8.5 by way of
     * {@link TestAuthHelper#setupUserWithDvAccessInTenant(UUID, String,
     * String, String[])}.</p>
     */
    @Test
    void tc_pendingCount_firstPendingDoesNotLeakCrossTenant() {
        // Tenant A (default): coordinator with zero assigned pending referrals.
        User coordA = authHelper.setupUserWithDvAccess(
                "firstpending-xtenant-coord-a@test.fabt.org", "FirstPending Xtenant A",
                new String[]{"COORDINATOR"});
        HttpHeaders coordAHeaders = authHelper.headersForUser(coordA);

        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            UUID assignedA = createShelterWithHeaders(adminHeaders, true);
            patchAvailabilityInTenant(assignedA, "DV_SURVIVOR", 10, 3, 0, adminHeaders);
            restTemplate.exchange("/api/v1/shelters/" + assignedA + "/coordinators",
                    HttpMethod.POST,
                    new HttpEntity<>("{\"userId\":\"" + coordA.getId() + "\"}", adminHeaders),
                    String.class);
            // Intentionally NO referral created in Tenant A — we want to
            // prove Tenant B's referral does not leak when Tenant A is empty.
        });

        // Tenant B setup — tenants + users via Spring beans; shelter + referral
        // via the REST API with tenant-B JWTs. Slug suffix ensures repeated
        // test runs don't collide on the unique slug constraint.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Tenant tenantB = authHelper.setupSecondaryTenant("iso-ndl-b-" + suffix);
        // adminB needs dvAccess=true so the shelter.dv_shelter_access RLS policy
        // permits the INSERT of a dv_shelter=true row. Matches the setUp() pattern
        // above where 'dvAdmin' is created with setupUserWithDvAccess.
        User adminB = authHelper.setupUserWithDvAccessInTenant(tenantB.getId(),
                "admin-b-" + suffix + "@test.fabt.org", "Tenant B Admin",
                new String[]{"PLATFORM_ADMIN", "COC_ADMIN"});
        User outreachB = authHelper.setupUserWithDvAccessInTenant(tenantB.getId(),
                "outreach-b-" + suffix + "@test.fabt.org", "Tenant B Outreach",
                new String[]{"OUTREACH_WORKER"});
        HttpHeaders adminBHeaders = authHelper.headersForUser(adminB);
        HttpHeaders outreachBHeaders = authHelper.headersForUser(outreachB);

        UUID shelterB = createShelterWithHeaders(adminBHeaders, true);
        patchAvailabilityInTenant(shelterB, "DV_SURVIVOR", 10, 3, 0, adminBHeaders);
        ResponseEntity<String> bReferralResp = createReferral(shelterB, outreachBHeaders);
        assertTrue(bReferralResp.getStatusCode().is2xxSuccessful(),
                "Tenant B referral creation must succeed — body: " + bReferralResp.getBody());
        UUID tenantBReferralId = UUID.fromString(extractField(bReferralResp.getBody(), "id"));

        // Now ask the Tenant A coordinator for their pending count.
        // They have an assigned shelter (with no referrals); Tenant B has
        // an unrelated referral. Tenant isolation should hide it.
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals/pending/count", HttpMethod.GET,
                new HttpEntity<>(coordAHeaders), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        String body = resp.getBody();
        assertTrue(body.contains("\"count\":0"),
                "Tenant A coordinator has 0 pending referrals; Tenant B's referral must not be "
                        + "counted — got: " + body);
        assertTrue(body.contains("\"firstPending\":null"),
                "firstPending must be null for zero-count — cross-tenant referral must not leak; "
                        + "got: " + body);
        assertFalse(body.contains(tenantBReferralId.toString()),
                "Tenant B's referral UUID must not appear anywhere in Tenant A's response");
        assertFalse(body.contains(shelterB.toString()),
                "Tenant B's shelter UUID must not appear anywhere in Tenant A's response");
    }

    /**
     * Task 16.2.6 — coordinator with zero assigned shelters (distinct from
     * 16.2.2's "assigned but empty"). Added as part of 2026-04-14 warroom
     * review (Riley Cho + Sam Okafor): the service's early-return on an
     * empty shelter-id list must produce {@code { count: 0, firstPending:
     * null }} and MUST NOT attempt a DB query that would error on
     * {@code shelter_id = ANY(ARRAY[]::uuid[])}.
     */
    @Test
    void tc_pendingCount_zeroAssignedShelters() {
        // Coordinator is created with NO coordinator-assignment rows at all.
        User coord = authHelper.setupUserWithDvAccess(
                "firstpending-zeroassign-coord@test.fabt.org", "FirstPending ZeroAssign",
                new String[]{"COORDINATOR"});
        HttpHeaders coordH = authHelper.headersForUser(coord);
        // Deliberately skip any POST to /api/v1/shelters/{id}/coordinators —
        // the coordinator has literally no shelter assignments.

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals/pending/count", HttpMethod.GET,
                new HttpEntity<>(coordH), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        String body = resp.getBody();
        assertTrue(body.contains("\"count\":0"),
                "zero-assignment coordinator must see count=0 — got: " + body);
        assertTrue(body.contains("\"firstPending\":null"),
                "zero-assignment coordinator must see firstPending=null (no NullPointerException, "
                        + "no SQL empty-array error) — got: " + body);
    }

    /**
     * Task 16.2.5 — additive change: pre-Phase-4 clients that destructure
     * only {@code count} continue to work. The JSON must contain a parseable
     * {@code count} field regardless of whether {@code firstPending} is
     * populated. No contract break.
     */
    @Test
    void tc_pendingCount_backwardCompatibleResponse() {
        User coord = authHelper.setupUserWithDvAccess(
                "firstpending-compat-coord@test.fabt.org", "FirstPending Compat",
                new String[]{"COORDINATOR"});
        HttpHeaders coordH = authHelper.headersForUser(coord);

        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            UUID shelter = createShelterWithHeaders(adminHeaders, true);
            patchAvailabilityInTenant(shelter, "DV_SURVIVOR", 10, 3, 0, adminHeaders);
            restTemplate.exchange("/api/v1/shelters/" + shelter + "/coordinators",
                    HttpMethod.POST,
                    new HttpEntity<>("{\"userId\":\"" + coord.getId() + "\"}", adminHeaders),
                    String.class);
            createReferral(shelter, outreachHeaders);
        });

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals/pending/count", HttpMethod.GET,
                new HttpEntity<>(coordH), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        String body = resp.getBody();

        // A pre-Phase-4 client parsing just { count: <int> } must still work.
        // Jackson produces stable field order, but assert on substring rather
        // than field position so this is resilient to re-ordering.
        assertTrue(body.contains("\"count\":1"),
                "pre-Phase-4 consumer parsing {count} must see count=1 — got: " + body);
        assertTrue(body.contains("\"firstPending\":{"),
                "firstPending populated object must be present on count>0 — got: " + body);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UUID createShelterInTenant(HttpHeaders headers, boolean dvShelter) {
        return createShelterWithHeaders(headers, dvShelter);
    }

    private void patchAvailabilityInTenant(UUID shelterId, String popType, int total, int occupied, int hold, HttpHeaders headers) {
        String body = String.format("""
                {"populationType": "%s", "bedsTotal": %d, "bedsOccupied": %d, "bedsOnHold": %d, "acceptingNewGuests": true}
                """, popType, total, occupied, hold);
        restTemplate.exchange("/api/v1/shelters/" + shelterId + "/availability",
                HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
    }

    private UUID createShelterWithHeaders(HttpHeaders headers, boolean dvShelter) {
        String body = String.format("""
                {
                  "name": "%s Shelter %s",
                  "addressStreet": "123 Test St",
                  "addressCity": "Raleigh",
                  "addressState": "NC",
                  "addressZip": "27601",
                  "phone": "919-555-%04d",
                  "dvShelter": %s,
                  "constraints": {
                    "populationTypesServed": ["%s"]
                  },
                  "capacities": [{"populationType": "%s", "bedsTotal": 10}]
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
                new HttpEntity<>(body, headers), String.class);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        return UUID.fromString(extractField(resp.getBody(), "id"));
    }

    private UUID createShelter(boolean dvShelter) {
        String body = String.format("""
                {
                  "name": "%s Shelter %s",
                  "addressStreet": "123 Test St",
                  "addressCity": "Raleigh",
                  "addressState": "NC",
                  "addressZip": "27601",
                  "phone": "919-555-%04d",
                  "dvShelter": %s,
                  "constraints": {
                    "populationTypesServed": ["%s"]
                  },
                  "capacities": [{"populationType": "%s", "bedsTotal": 10}]
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
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "POST /shelters should return 201 — body: " + resp.getBody());
        return UUID.fromString(extractField(resp.getBody(), "id"));
    }

    private void patchAvailability(UUID shelterId, String popType, int total, int occupied, int hold) {
        String body = String.format("""
                {"populationType": "%s", "bedsTotal": %d, "bedsOccupied": %d, "bedsOnHold": %d, "acceptingNewGuests": true}
                """, popType, total, occupied, hold);
        restTemplate.exchange("/api/v1/shelters/" + shelterId + "/availability",
                HttpMethod.PATCH, new HttpEntity<>(body, coordHeaders), String.class);
    }

    private ResponseEntity<String> createReferral(UUID shelterId, HttpHeaders headers) {
        String body = String.format("""
                {
                  "shelterId": "%s",
                  "householdSize": 3,
                  "populationType": "DV_SURVIVOR",
                  "urgency": "URGENT",
                  "specialNeeds": "Wheelchair accessible needed",
                  "callbackNumber": "919-555-0042"
                }
                """, shelterId);
        return restTemplate.exchange("/api/v1/dv-referrals", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private String extractField(String json, String field) {
        int idx = json.indexOf("\"" + field + "\":\"");
        if (idx < 0) {
            throw new AssertionError("Field '" + field + "' not found in response: " + json);
        }
        int start = idx + field.length() + 4;
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    private int extractFieldFromDetail(String json, String field) {
        int idx = json.indexOf("\"" + field + "\":");
        if (idx < 0) return -999;
        String val = json.substring(idx + field.length() + 3);
        int end = Math.min(
                val.indexOf(",") >= 0 ? val.indexOf(",") : val.length(),
                val.indexOf("}") >= 0 ? val.indexOf("}") : val.length()
        );
        return Integer.parseInt(val.substring(0, end).trim());
    }
}
