package org.fabt.notification;

import java.util.List;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.notification.service.EscalationPolicyService;
import org.junit.jupiter.api.AfterEach;
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
 * T-28 — Integration tests for the admin escalation-policy endpoints
 * ({@code GET /api/v1/admin/escalation-policy/{eventType}} and the
 * corresponding {@code PATCH}).
 *
 * <p>Covers:</p>
 * <ul>
 *   <li>GET fallback to platform default when tenant has no custom policy</li>
 *   <li>PATCH success path — new version inserted, returned, audited</li>
 *   <li>PATCH validation failures (monotonic, invalid role, invalid duration)</li>
 *   <li>Authorization gate — COORDINATOR rejected with 403</li>
 *   <li>Tenant isolation — caller's tenant scopes the read/write</li>
 * </ul>
 */
class EscalationPolicyEndpointTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EscalationPolicyService escalationPolicyService;

    private User cocAdmin;
    private HttpHeaders cocAdminHeaders;
    private HttpHeaders coordinatorHeaders;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();

        cocAdmin = authHelper.setupUserWithDvAccess(
                "policy-cocadmin@test.fabt.org", "Policy CoC Admin", new String[]{"COC_ADMIN"});
        cocAdminHeaders = authHelper.headersForUser(cocAdmin);

        var coord = authHelper.setupUserWithDvAccess(
                "policy-coord@test.fabt.org", "Policy Coord", new String[]{"COORDINATOR"});
        coordinatorHeaders = authHelper.headersForUser(coord);

        // Clean any tenant policy left from sibling tests + invalidate cache
        // (per the same pattern used by ReferralEscalationFrozenPolicyTest).
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            conn.createStatement().execute("RESET ROLE");
            try (var ps = conn.prepareStatement(
                    "DELETE FROM escalation_policy WHERE tenant_id = ?")) {
                ps.setObject(1, authHelper.getTestTenantId());
                ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement(
                    "DELETE FROM audit_events WHERE action = 'ESCALATION_POLICY_UPDATED'")) {
                ps.executeUpdate();
            }
            conn.createStatement().execute("SET ROLE fabt_app");
        } catch (java.sql.SQLException e) {
            throw new RuntimeException("setUp cleanup failed", e);
        }
        escalationPolicyService.clearCaches_testOnly();
    }

    @AfterEach
    void tearDown() {
        // Sibling tests (e.g. ReferralEscalationIntegrationTest) expect a
        // clean tenant policy state — wipe what we added.
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            conn.createStatement().execute("RESET ROLE");
            try (var ps = conn.prepareStatement(
                    "DELETE FROM escalation_policy WHERE tenant_id = ?")) {
                ps.setObject(1, authHelper.getTestTenantId());
                ps.executeUpdate();
            }
            conn.createStatement().execute("SET ROLE fabt_app");
        } catch (java.sql.SQLException e) {
            throw new RuntimeException("tearDown cleanup failed", e);
        }
        escalationPolicyService.clearCaches_testOnly();
    }

    @Test
    @DisplayName("T-28a: GET falls back to seeded platform default when tenant has none")
    void getFallsBackToPlatformDefault() {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/admin/escalation-policy/dv-referral",
                HttpMethod.GET, new HttpEntity<>(cocAdminHeaders), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        String body = resp.getBody();
        assertThat(body).isNotNull();
        // Platform default has tenant_id = NULL and createdBy = NULL. The
        // project's Jackson config (application.yml) sets
        // default-property-inclusion: non_null — so null fields are OMITTED
        // from the JSON entirely, not serialized as ":null". Assert their
        // absence rather than expecting a literal null.
        assertThat(body).doesNotContain("tenantId");
        assertThat(body).doesNotContain("createdBy");

        // The four seeded thresholds and their severities are present.
        assertThat(body).contains("\"version\":1");
        assertThat(body).contains("\"id\":\"1h\"");
        assertThat(body).contains("\"id\":\"2h\"");
        assertThat(body).contains("\"id\":\"3_5h\"");
        assertThat(body).contains("\"id\":\"4h\"");
        assertThat(body).contains("ACTION_REQUIRED");
        assertThat(body).contains("CRITICAL");
    }

    @Test
    @DisplayName("T-28b: PATCH inserts a new tenant policy version and returns it")
    void patchInsertsNewVersionAndReturnsIt() {
        String body = """
                {
                  "thresholds": [
                    {"id":"30m","at":"PT30M","severity":"INFO","recipients":["COORDINATOR"]},
                    {"id":"1h","at":"PT1H","severity":"ACTION_REQUIRED","recipients":["COORDINATOR"]},
                    {"id":"2h","at":"PT2H","severity":"CRITICAL","recipients":["COC_ADMIN"]}
                  ]
                }
                """;
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/admin/escalation-policy/dv-referral",
                HttpMethod.PATCH,
                new HttpEntity<>(body, jsonHeaders(cocAdminHeaders)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        String respBody = resp.getBody();
        assertThat(respBody).isNotNull();
        assertThat(respBody).contains("\"version\":1"); // first tenant version
        assertThat(respBody).contains("\"id\":\"30m\"");
        assertThat(respBody).contains("\"createdBy\":\"" + cocAdmin.getId() + "\"");

        // Audit row exists.
        // NOTE: audit_events.details is JSONB; querying ::text emits the
        // PostgreSQL canonical form `"key": value` (with a space after the
        // colon), not Jackson's `"key":value`. Normalize whitespace before
        // matching so the assertion works regardless of format.
        List<AuditRow> auditRows = findAudit("ESCALATION_POLICY_UPDATED");
        assertThat(auditRows).hasSize(1);
        assertThat(auditRows.get(0).actorUserId()).isEqualTo(cocAdmin.getId());
        String detailsCompact = auditRows.get(0).details().replaceAll("\\s+", "");
        assertThat(detailsCompact).contains("\"eventType\":\"dv-referral\"");
        assertThat(detailsCompact).contains("\"version\":1");
        // First-tenant-version PATCH has previousVersion omitted (Casey #3 contract).
        assertThat(detailsCompact).doesNotContain("previousVersion");

        // GET now returns the new tenant policy (not the platform default).
        ResponseEntity<String> getResp = restTemplate.exchange(
                "/api/v1/admin/escalation-policy/dv-referral",
                HttpMethod.GET, new HttpEntity<>(cocAdminHeaders), String.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String getBody = getResp.getBody();
        assertThat(getBody).contains("\"id\":\"30m\"");
        // tenantId should now be the test tenant, not null.
        assertThat(getBody).contains("\"tenantId\":\"" + authHelper.getTestTenantId() + "\"");
    }

    @Test
    @DisplayName("T-28c: PATCH with non-monotonic durations returns 400")
    void patchNonMonotonicReturns400() {
        String body = """
                {
                  "thresholds": [
                    {"id":"a","at":"PT2H","severity":"INFO","recipients":["COORDINATOR"]},
                    {"id":"b","at":"PT1H","severity":"INFO","recipients":["COORDINATOR"]}
                  ]
                }
                """;
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/admin/escalation-policy/dv-referral",
                HttpMethod.PATCH,
                new HttpEntity<>(body, jsonHeaders(cocAdminHeaders)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("T-28d: PATCH with invalid role returns 400")
    void patchInvalidRoleReturns400() {
        String body = """
                {
                  "thresholds": [
                    {"id":"1h","at":"PT1H","severity":"INFO","recipients":["JANITOR"]}
                  ]
                }
                """;
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/admin/escalation-policy/dv-referral",
                HttpMethod.PATCH,
                new HttpEntity<>(body, jsonHeaders(cocAdminHeaders)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("T-28e: PATCH with malformed ISO duration returns 400")
    void patchMalformedDurationReturns400() {
        String body = """
                {
                  "thresholds": [
                    {"id":"1h","at":"one-hour","severity":"INFO","recipients":["COORDINATOR"]}
                  ]
                }
                """;
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/admin/escalation-policy/dv-referral",
                HttpMethod.PATCH,
                new HttpEntity<>(body, jsonHeaders(cocAdminHeaders)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("T-28g: second PATCH records previousVersion in audit (Casey Drummond #3)")
    void secondPatchRecordsPreviousVersion() {
        // First PATCH — version 1, no previousVersion in audit (Casey #3 contract).
        String body1 = """
                {
                  "thresholds": [
                    {"id":"1h","at":"PT1H","severity":"ACTION_REQUIRED","recipients":["COORDINATOR"]},
                    {"id":"2h","at":"PT2H","severity":"CRITICAL","recipients":["COC_ADMIN"]}
                  ]
                }
                """;
        ResponseEntity<String> first = restTemplate.exchange(
                "/api/v1/admin/escalation-policy/dv-referral",
                HttpMethod.PATCH,
                new HttpEntity<>(body1, jsonHeaders(cocAdminHeaders)),
                String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second PATCH — version 2 must record previousVersion=1 in audit
        // details so a subpoena can answer "what changed?" without joining
        // back to escalation_policy. (Casey Drummond war room round 3.)
        String body2 = """
                {
                  "thresholds": [
                    {"id":"30m","at":"PT30M","severity":"INFO","recipients":["COORDINATOR"]},
                    {"id":"1h","at":"PT1H","severity":"ACTION_REQUIRED","recipients":["COORDINATOR"]},
                    {"id":"2h","at":"PT2H","severity":"CRITICAL","recipients":["COC_ADMIN"]}
                  ]
                }
                """;
        ResponseEntity<String> second = restTemplate.exchange(
                "/api/v1/admin/escalation-policy/dv-referral",
                HttpMethod.PATCH,
                new HttpEntity<>(body2, jsonHeaders(cocAdminHeaders)),
                String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<AuditRow> auditRows = findAudit("ESCALATION_POLICY_UPDATED");
        assertThat(auditRows).hasSize(2);

        // Find the v=2 row by content. We can't rely on insertion order
        // alone because the test doesn't sort.
        AuditRow v2Row = auditRows.stream()
                .filter(r -> r.details().replaceAll("\\s+", "").contains("\"version\":2"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("v=2 audit row not found among: " + auditRows));

        String v2Compact = v2Row.details().replaceAll("\\s+", "");
        assertThat(v2Compact)
                .as("Second PATCH must record previousVersion in audit details (Casey Drummond #3)")
                .contains("\"previousVersion\":1");
        assertThat(v2Compact).contains("\"version\":2");
        assertThat(v2Compact).contains("\"eventType\":\"dv-referral\"");

        // The v=1 row must NOT have previousVersion (first tenant version
        // has no prior — Casey's contract).
        AuditRow v1Row = auditRows.stream()
                .filter(r -> {
                    String c = r.details().replaceAll("\\s+", "");
                    return c.contains("\"version\":1") && !c.contains("\"previousVersion\"");
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError("v=1 audit row not found"));
        assertThat(v1Row.details().replaceAll("\\s+", ""))
                .as("First-tenant-version PATCH must NOT include previousVersion")
                .doesNotContain("previousVersion");
    }

    @Test
    @DisplayName("T-28f: COORDINATOR is rejected with 403 on both GET and PATCH")
    void coordinatorRejected() {
        ResponseEntity<String> getResp = restTemplate.exchange(
                "/api/v1/admin/escalation-policy/dv-referral",
                HttpMethod.GET, new HttpEntity<>(coordinatorHeaders), String.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String body = """
                {"thresholds":[{"id":"1h","at":"PT1H","severity":"INFO","recipients":["COORDINATOR"]}]}
                """;
        ResponseEntity<String> patchResp = restTemplate.exchange(
                "/api/v1/admin/escalation-policy/dv-referral",
                HttpMethod.PATCH,
                new HttpEntity<>(body, jsonHeaders(coordinatorHeaders)),
                String.class);
        assertThat(patchResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private record AuditRow(UUID actorUserId, String details) {}

    private HttpHeaders jsonHeaders(HttpHeaders base) {
        HttpHeaders h = new HttpHeaders();
        h.putAll(base);
        h.set("Content-Type", "application/json");
        return h;
    }

    private List<AuditRow> findAudit(String action) {
        try (var conn = jdbcTemplate.getDataSource().getConnection()) {
            conn.createStatement().execute("RESET ROLE");
            try (var ps = conn.prepareStatement(
                    "SELECT actor_user_id, details::text FROM audit_events WHERE action = ?")) {
                ps.setString(1, action);
                var rs = ps.executeQuery();
                List<AuditRow> out = new java.util.ArrayList<>();
                while (rs.next()) {
                    out.add(new AuditRow((UUID) rs.getObject(1), rs.getString(2)));
                }
                conn.createStatement().execute("SET ROLE fabt_app");
                return out;
            }
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
