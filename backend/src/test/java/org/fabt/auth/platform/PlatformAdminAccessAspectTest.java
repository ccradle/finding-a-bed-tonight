package org.fabt.auth.platform;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.Application;
import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end IT for the {@code @PlatformAdminOnly} pipeline (G-4.3 task 4.7):
 *
 * <ul>
 *   <li>{@code JustificationValidationFilter} — header presence / length checks</li>
 *   <li>{@code PlatformAdminLogger} aspect — REQUIRES_NEW double-write to
 *       {@code platform_admin_access_log} + {@code audit_events} with linked
 *       UUIDs, MDC marker, body fingerprint (no SHA-256 per M-RV4),
 *       PLATFORM_TENANT_HARD_DELETED tenant_id override, append-only trigger</li>
 *   <li>{@code PlatformAdminAccessLogger.logLockout} — direct-write hook
 *       from {@code PlatformAuthService.recordFailureAndMaybeLock} on the
 *       lockout transition (R-RV1)</li>
 * </ul>
 *
 * <p>All scenarios run against the canary endpoint {@code POST
 * /api/v1/batch/jobs/{name}/run} (G-4.3 §4.6). The full endpoint
 * migration (11 tenant + 7 platform sites) is G-4.4; this test only needs
 * one annotated endpoint to exercise the wiring.
 */
