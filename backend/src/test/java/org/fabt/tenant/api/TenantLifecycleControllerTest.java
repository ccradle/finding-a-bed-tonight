package org.fabt.tenant.api;

import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-4.6 controller IT for {@link TenantLifecycleController}. Covers the
 * thin slice the service IT can't reach: HTTP shape, the
 * {@code @PreAuthorize PLATFORM_OPERATOR} gate, the
 * {@code @PlatformAdminOnly} aspect's PAL+AE chain commit ordering,
 * and the {@code X-Platform-Justification} filter.
 *
 * <p>The state machine + Phase F crypto-shred behavior is exercised by
 * {@code TenantLifecycleServiceIntegrationTest} at the service layer.
 * This file confirms the new HTTP surface enforces what the
 * platform-admin-split-and-access-log change spec promised.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // TenantLifecycleService + TenantLifecycleController are both
        // @ConditionalOnProperty(fabt.tenant.lifecycle.enabled=true).
        // Default is false (the legacy lazy-bootstrap path remains the
        // production posture for now). Enable here so the controller bean
        // exists and the IT can hit /api/v1/tenants/{id}/suspend etc.
        properties = "fabt.tenant.lifecycle.enabled=true")
class TenantLifecycleControllerTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JdbcTemplate jdbc;

    /** SYSTEM tenant id from TenantContext.SYSTEM_TENANT_ID — used for hard-delete audit row check. */
    private static final UUID SYSTEM_TENANT_ID = TenantContext.SYSTEM_TENANT_ID;

    @BeforeEach
    void setUp() {
        // The primary test tenant exists so authHelper.platformOperatorHeaders
        // can mint a platform-operator JWT. Lifecycle tests below operate on
        // SECONDARY tenants so suspend/offboard/hardDelete don't poison shared
        // state across tests.
        authHelper.setupTestTenant();
    }

    // =========================================================================
    // Happy-path state transitions
    // =========================================================================

    @Test
    @DisplayName("POST /suspend → 200 + tenant.state=SUSPENDED + PAL row + chained AE row")
    void suspend_happyPath_writesAuditRows() {
        UUID target = setupSecondaryTenant("g46-suspend").getId();
        long palBefore = countPalRows();

        ResponseEntity<Map<String, Object>> response = postLifecycle(
                target, "suspend", "G-4.6 IT - suspend " + target);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        // TenantResponse doesn't expose state — query DB directly.
        assertEquals("SUSPENDED", queryTenantState(target));

        // Aspect committed ONE PAL row before service.suspend ran (Decision 11).
        assertEquals(palBefore + 1, countPalRows(),
                "aspect must commit exactly one PAL row pre-proceed");
        // Audit row chained in TARGET tenant's chain (Decision 13).
        assertEquals(1L, countAuditEventForActionInTenant("PLATFORM_TENANT_SUSPENDED", target));
        // Service-emitted domain-event row (different action enum, same chain).
        // Warroom MEDIUM (Riley) — without this assertion a DetachedAuditPersister
        // regression would land the operator-action row but silently drop the
        // domain row, and this IT would still pass green.
        assertTrue(countAuditEventForActionInTenant("TENANT_SUSPENDED", target) >= 1L,
                "service must also emit TENANT_SUSPENDED domain-event row in target chain");
    }

    @Test
    @DisplayName("POST /unsuspend → 200 + tenant.state=ACTIVE + PAL+AE rows")
    void unsuspend_happyPath() {
        UUID target = setupSecondaryTenant("g46-unsuspend").getId();
        // Pre-suspend so the state-machine accepts unsuspend.
        postLifecycle(target, "suspend", "g46-unsuspend pre-condition");

        ResponseEntity<Map<String, Object>> response = postLifecycle(
                target, "unsuspend", "G-4.6 IT - unsuspend " + target);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("ACTIVE", queryTenantState(target));
        assertEquals(1L, countAuditEventForActionInTenant("PLATFORM_TENANT_UNSUSPENDED", target));
        // Service-emitted domain-event row — see suspend test for rationale.
        assertTrue(countAuditEventForActionInTenant("TENANT_UNSUSPENDED", target) >= 1L,
                "service must also emit TENANT_UNSUSPENDED domain-event row in target chain");
    }

    @Test
    @DisplayName("POST /offboard → 200 + tenant.state=OFFBOARDING + offboard_export_receipt_uri set + PAL+AE rows")
    void offboard_happyPath() {
        UUID target = setupSecondaryTenant("g46-offboard").getId();

        ResponseEntity<Map<String, Object>> response = postLifecycle(
                target, "offboard", "G-4.6 IT - offboard " + target);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        // FSM state name is OFFBOARDING (intermediate, ARCHIVED follows);
        // the action enum is PLATFORM_TENANT_OFFBOARDED (semantic label).
        // Per TenantLifecycleService.doOffboard the offboard transition does
        // NOT stamp archived_at — that's the OFFBOARDING→ARCHIVED hop's job
        // (doArchive). What offboard MUST set is offboard_export_receipt_uri,
        // since GDPR Art-20 sequencing requires the export to land before
        // archive can run. Asserting on that contract instead.
        assertEquals("OFFBOARDING", queryTenantState(target));
        String exportUri = jdbc.queryForObject(
                "SELECT offboard_export_receipt_uri FROM tenant WHERE id = ?",
                String.class, target);
        assertNotNull(exportUri,
                "offboard must persist offboard_export_receipt_uri (GDPR Art-20 sequencing gate)");
        assertEquals(1L, countAuditEventForActionInTenant("PLATFORM_TENANT_OFFBOARDED", target));
        // Service-emitted domain-event row — note action is TENANT_OFFBOARDING_STARTED
        // (the verb the service uses; aspect's PLATFORM_TENANT_OFFBOARDED is the
        // operator-action label). See suspend test for rationale.
        assertTrue(countAuditEventForActionInTenant("TENANT_OFFBOARDING_STARTED", target) >= 1L,
                "service must also emit TENANT_OFFBOARDING_STARTED domain-event row in target chain");
    }

    @Test
    @DisplayName("DELETE /tenants/{id} → 204 + tenant row gone + AE row in SYSTEM_TENANT chain (Decision 13)")
    void hardDelete_happyPath_auditRowInSystemChain() {
        UUID target = setupSecondaryTenant("g46-harddelete").getId();
        // State machine requires ARCHIVED before hardDelete (FSM:
        // ACTIVE→OFFBOARDING→ARCHIVED→DELETED). The offboard endpoint
        // moves to OFFBOARDING; the OFFBOARDING→ARCHIVED transition is
        // not exposed via REST (typically handled by a sweep job in
        // Phase F+). For this IT, advance the state directly via JDBC
        // — and stamp archived_at to a date past the 30-day retention
        // window, since TenantLifecycleService.doHardDelete checks BOTH
        // (a) archived_at IS NOT NULL — pure data-integrity gate, and
        // (b) now() >= archived_at + retention-days — the GDPR Art-17
        // industry-practice waiting period. The IT exercises the real
        // production retention check rather than overriding the prop;
        // a dated archived_at proves the retention gate fires when the
        // window has elapsed without weakening the prod default config.
        postLifecycle(target, "offboard", "g46-harddelete pre-condition: offboard first");
        jdbc.update("UPDATE tenant SET state = 'ARCHIVED', archived_at = NOW() - INTERVAL '31 days' "
                + "WHERE id = ?", target);

        HttpHeaders headers = authHelper.platformOperatorHeaders(
                "G-4.6 IT - hard-delete " + target + " (irreversible)");
        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/tenants/" + target,
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Void.class);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());

        // Tenant row is gone (cascade deleted by Phase F).
        Integer surviving = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant WHERE id = ?",
                Integer.class, target);
        assertEquals(0, surviving, "hard-delete must cascade-delete the tenant row");

        // Audit row is forced to SYSTEM_TENANT_ID per Decision 13 so it
        // survives the cascade. Query under SYSTEM tenant context.
        long systemRows = countAuditEventForActionInTenant(
                "PLATFORM_TENANT_HARD_DELETED", SYSTEM_TENANT_ID);
        assertTrue(systemRows >= 1L,
                "PLATFORM_TENANT_HARD_DELETED must land in SYSTEM_TENANT_ID chain so it survives cascade");
    }

    // =========================================================================
    // State-machine rejection (per task §6a.4: aspect's audit row WAS committed)
    // =========================================================================

    @Test
    @DisplayName("POST /suspend on already-SUSPENDED → 409, but PAL row from the rejected attempt persists (Decision 11)")
    void suspendAlreadySuspended_rejected_butPalPersists() {
        UUID target = setupSecondaryTenant("g46-double-suspend").getId();
        postLifecycle(target, "suspend", "g46-double-suspend first");

        long palBefore = countPalRows();
        // Second suspend attempt — service throws IllegalStateTransitionException.
        ResponseEntity<Map<String, Object>> response = postLifecycle(
                target, "suspend", "g46-double-suspend second (should reject)");

        // TenantLifecycleExceptionAdvice maps state-machine violations to 409.
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());

        // Decision 11: aspect commits PAL BEFORE proceed(). The service-layer
        // throw happens AFTER the aspect's commit, so the PAL row persists
        // recording the ATTEMPT. Operator runbook correlates this PAL row
        // with the TENANT_SUSPEND_REJECTED audit event the service emits.
        assertEquals(palBefore + 1, countPalRows(),
                "rejected attempt still writes a PAL row per the pre-proceed commit ordering");
        // Service emits a TENANT_SUSPEND_REJECTED row via DetachedAuditPersister
        // (REQUIRES_NEW) BEFORE re-throwing the FSM rejection. This is the row
        // operators correlate against the PAL row to distinguish "attempted but
        // FSM-blocked" from "attempted and FSM-allowed but service-failure".
        // Without this assertion, a regression in wrapAttemptAudit would silently
        // drop the rejection-trail audit row.
        assertTrue(countAuditEventForActionInTenant("TENANT_SUSPEND_REJECTED", target) >= 1L,
                "service must emit TENANT_SUSPEND_REJECTED via DetachedAuditPersister on FSM reject");
    }

    // =========================================================================
    // Authorization gates
    // =========================================================================

    @Test
    @DisplayName("Missing X-Platform-Justification → 400 (JustificationValidationFilter)")
    void missingJustification_400() {
        UUID target = setupSecondaryTenant("g46-no-justification").getId();
        long palBefore = countPalRows();

        // Build platform-operator bearer WITHOUT justification header.
        HttpHeaders bareHeaders = new HttpHeaders();
        bareHeaders.set("Authorization",
                authHelper.platformOperatorHeaders("seed").getFirst("Authorization"));
        bareHeaders.set("Content-Type", "application/json");

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/tenants/" + target + "/suspend",
                HttpMethod.POST,
                new HttpEntity<>(bareHeaders),
                new ParameterizedTypeReference<>() {});

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        // Filter rejected pre-aspect — no PAL row written.
        assertEquals(palBefore, countPalRows(),
                "filter rejection happens before aspect commits — no PAL row");
    }

    @Test
    @DisplayName("COC_ADMIN tenant JWT → 403 (PLATFORM_OPERATOR-only endpoint)")
    void cocAdminTenantJwt_403() {
        UUID target = setupSecondaryTenant("g46-cocadmin-blocked").getId();
        User cocAdmin = authHelper.createUserInTenant(authHelper.getTestTenantId(),
                "g46-coc-" + UUID.randomUUID() + "@test.fabt.org",
                "G-4.6 CoC Admin", new String[]{"COC_ADMIN"}, false);

        HttpHeaders headers = authHelper.withJustification(
                authHelper.headersForUser(cocAdmin),
                "G-4.6 IT - COC_ADMIN must not be able to lifecycle tenants");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/tenants/" + target + "/suspend",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class);

        // Spring Security URL rule + @PreAuthorize both reject — 403.
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private org.fabt.tenant.domain.Tenant setupSecondaryTenant(String slugBase) {
        // Per-test unique slug — secondary tenants persist across tests
        // when reused with the same slug, but each test wants a fresh
        // tenant to control state-machine pre-conditions.
        // setupSecondaryTenantWithKeyMaterial bootstraps the gen-1 JWT
        // key material so the lifecycle service's suspend flow can bump
        // jwt_key_generation; see helper Javadoc for full rationale.
        return authHelper.setupSecondaryTenantWithKeyMaterial(
                slugBase + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private ResponseEntity<Map<String, Object>> postLifecycle(UUID tenantId, String action, String justification) {
        HttpHeaders headers = authHelper.platformOperatorHeaders(justification);
        return restTemplate.exchange(
                "/api/v1/tenants/" + tenantId + "/" + action,
                HttpMethod.POST,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});
    }

    /**
     * TenantResponse doesn't expose state/archived_at fields (it's the
     * thin DTO from G-4.4). Query the tenant table directly. Tenant
     * itself has no FORCE RLS today (per `feedback_per_user_rls_wrong_pattern`)
     * so a bare query under fabt_app works without TenantContext bind.
     */
    private String queryTenantState(UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT state FROM tenant WHERE id = ?",
                String.class, tenantId);
    }

    private long countPalRows() {
        // PAL = platform_admin_access_log (V89). FORCE RLS on tenant_id —
        // bind SYSTEM context so the read sees rows committed under SYSTEM
        // chain (the aspect commits some PAL rows under the target tenant
        // chain too; a global count requires SYSTEM bind).
        return TenantContext.callWithContext(SYSTEM_TENANT_ID, true,
                () -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM platform_admin_access_log",
                        Long.class));
    }

    private long countAuditEventForActionInTenant(String action, UUID tenantId) {
        return TenantContext.callWithContext(tenantId, true,
                () -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_events WHERE action = ? AND tenant_id = ?",
                        Long.class, action, tenantId));
    }
}
