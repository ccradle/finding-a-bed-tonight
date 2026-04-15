package org.fabt.referral;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-26 — Integration tests for {@code POST /api/v1/dv-referrals/{id}/reassign}.
 *
 * <p>Covers all three target types and the load-bearing
 * SPECIFIC_USER-breaks-the-escalation-chain assertion: the batch tasklet
 * must skip a referral whose chain has been broken by an admin.</p>
 */
class ReassignTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Tasklet escalationTasklet;

    private UUID dvShelterId;
    private HttpHeaders outreachHeaders;
    private User dvCoordinator;
    private HttpHeaders cocAdminHeaders;
    private User cocAdmin;
    private User specificTargetUser;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();

        var dvAdmin = authHelper.setupUserWithDvAccess(
                "reassign-padmin@test.fabt.org", "Reassign Platform Admin", new String[]{"PLATFORM_ADMIN"});
        HttpHeaders padminHeaders = authHelper.headersForUser(dvAdmin);

        var dvOutreach = authHelper.setupUserWithDvAccess(
                "reassign-outreach@test.fabt.org", "Reassign Outreach", new String[]{"OUTREACH_WORKER"});
        outreachHeaders = authHelper.headersForUser(dvOutreach);

        dvCoordinator = authHelper.setupUserWithDvAccess(
                "reassign-coord@test.fabt.org", "Reassign Coord", new String[]{"COORDINATOR"});

        cocAdmin = authHelper.setupUserWithDvAccess(
                "reassign-cocadmin@test.fabt.org", "Reassign CoC Admin", new String[]{"COC_ADMIN"});
        cocAdminHeaders = authHelper.headersForUser(cocAdmin);

        specificTargetUser = authHelper.setupUserWithDvAccess(
                "reassign-target@test.fabt.org", "Reassign Specific Target", new String[]{"COORDINATOR"});

        // Clean prior state.
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            conn.createStatement().execute("RESET ROLE");
            try (var ps = conn.prepareStatement("DELETE FROM referral_token WHERE tenant_id = ?")) {
                ps.setObject(1, authHelper.getTestTenantId());
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("DELETE FROM notification WHERE tenant_id = ?")) {
                ps.setObject(1, authHelper.getTestTenantId());
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement(
                    "DELETE FROM audit_events WHERE action LIKE 'DV_REFERRAL_%'")) {
                ps.executeUpdate();
            }
            conn.createStatement().execute("SET ROLE fabt_app");
        } catch (java.sql.SQLException e) {
            throw new RuntimeException("setUp cleanup failed", e);
        }

        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            dvShelterId = createDvShelter(padminHeaders);
        });

        // Wire the DV coordinator as an assigned coordinator for this shelter
        // so the COORDINATOR_GROUP fan-out has a non-empty recipient list.
        // Schema (V7): composite PK (user_id, shelter_id), no separate id or
        // tenant_id columns — tenant scope is inherited via shelter FK.
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            try (var conn = jdbcTemplate.getDataSource().getConnection()) {
                conn.createStatement().execute("RESET ROLE");
                try (var ps = conn.prepareStatement(
                        "INSERT INTO coordinator_assignment (user_id, shelter_id) "
                        + "VALUES (?, ?) ON CONFLICT DO NOTHING")) {
                    ps.setObject(1, dvCoordinator.getId());
                    ps.setObject(2, dvShelterId);
                    ps.executeUpdate();
                }
                conn.createStatement().execute("SET ROLE fabt_app");
            } catch (java.sql.SQLException e) {
                throw new RuntimeException("coordinator assignment failed", e);
            }
        });
    }

    @Test
    @DisplayName("T-26a: COORDINATOR_GROUP reassign pages coordinators with enriched audit + PII-safe payload")
    void coordinatorGroupReassignPagesShelterCoordinators() {
        UUID tokenId = createPendingReferral();

        // Use a recognizable reason string so we can assert PII reduction:
        // the reason MUST appear in the audit row but MUST NOT appear in
        // any recipient's notification payload (Keisha Thompson war-room
        // round 3 lock-in).
        String distinctiveReason = "PII-MARKER-need-a-second-look";
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/reassign",
                HttpMethod.POST,
                new HttpEntity<>(String.format("""
                        {"targetType":"COORDINATOR_GROUP","reason":"%s"}
                        """, distinctiveReason), jsonHeaders(cocAdminHeaders)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The DV coordinator wired in setUp should have a referral.reassigned notification.
        int count = countNotifications(dvCoordinator.getId(), "referral.reassigned", tokenId);
        assertThat(count)
                .as("COORDINATOR_GROUP fan-out must reach the shelter's assigned coordinator")
                .isEqualTo(1);

        // Keisha Thompson (war room round 3): the admin's reason must NOT
        // leak into the broadcast notification payload. Recipients see only
        // referralId + targetType. Even if an admin slips PII into the reason
        // despite the modal warning, the audit row is the only sink.
        String payload = readNotificationPayload(dvCoordinator.getId(), "referral.reassigned", tokenId);
        assertThat(payload)
                .as("Reason must NOT appear in the broadcast notification payload — PII reduction")
                .doesNotContain(distinctiveReason);
        assertThat(payload).contains(tokenId.toString());
        assertThat(payload).contains("COORDINATOR_GROUP");

        // Audit row enrichment lock-in — Casey Drummond #1 + #2 (war room
        // round 3): shelterId for single-table subpoena queries, actorRoles
        // for frozen-at-action-time chain of custody. Without these
        // assertions a future "cleanup" could remove them and CI would not
        // catch the regression.
        List<AuditRow> rows = findAudit("DV_REFERRAL_REASSIGNED", tokenId);
        assertThat(rows).hasSize(1);
        AuditRow audit = rows.get(0);
        assertThat(audit.actorUserId()).isEqualTo(cocAdmin.getId());

        // audit_events.details is JSONB; ::text emits "key": value (with
        // space). Normalize whitespace before content matching.
        String detailsCompact = audit.details().replaceAll("\\s+", "");
        assertThat(detailsCompact).contains("\"targetType\":\"COORDINATOR_GROUP\"");
        assertThat(detailsCompact).contains("\"reason\":\"" + distinctiveReason + "\"");
        assertThat(detailsCompact)
                .as("shelterId enrichment (Casey #1) must be present in audit details")
                .contains("\"shelterId\":\"" + dvShelterId + "\"");
        assertThat(detailsCompact)
                .as("actorRoles enrichment (Casey #2) must be present in audit details")
                .contains("\"actorRoles\":[\"COC_ADMIN\"]");

        // Chain NOT broken — group reassigns leave escalation enabled.
        assertThat(readChainBroken(tokenId)).isFalse();
    }

    @Test
    @DisplayName("T-26b: COC_ADMIN_GROUP reassign pages all CoC admins as CRITICAL")
    void cocAdminGroupReassignPagesAllAdmins() {
        UUID tokenId = createPendingReferral();

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/reassign",
                HttpMethod.POST,
                new HttpEntity<>("""
                        {"targetType":"COC_ADMIN_GROUP","reason":"escalating to admin group"}
                        """, jsonHeaders(cocAdminHeaders)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The CoC admin (also a recipient because they're in the role list)
        // should have one referral.reassigned notification of severity CRITICAL.
        // Note: the actor is also a COC_ADMIN; pages-the-actor is acceptable
        // because they're broadcasting to the group, not just to themselves.
        int count = countNotificationsBySeverity(cocAdmin.getId(),
                "referral.reassigned", tokenId, "CRITICAL");
        assertThat(count).isEqualTo(1);

        assertThat(findAudit("DV_REFERRAL_REASSIGNED", tokenId)).hasSize(1);
        assertThat(readChainBroken(tokenId)).isFalse();
    }

    @Test
    @DisplayName("T-26c: SPECIFIC_USER reassign breaks the escalation chain")
    void specificUserReassignBreaksEscalationChain() {
        UUID tokenId = createPendingReferral();

        String body = String.format("""
                {"targetType":"SPECIFIC_USER","targetUserId":"%s","reason":"Maria handles overnights"}
                """, specificTargetUser.getId());
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/reassign",
                HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders(cocAdminHeaders)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Only the named user got paged.
        assertThat(countNotifications(specificTargetUser.getId(), "referral.reassigned", tokenId))
                .isEqualTo(1);
        assertThat(countNotifications(dvCoordinator.getId(), "referral.reassigned", tokenId))
                .as("DV coordinator should NOT receive a SPECIFIC_USER reassign notification")
                .isEqualTo(0);

        // Chain IS broken now.
        assertThat(readChainBroken(tokenId)).isTrue();

        // Audit row records the targetUserId for chain-of-custody.
        List<AuditRow> rows = findAudit("DV_REFERRAL_REASSIGNED", tokenId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).details()).contains(specificTargetUser.getId().toString());
        assertThat(rows.get(0).details()).contains("SPECIFIC_USER");
    }

    @Test
    @DisplayName("T-26d: chain-broken referral is skipped by the escalation tasklet")
    void chainBrokenReferralIsSkippedByTasklet() {
        // Create a referral, reassign to specific user, backdate so it WOULD
        // normally fire escalation.1h, then run the tasklet and assert no
        // escalation notification fired (only the reassign notification).
        UUID tokenId = createPendingReferral();

        String body = String.format("""
                {"targetType":"SPECIFIC_USER","targetUserId":"%s","reason":"manual takeover"}
                """, specificTargetUser.getId());
        restTemplate.exchange("/api/v1/dv-referrals/" + tokenId + "/reassign",
                HttpMethod.POST, new HttpEntity<>(body, jsonHeaders(cocAdminHeaders)), String.class);

        // Backdate the referral so it would cross the 1h threshold.
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            jdbcTemplate.update(
                    "UPDATE referral_token SET created_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(Instant.now().minus(65, ChronoUnit.MINUTES)), tokenId);
        });

        // Run the tasklet.
        TenantContext.runWithContext(null, true, () -> {
            try {
                escalationTasklet.execute(null, null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // The DV coordinator should NOT have an escalation.1h notification
        // for this referral — chain is broken.
        assertThat(countNotifications(dvCoordinator.getId(), "escalation.1h", tokenId))
                .as("Tasklet must skip referrals where escalation_chain_broken=true")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("T-26e: SPECIFIC_USER without targetUserId returns 400")
    void specificUserRequiresTargetUserId() {
        UUID tokenId = createPendingReferral();

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/reassign",
                HttpMethod.POST,
                new HttpEntity<>("""
                        {"targetType":"SPECIFIC_USER","reason":"forgot the target"}
                        """, jsonHeaders(cocAdminHeaders)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("T-26f: missing referral returns 404")
    void missingReferralReturns404() {
        UUID nonExistent = UUID.randomUUID();
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + nonExistent + "/reassign",
                HttpMethod.POST,
                new HttpEntity<>("""
                        {"targetType":"COC_ADMIN_GROUP","reason":"chasing a ghost"}
                        """, jsonHeaders(cocAdminHeaders)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("T-26g: non-pending referral returns 409")
    void nonPendingReferralRejected() {
        UUID tokenId = createPendingReferral();

        // Accept the referral so it's no longer PENDING.
        ResponseEntity<String> acceptResp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/accept",
                HttpMethod.PATCH, new HttpEntity<>(jsonHeaders(cocAdminHeaders)), String.class);
        assertThat(acceptResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/reassign",
                HttpMethod.POST,
                new HttpEntity<>("""
                        {"targetType":"COORDINATOR_GROUP","reason":"too late"}
                        """, jsonHeaders(cocAdminHeaders)),
                String.class);
        assertThat(resp.getStatusCode())
                .as("Reassigning an ACCEPTED referral must not silently succeed")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("T-26h: cross-tenant referral access blocked")
    void crossTenantReassignBlocked() {
        // Create a referral in tenant A (the default test tenant).
        UUID tokenId = createPendingReferral();

        // Build an admin in a SECOND tenant. Their JWT carries the second
        // tenant id; the JWT filter binds TenantContext accordingly. They
        // should NOT be able to reassign tenant A's referral.
        var jwtService = authHelper.getJwtService();
        var passwordService = authHelper.getPasswordService();
        var userRepo = authHelper.getUserRepository();
        var tenantService = applicationContextTenantService();
        var tenantB = tenantService.findBySlug("reassign-tenant-b")
                .orElseGet(() -> tenantService.create("Reassign Tenant B", "reassign-tenant-b"));

        org.fabt.auth.domain.User otherTenantAdmin = userRepo
                .findByTenantIdAndEmail(tenantB.getId(), "reassign-other-admin@test.fabt.org")
                .orElseGet(() -> {
                    var u = new org.fabt.auth.domain.User();
                    u.setTenantId(tenantB.getId());
                    u.setEmail("reassign-other-admin@test.fabt.org");
                    u.setDisplayName("Other Tenant Admin");
                    u.setPasswordHash(passwordService.hash(TestAuthHelper.TEST_PASSWORD));
                    u.setRoles(new String[]{"COC_ADMIN"});
                    u.setDvAccess(true);
                    u.setCreatedAt(java.time.Instant.now());
                    u.setUpdatedAt(java.time.Instant.now());
                    return userRepo.save(u);
                });

        HttpHeaders otherHeaders = new HttpHeaders();
        otherHeaders.setBearerAuth(jwtService.generateAccessToken(otherTenantAdmin));
        otherHeaders.set("Content-Type", "application/json");

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/reassign",
                HttpMethod.POST,
                new HttpEntity<>("""
                        {"targetType":"COC_ADMIN_GROUP","reason":"trying to steal"}
                        """, otherHeaders),
                String.class);

        // Marcus Webb #1 (war room round 3): cross-tenant access must be
        // blocked at the service layer because referral_token RLS only
        // checks dvAccess, not tenant.
        //
        // Status updated 2026-04-14 with tasks 8.5/8.6 cross-tenant hardening
        // (commit 366765c): repository.findByIdAndTenantId now short-circuits
        // the cross-tenant lookup with NoSuchElementException → 404 BEFORE
        // the service's own AccessDeniedException guard runs. 404 matches
        // Marcus's D10 contract — no information leak about whether the
        // referral exists in another tenant. The previous 403 assertion
        // leaked existence semantically (403 implies "the resource exists
        // but you can't touch it"). Aligns with parallel cross-tenant
        // tests tc_getById/accept/reject_crossTenant_returns404 in
        // DvReferralIntegrationTest.
        assertThat(resp.getStatusCode())
                .as("Tenant B admin must see 404 for tenant A's referral (D10 — no existence leak)")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.fabt.tenant.service.TenantService tenantServiceForTests;

    private org.fabt.tenant.service.TenantService applicationContextTenantService() {
        return tenantServiceForTests;
    }

    @Test
    @DisplayName("T-26i: SPECIFIC_USER → COORDINATOR_GROUP resumes auto-escalation (Marcus Okafor war-room R4)")
    void groupReassignAfterSpecificUserResumesEscalation() {
        // Sequence: create referral → reassign to specific user (chain
        // breaks) → admin gives it back to the group → backdate so the
        // referral is past the 1h threshold → run tasklet → assert that
        // escalation.1h fired (chain-broken was cleared by the group reassign).
        UUID tokenId = createPendingReferral();

        // Step 1: SPECIFIC_USER reassign — chain breaks.
        String specificBody = String.format("""
                {"targetType":"SPECIFIC_USER","targetUserId":"%s","reason":"Maria takes overnights"}
                """, specificTargetUser.getId());
        ResponseEntity<String> specificResp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/reassign",
                HttpMethod.POST,
                new HttpEntity<>(specificBody, jsonHeaders(cocAdminHeaders)),
                String.class);
        assertThat(specificResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readChainBroken(tokenId)).as("Chain must be broken after SPECIFIC_USER").isTrue();

        // Step 2: COORDINATOR_GROUP reassign — admin gives it back. Chain MUST resume.
        ResponseEntity<String> groupResp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/reassign",
                HttpMethod.POST,
                new HttpEntity<>("""
                        {"targetType":"COORDINATOR_GROUP","reason":"Maria is on PTO, group take over"}
                        """, jsonHeaders(cocAdminHeaders)),
                String.class);
        assertThat(groupResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readChainBroken(tokenId))
                .as("COORDINATOR_GROUP reassign after SPECIFIC_USER must clear escalation_chain_broken")
                .isFalse();

        // Step 3: backdate to 65 minutes so the 1h threshold has been crossed.
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            jdbcTemplate.update(
                    "UPDATE referral_token SET created_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(Instant.now().minus(65, ChronoUnit.MINUTES)), tokenId);
        });

        // Step 4: run the escalation tasklet.
        TenantContext.runWithContext(null, true, () -> {
            try {
                escalationTasklet.execute(null, null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Step 5: the DV coordinator MUST have an escalation.1h notification —
        // the chain was resumed by the group reassign, so the tasklet
        // processed the referral instead of skipping it.
        assertThat(countNotifications(dvCoordinator.getId(), "escalation.1h", tokenId))
                .as("Tasklet must escalate after chain-resume — Marcus Okafor R4 invariant")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("T-26k: SPECIFIC_USER → COC_ADMIN_GROUP also resumes auto-escalation (symmetry with T-26i)")
    void cocAdminGroupReassignAfterSpecificUserResumesEscalation() {
        // Riley Cho war-room round 5: T-26i covers COORDINATOR_GROUP
        // chain-resume; this is the symmetric case for COC_ADMIN_GROUP. The
        // two branches share most of their logic but diverge in audit details
        // (severity, recipient lookup) — locking both prevents an asymmetric
        // future regression where one branch loses chain-resume.
        UUID tokenId = createPendingReferral();

        // Step 1: SPECIFIC_USER reassign — chain breaks.
        String specificBody = String.format("""
                {"targetType":"SPECIFIC_USER","targetUserId":"%s","reason":"Maria takes overnights"}
                """, specificTargetUser.getId());
        ResponseEntity<String> specificResp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/reassign",
                HttpMethod.POST,
                new HttpEntity<>(specificBody, jsonHeaders(cocAdminHeaders)),
                String.class);
        assertThat(specificResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readChainBroken(tokenId)).isTrue();

        // Step 2: COC_ADMIN_GROUP reassign — admin escalates to admin group.
        // Chain MUST resume because no single person owns it anymore.
        ResponseEntity<String> groupResp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/reassign",
                HttpMethod.POST,
                new HttpEntity<>("""
                        {"targetType":"COC_ADMIN_GROUP","reason":"escalating to admin team"}
                        """, jsonHeaders(cocAdminHeaders)),
                String.class);
        assertThat(groupResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readChainBroken(tokenId))
                .as("COC_ADMIN_GROUP reassign after SPECIFIC_USER must clear escalation_chain_broken")
                .isFalse();

        // Casey Drummond war-room round 5: chainResumed:true must be in the
        // SECOND audit row (the COC_ADMIN_GROUP one), not the first.
        List<AuditRow> rows = findAudit("DV_REFERRAL_REASSIGNED", tokenId);
        assertThat(rows).hasSize(2);
        AuditRow secondAudit = rows.stream()
                .filter(r -> r.details().contains("COC_ADMIN_GROUP"))
                .findFirst()
                .orElseThrow();
        String secondCompact = secondAudit.details().replaceAll("\\s+", "");
        assertThat(secondCompact)
                .as("Group reassign after a chain break must record chainResumed:true (Casey R5)")
                .contains("\"chainResumed\":true");

        // The first (SPECIFIC_USER) row must NOT have chainResumed — it
        // BROKE the chain, didn't resume it.
        AuditRow firstAudit = rows.stream()
                .filter(r -> r.details().contains("SPECIFIC_USER"))
                .findFirst()
                .orElseThrow();
        assertThat(firstAudit.details()).doesNotContain("chainResumed");
    }

    @Test
    @DisplayName("T-26j: SPECIFIC_USER targeting a different-tenant user is rejected (Marcus Webb #2)")
    void specificUserCrossTenantTargetRejected() {
        UUID tokenId = createPendingReferral();

        // Build a user in a different tenant. The reassign caller (cocAdmin)
        // is in tenant A; the target user is in tenant B. The service-layer
        // existsByIdInCurrentTenant call must return false → 404.
        var tenantService = applicationContextTenantService();
        var tenantC = tenantService.findBySlug("reassign-tenant-c")
                .orElseGet(() -> tenantService.create("Reassign Tenant C", "reassign-tenant-c"));

        var userRepo = authHelper.getUserRepository();
        var passwordService = authHelper.getPasswordService();
        org.fabt.auth.domain.User otherTenantUser = userRepo
                .findByTenantIdAndEmail(tenantC.getId(), "reassign-cross-target@test.fabt.org")
                .orElseGet(() -> {
                    var u = new org.fabt.auth.domain.User();
                    u.setTenantId(tenantC.getId());
                    u.setEmail("reassign-cross-target@test.fabt.org");
                    u.setDisplayName("Cross Tenant Target");
                    u.setPasswordHash(passwordService.hash(TestAuthHelper.TEST_PASSWORD));
                    u.setRoles(new String[]{"COORDINATOR"});
                    u.setDvAccess(true);
                    u.setCreatedAt(java.time.Instant.now());
                    u.setUpdatedAt(java.time.Instant.now());
                    return userRepo.save(u);
                });

        String body = String.format("""
                {"targetType":"SPECIFIC_USER","targetUserId":"%s","reason":"trying to leak across tenants"}
                """, otherTenantUser.getId());
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/reassign",
                HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders(cocAdminHeaders)),
                String.class);

        assertThat(resp.getStatusCode())
                .as("Cross-tenant target user must be rejected (existsByIdInCurrentTenant false → 404)")
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Defense in depth: chain MUST NOT have been broken (the failure
        // happens BEFORE markEscalationChainBroken).
        assertThat(readChainBroken(tokenId))
                .as("Failed reassign must not flip the chain-broken flag")
                .isFalse();
    }

    /**
     * Read the notification.payload column for a (recipient, type, referral)
     * combo. Returns the payload JSON as a String, or null if no row exists.
     * Used by T-26a to assert PII reduction (Keisha Thompson lock-in).
     */
    private String readNotificationPayload(UUID recipientId, String type, UUID referralId) {
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            conn.createStatement().execute("RESET ROLE");
            try (var ps = conn.prepareStatement(
                    "SELECT payload::text FROM notification "
                    + " WHERE recipient_id = ? AND type = ? AND payload::text LIKE ? "
                    + " LIMIT 1")) {
                ps.setObject(1, recipientId);
                ps.setString(2, type);
                ps.setString(3, "%" + referralId + "%");
                var rs = ps.executeQuery();
                String result = rs.next() ? rs.getString(1) : null;
                conn.createStatement().execute("SET ROLE fabt_app");
                return result;
            }
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private record AuditRow(UUID actorUserId, UUID targetUserId, String details) {}

    private HttpHeaders jsonHeaders(HttpHeaders base) {
        HttpHeaders h = new HttpHeaders();
        h.putAll(base);
        h.set("Content-Type", "application/json");
        return h;
    }

    private List<AuditRow> findAudit(String action, UUID targetId) {
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            conn.createStatement().execute("RESET ROLE");
            try (var ps = conn.prepareStatement(
                    "SELECT actor_user_id, target_user_id, details::text "
                    + "  FROM audit_events WHERE action = ? AND target_user_id = ?")) {
                ps.setString(1, action);
                ps.setObject(2, targetId);
                var rs = ps.executeQuery();
                List<AuditRow> out = new java.util.ArrayList<>();
                while (rs.next()) {
                    out.add(new AuditRow(
                            (UUID) rs.getObject(1),
                            (UUID) rs.getObject(2),
                            rs.getString(3)));
                }
                conn.createStatement().execute("SET ROLE fabt_app");
                return out;
            }
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private int countNotifications(UUID recipientId, String type, UUID referralId) {
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            conn.createStatement().execute("RESET ROLE");
            try (var ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM notification "
                    + " WHERE recipient_id = ? AND type = ? AND payload::text LIKE ?")) {
                ps.setObject(1, recipientId);
                ps.setString(2, type);
                ps.setString(3, "%" + referralId + "%");
                var rs = ps.executeQuery();
                rs.next();
                int count = rs.getInt(1);
                conn.createStatement().execute("SET ROLE fabt_app");
                return count;
            }
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private int countNotificationsBySeverity(UUID recipientId, String type, UUID referralId, String severity) {
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            conn.createStatement().execute("RESET ROLE");
            try (var ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM notification "
                    + " WHERE recipient_id = ? AND type = ? AND severity = ? "
                    + "   AND payload::text LIKE ?")) {
                ps.setObject(1, recipientId);
                ps.setString(2, type);
                ps.setString(3, severity);
                ps.setString(4, "%" + referralId + "%");
                var rs = ps.executeQuery();
                rs.next();
                int count = rs.getInt(1);
                conn.createStatement().execute("SET ROLE fabt_app");
                return count;
            }
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean readChainBroken(UUID tokenId) {
        boolean[] holder = new boolean[1];
        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () ->
                holder[0] = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                        "SELECT escalation_chain_broken FROM referral_token WHERE id = ?",
                        Boolean.class, tokenId)));
        return holder[0];
    }

    private UUID createPendingReferral() {
        String body = String.format("""
                {
                  "shelterId": "%s",
                  "householdSize": 2,
                  "populationType": "DV_SURVIVOR",
                  "urgency": "URGENT",
                  "specialNeeds": null,
                  "callbackNumber": "919-555-0099"
                }
                """, dvShelterId);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals", HttpMethod.POST,
                new HttpEntity<>(body, outreachHeaders), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(extractField(resp.getBody(), "id"));
    }

    private UUID createDvShelter(HttpHeaders headers) {
        String body = String.format("""
                {
                  "name": "Reassign Test DV Shelter %s",
                  "addressStreet": "123 Test St",
                  "addressCity": "Raleigh",
                  "addressState": "NC",
                  "addressZip": "27601",
                  "phone": "919-555-%04d",
                  "dvShelter": true,
                  "constraints": {"populationTypesServed": ["DV_SURVIVOR"]},
                  "capacities": [{"populationType": "DV_SURVIVOR", "bedsTotal": 10}]
                }
                """,
                UUID.randomUUID().toString().substring(0, 8),
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
                HttpMethod.PATCH, new HttpEntity<>(availBody, headers), String.class);
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