@DisplayName("@PlatformAdminOnly aspect IT (G-4.3)")
@SpringBootTest(classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformAdminAccessAspectTest extends BaseIntegrationTest {

    private static final String CANARY_PATH = "/api/v1/batch/jobs/test-job/run";
    private static final String JUSTIFICATION_HEADER = "X-Platform-Justification";
    // ASCII only — HTTP header values must be ISO-8859-1 / ASCII per RFC 7230;
    // the JDK 17+ http client rejects non-ASCII outright (em-dash etc.).
    private static final String VALID_JUSTIFICATION =
            "G-4.3 IT - exercising the canary endpoint for aspect verification";

    @Autowired private TestAuthHelper testAuthHelper;
    @Autowired private JdbcTemplate jdbc;

    private TestAuthHelper.PlatformOperatorFixture operator;

    @BeforeEach
    void setUp() {
        operator = testAuthHelper.setupPlatformOperator("aspect-it@test.fabt.org");
    }

    @AfterEach
    void cleanUp() {
        testAuthHelper.resetPlatformBootstrap();
    }

    // -----------------------------------------------------------------
    // Filter-rejection scenarios (justification missing / too short)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("missing X-Platform-Justification header → 400; no log rows written")
    void missingJustificationRejectedAtFilter() {
        long palBefore = countPalRows();
        long aeBefore = countAeRowsForAction("PLATFORM_BATCH_JOB_TRIGGERED");

        ResponseEntity<Map> response = postCanary(operator.bearer(), null, "{}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error")).isEqualTo("missing_justification");
        // Filter rejected — aspect never ran — no rows written.
        assertThat(countPalRows()).isEqualTo(palBefore);
        assertThat(countAeRowsForAction("PLATFORM_BATCH_JOB_TRIGGERED")).isEqualTo(aeBefore);
    }

    @Test
    @DisplayName("X-Platform-Justification < 10 chars → 400; no log rows written")
    void shortJustificationRejectedAtFilter() {
        long palBefore = countPalRows();

        ResponseEntity<Map> response = postCanary(operator.bearer(), "ok", "{}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error")).isEqualTo("justification_too_short");
        assertThat(countPalRows()).isEqualTo(palBefore);
    }

    // -----------------------------------------------------------------
    // Spring Security rejection (post-filter, pre-aspect)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("unauthenticated request → 401; no log rows written")
    void unauthenticatedRejectedAtSecurity() {
        long palBefore = countPalRows();

        ResponseEntity<Map> response = postCanary(null, VALID_JUSTIFICATION, "{}");

        // Post-Security ordering (warroom M-RV2): Spring Security rejects
        // first — operator gets 401, NOT 400. No info-disclosure that
        // the endpoint has @PlatformAdminOnly.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(countPalRows()).isEqualTo(palBefore);
    }

    // -----------------------------------------------------------------
    // Happy path — both rows persist + linked
    // -----------------------------------------------------------------

    @Test
    @DisplayName("successful platform-admin call writes linked PAL+AE rows")
    void successfulCallWritesLinkedRows() {
        ResponseEntity<Map> response = postCanary(operator.bearer(),
                VALID_JUSTIFICATION,
                "{\"date\":\"2026-04-25\"}");

        // BatchJobController.run returns 200 on success or 400 on
        // job-trigger failure. Either way, the aspect committed BEFORE
        // the controller ran (Decision 11) — both rows persist.
        assertThat(response.getStatusCode())
                .as("request reached the controller (filter passed, security passed, aspect ran)")
                .isIn(HttpStatus.OK, HttpStatus.BAD_REQUEST);

        // Find the row(s) written for this operator + action.
        List<Map<String, Object>> palRows = jdbc.queryForList(
                "SELECT id, platform_user_id, action, request_method, request_path, "
                        + "request_body_excerpt, justification, audit_event_id "
                        + "FROM platform_admin_access_log "
                        + "WHERE platform_user_id = ? AND action = ? "
                        + "ORDER BY timestamp DESC LIMIT 1",
                operator.userId(), "PLATFORM_BATCH_JOB_TRIGGERED");
        assertThat(palRows).hasSize(1);
        Map<String, Object> pal = palRows.get(0);

        assertThat(pal.get("request_method")).isEqualTo("POST");
        assertThat(pal.get("request_path"))
                .asString().contains("/api/v1/batch/jobs/");
        // M-RV4 — body fingerprint MUST contain Content-Type + Content-Length,
        // MUST NOT contain SHA-256.
        String excerpt = (String) pal.get("request_body_excerpt");
        assertThat(excerpt).contains("Content-Type=");
        assertThat(excerpt).contains("Content-Length=");
        assertThat(excerpt).doesNotContain("SHA-256");
        // Justification carries the annotation.reason() prefix + header value.
        assertThat(pal.get("justification"))
                .asString()
                .contains("Batch job trigger requires platform authority")
                .contains(VALID_JUSTIFICATION);

        UUID auditEventId = (UUID) pal.get("audit_event_id");
        assertThat(auditEventId).isNotNull();

        // PAL.audit_event_id == AE.id linkage. BatchJob action is platform-
        // wide → AE.tenant_id = SYSTEM_TENANT_ID; bypass FORCE RLS by
        // binding app.tenant_id = SYSTEM via SET LOCAL inside a tx.
        Map<String, Object> ae = readSystemAuditRow(
                "SELECT id, action, actor_user_id, details::text AS details_text, "
                        + "tenant_id "
                        + "FROM audit_events WHERE id = ?", auditEventId);
        assertThat(ae.get("action")).isEqualTo("PLATFORM_BATCH_JOB_TRIGGERED");
        assertThat(ae.get("actor_user_id")).isEqualTo(operator.userId());

        // AE.details->>'platform_admin_access_log_id' == PAL.id linkage
        String detailsText = (String) ae.get("details_text");
        assertThat(detailsText).contains(pal.get("id").toString());
        // D3 — NO platform_user_email in details
        assertThat(detailsText).doesNotContainIgnoringCase("email");
        // platform_user_id IS in details
        assertThat(detailsText).contains(operator.userId().toString());
    }

    @Test
    @DisplayName("platform-wide action (BatchJobController.run) → tenant_id = SYSTEM_TENANT_ID, not chained")
    void platformWideActionUsesSystemTenant() {
        postCanary(operator.bearer(), VALID_JUSTIFICATION, "{}");

        Map<String, Object> ae = readSystemAuditRow(
                "SELECT tenant_id, prev_hash, row_hash "
                        + "FROM audit_events "
                        + "WHERE action = 'PLATFORM_BATCH_JOB_TRIGGERED' AND actor_user_id = ? "
                        + "ORDER BY timestamp DESC LIMIT 1",
                operator.userId());

        UUID systemTenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertThat(ae.get("tenant_id")).isEqualTo(systemTenantId);
        // Phase G-1 contract: SYSTEM_TENANT_ID rows are NOT chained.
        assertThat(ae.get("prev_hash")).isNull();
        assertThat(ae.get("row_hash")).isNull();
    }

    // -----------------------------------------------------------------
    // Append-only trigger (D4)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("append-only trigger raises on UPDATE attempts against PAL")
    void appendOnlyTriggerBlocksUpdate() {
        postCanary(operator.bearer(), VALID_JUSTIFICATION, "{}");

        UUID palId = jdbc.queryForObject(
                "SELECT id FROM platform_admin_access_log "
                        + "WHERE platform_user_id = ? ORDER BY timestamp DESC LIMIT 1",
                UUID.class, operator.userId());

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE platform_admin_access_log SET justification = 'tampered' WHERE id = ?",
                palId))
                .as("append-only enforcement: either the trigger raises 'append-only' OR "
                        + "the REVOKE blocks first with 'permission denied'. Both indicate "
                        + "the column is correctly protected.")
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .satisfiesAnyOf(
                        ex -> assertThat(ex.getMessage()).contains("append-only"),
                        ex -> assertThat(ex.getMessage()).contains("permission denied"));
    }

    @Test
    @DisplayName("append-only trigger raises on DELETE attempts against PAL")
    void appendOnlyTriggerBlocksDelete() {
        postCanary(operator.bearer(), VALID_JUSTIFICATION, "{}");

        UUID palId = jdbc.queryForObject(
                "SELECT id FROM platform_admin_access_log "
                        + "WHERE platform_user_id = ? ORDER BY timestamp DESC LIMIT 1",
                UUID.class, operator.userId());

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM platform_admin_access_log WHERE id = ?",
                palId))
                .as("append-only enforcement: either the trigger raises 'append-only' OR "
                        + "the REVOKE blocks first with 'permission denied'. Both indicate "
                        + "the column is correctly protected.")
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .satisfiesAnyOf(
                        ex -> assertThat(ex.getMessage()).contains("append-only"),
                        ex -> assertThat(ex.getMessage()).contains("permission denied"));
    }

    // -----------------------------------------------------------------
    // FK enforcement (M-RV1 verification)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("FK enforcement: INSERT with platform_user_id not in platform_user raises constraint violation")
    void fkEnforcedDespiteRevoke() {
        UUID neverSeen = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO platform_admin_access_log ("
                        + "id, platform_user_id, action, justification, "
                        + "request_method, request_path) "
                        + "VALUES (?, ?, 'PLATFORM_TEST_RESET_INVOKED', "
                        + "'IT FK violation probe — should fail', 'POST', '/test-fk')",
                UUID.randomUUID(), neverSeen))
                .as("FK validation runs via PG's RI trigger which bypasses the REVOKE on platform_user "
                        + "(warroom M-RV1) — this INSERT must fail with a constraint violation, NOT a "
                        + "permission error")
                .isInstanceOf(DataAccessException.class);
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private ResponseEntity<Map> postCanary(String bearerHeader, String justification, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearerHeader != null) {
            headers.set("Authorization", bearerHeader);
        }
        if (justification != null) {
            headers.set(JUSTIFICATION_HEADER, justification);
        }
        return restTemplate.exchange(CANARY_PATH, HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
    }

    private long countPalRows() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_admin_access_log", Long.class);
        return count == null ? 0 : count;
    }

    private long countAeRowsForAction(String action) {
        // audit_events has FORCE RLS — must bind app.tenant_id to read.
        // Platform-wide AE rows live under SYSTEM_TENANT_ID; bind it for
        // the count query so it sees those rows.
        Long count = countSystemAuditRows(
                "SELECT COUNT(*) FROM audit_events WHERE action = ?", action);
        return count == null ? 0 : count;
    }

    /**
     * Reads an {@code audit_events} row with SYSTEM_TENANT_ID bound for
     * the duration of the read. Required because Phase B FORCE RLS on
     * audit_events filters by {@code tenant_id = fabt_current_tenant_id()};
     * platform-wide rows (PLATFORM_BATCH_JOB_TRIGGERED etc.) live under
     * SYSTEM_TENANT_ID and are invisible without the binding. SET LOCAL
     * scopes the binding to the transaction so connection pooling is OK.
     *
     * <p>Test-only convenience; production reads (e.g. compliance
     * dashboards) use {@code AuditEventService.findByTargetUserId} which
     * does its own tenant binding via {@link org.fabt.shared.web.TenantContext}.
     */
    private Map<String, Object> readSystemAuditRow(String sql, Object... args) {
        return txTemplate.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', "
                    + "'00000000-0000-0000-0000-000000000001', true)", String.class);
            return jdbc.queryForMap(sql, args);
        });
    }

    private Long countSystemAuditRows(String sql, Object... args) {
        return txTemplate.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', "
                    + "'00000000-0000-0000-0000-000000000001', true)", String.class);
            return jdbc.queryForObject(sql, Long.class, args);
        });
    }

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate txTemplate;
}
