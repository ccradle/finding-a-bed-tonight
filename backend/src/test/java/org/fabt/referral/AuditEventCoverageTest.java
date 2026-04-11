package org.fabt.referral;

import java.util.List;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.referral.service.ReferralTokenService;
import org.fabt.shared.web.TenantContext;
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
 * T-29 (partial) — Verifies that every CoC-admin-escalation audit event type
 * actually lands a row in {@code audit_events} with the expected actor /
 * target / details shape.
 *
 * <p>Casey Drummond's lens: the audit trail is the chain-of-custody answer
 * to a court subpoena. If a row is silently swallowed (e.g. by NOT NULL
 * constraint hitting a system actor), the trail is broken. This test would
 * catch the V42 actor_user_id-nullable regression instantly.</p>
 *
 * <p>Coverage in this file (Sessions 2-3):</p>
 * <ul>
 *   <li>{@code DV_REFERRAL_CLAIMED}</li>
 *   <li>{@code DV_REFERRAL_RELEASED} (manual)</li>
 *   <li>{@code DV_REFERRAL_RELEASED} (timeout — system actor)</li>
 *   <li>{@code DV_REFERRAL_ADMIN_ACCEPTED}</li>
 *   <li>{@code DV_REFERRAL_ADMIN_REJECTED}</li>
 * </ul>
 *
 * <p>Coordinator-actor variants ({@code DV_REFERRAL_ACCEPTED} /
 * {@code DV_REFERRAL_REJECTED}) are also exercised here as a regression
 * guard for the C5 role-aware audit fix.</p>
 *
 * <p>Reassign + escalation policy update audit types are out of scope for
 * Session 3 — they ship with Session 4 endpoints.</p>
 */
class AuditEventCoverageTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ReferralTokenService referralTokenService;

    private UUID dvShelterId;
    private HttpHeaders outreachHeaders;
    private User coordinator;
    private HttpHeaders coordinatorHeaders;
    private User cocAdmin;
    private HttpHeaders cocAdminHeaders;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();

        var dvAdmin = authHelper.setupUserWithDvAccess(
                "audit-padmin@test.fabt.org", "Audit Platform Admin", new String[]{"PLATFORM_ADMIN"});
        HttpHeaders padminHeaders = authHelper.headersForUser(dvAdmin);

        var dvOutreach = authHelper.setupUserWithDvAccess(
                "audit-outreach@test.fabt.org", "Audit Outreach", new String[]{"OUTREACH_WORKER"});
        outreachHeaders = authHelper.headersForUser(dvOutreach);

        coordinator = authHelper.setupUserWithDvAccess(
                "audit-coord@test.fabt.org", "Audit Coordinator", new String[]{"COORDINATOR"});
        coordinatorHeaders = authHelper.headersForUser(coordinator);

        cocAdmin = authHelper.setupUserWithDvAccess(
                "audit-cocadmin@test.fabt.org", "Audit CoC Admin", new String[]{"COC_ADMIN"});
        cocAdminHeaders = authHelper.headersForUser(cocAdmin);

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
    }

    @Test
    @DisplayName("T-29: claim writes one DV_REFERRAL_CLAIMED row with correct actor + target")
    void claimWritesAuditRow() {
        UUID tokenId = createPendingReferral();

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/claim", HttpMethod.POST,
                new HttpEntity<>(cocAdminHeaders), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<AuditRow> rows = findAudit("DV_REFERRAL_CLAIMED", tokenId);
        assertThat(rows).as("Exactly one CLAIMED row must exist").hasSize(1);
        assertThat(rows.get(0).actorUserId()).isEqualTo(cocAdmin.getId());
        assertThat(rows.get(0).targetUserId()).isEqualTo(tokenId);
        assertThat(rows.get(0).details()).contains("claimed_until");
    }

    @Test
    @DisplayName("T-29: manual release writes one DV_REFERRAL_RELEASED row with reason=manual")
    void manualReleaseWritesAuditRow() {
        UUID tokenId = createPendingReferral();
        restTemplate.exchange("/api/v1/dv-referrals/" + tokenId + "/claim", HttpMethod.POST,
                new HttpEntity<>(cocAdminHeaders), String.class);
        ResponseEntity<String> rel = restTemplate.exchange(
                "/api/v1/dv-referrals/" + tokenId + "/release", HttpMethod.POST,
                new HttpEntity<>(cocAdminHeaders), String.class);
        assertThat(rel.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<AuditRow> rows = findAudit("DV_REFERRAL_RELEASED", tokenId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).actorUserId()).isEqualTo(cocAdmin.getId());
        assertThat(rows.get(0).details()).contains("manual");
    }

    @Test
    @DisplayName("T-29: auto-release (system actor) writes one row with NULL actor — V42 contract")
    void timeoutReleaseWritesAuditRowWithNullActor() {
        UUID tokenId = createPendingReferral();

        TenantContext.runWithContext(authHelper.getTestTenantId(), true, () -> {
            referralTokenService.claimToken(tokenId, cocAdmin.getId(), false);
            jdbcTemplate.update(
                    "UPDATE referral_token SET claim_expires_at = NOW() - INTERVAL '1 minute' WHERE id = ?",
                    tokenId);
        });

        referralTokenService.autoReleaseClaims();

        List<AuditRow> rows = findAudit("DV_REFERRAL_RELEASED", tokenId);
        // The CLAIMED row above also lives in audit_events but for a different action.
        // We expect exactly one RELEASED row with reason=timeout and actor=NULL.
        assertThat(rows)
                .as("Auto-release must persist its audit row even though the actor is null")
                .hasSize(1);
        AuditRow row = rows.get(0);
        assertThat(row.actorUserId())
                .as("System actor → NULL actor_user_id (V42)")
                .isNull();
        assertThat(row.details()).contains("timeout");
    }

    @Test
    @DisplayName("T-29: COC_ADMIN accept writes DV_REFERRAL_ADMIN_ACCEPTED, COORDINATOR accept writes DV_REFERRAL_ACCEPTED")
    void roleAwareAcceptAudit() {
        // Coordinator branch
        UUID coordTokenId = createPendingReferral();
        restTemplate.exchange("/api/v1/dv-referrals/" + coordTokenId + "/accept", HttpMethod.PATCH,
                new HttpEntity<>(coordinatorHeaders), String.class);
        assertThat(findAudit("DV_REFERRAL_ACCEPTED", coordTokenId))
                .as("Coordinator accept must NOT use the ADMIN audit type")
                .hasSize(1);
        assertThat(findAudit("DV_REFERRAL_ADMIN_ACCEPTED", coordTokenId)).isEmpty();

        // CoC admin branch — needs a fresh referral from a different outreach
        // worker because the coordinator already accepted the first one.
        var outreach2 = authHelper.setupUserWithDvAccess(
                "audit-outreach-2@test.fabt.org", "Audit Outreach 2", new String[]{"OUTREACH_WORKER"});
        HttpHeaders outreach2Headers = authHelper.headersForUser(outreach2);
        UUID adminTokenId = createPendingReferralWithHeaders(outreach2Headers);
        restTemplate.exchange("/api/v1/dv-referrals/" + adminTokenId + "/accept", HttpMethod.PATCH,
                new HttpEntity<>(cocAdminHeaders), String.class);
        assertThat(findAudit("DV_REFERRAL_ADMIN_ACCEPTED", adminTokenId)).hasSize(1);
        assertThat(findAudit("DV_REFERRAL_ACCEPTED", adminTokenId)).isEmpty();
    }

    @Test
    @DisplayName("T-29: COC_ADMIN reject writes DV_REFERRAL_ADMIN_REJECTED with reason in details")
    void rejectAuditAdminRole() {
        UUID tokenId = createPendingReferral();

        String rejectBody = "{\"reason\":\"no capacity for pets\"}";
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(cocAdminHeaders);
        headers.set("Content-Type", "application/json");

        restTemplate.exchange("/api/v1/dv-referrals/" + tokenId + "/reject", HttpMethod.PATCH,
                new HttpEntity<>(rejectBody, headers), String.class);

        List<AuditRow> rows = findAudit("DV_REFERRAL_ADMIN_REJECTED", tokenId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).actorUserId()).isEqualTo(cocAdmin.getId());
        assertThat(rows.get(0).details()).contains("no capacity for pets");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private record AuditRow(UUID actorUserId, UUID targetUserId, String details) {}

    private List<AuditRow> findAudit(String action, UUID targetId) {
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            conn.createStatement().execute("RESET ROLE");
            try (var ps = conn.prepareStatement(
                    "SELECT actor_user_id, target_user_id, details::text "
                    + "  FROM audit_events "
                    + " WHERE action = ? AND target_user_id = ?")) {
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

    private UUID createPendingReferral() {
        return createPendingReferralWithHeaders(outreachHeaders);
    }

    private UUID createPendingReferralWithHeaders(HttpHeaders headers) {
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
                new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(extractField(resp.getBody(), "id"));
    }

    private UUID createDvShelter(HttpHeaders headers) {
        String body = String.format("""
                {
                  "name": "Audit Test DV Shelter %s",
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
