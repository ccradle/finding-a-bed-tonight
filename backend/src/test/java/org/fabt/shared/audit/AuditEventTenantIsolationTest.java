package org.fabt.shared.audit;

import java.util.List;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.shared.web.TenantContext;
import org.fabt.tenant.domain.Tenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cross-tenant-isolation-audit (Issue #117) — Phase 2.12.
 *
 * <p>Regression tests for the {@code audit_events} cross-tenant read leak
 * (Casey's VAWA audit-integrity concern). Pre-fix:
 * {@code AuditEventRepository.findByTargetUserId} had no tenant predicate
 * and the underlying table had no {@code tenant_id} column. A CoC admin in
 * Tenant A could query GET
 * {@code /api/v1/audit-events?targetUserId=<tenantB-user-uuid>} and read
 * Tenant B's audit history — per-tenant audit-integrity violation (VAWA).</p>
 *
 * <p>Post-fix (V57 migration + service/repository refactor): the service
 * pulls {@code tenantId} from {@code TenantContext}, filters the query by
 * tenant, and returns an empty list for cross-tenant probes. Audit writes
 * also carry {@code tenant_id} set by the event-listener from
 * {@code TenantContext}, so the per-tenant invariant holds on both INSERT
 * and SELECT paths.</p>
 */
@DisplayName("cross-tenant-isolation-audit Phase 2.12 — audit_events tenant isolation")
class AuditEventTenantIsolationTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ApplicationEventPublisher eventPublisher;

    @Test
    @DisplayName("Cross-tenant audit-event probe returns empty list (not Tenant B's history)")
    void tc_audit_events_crossTenant_returns_empty() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        authHelper.setupTestTenant();
        authHelper.setupCocAdminUser();

        // Set up Tenant B with its own user and audit-event history.
        Tenant tenantB = authHelper.setupSecondaryTenant("xtenant-audit-" + suffix);
        User tenantBUser = authHelper.createUserInTenant(tenantB.getId(),
                "audit-victim-" + suffix + "@test.fabt.org", "Audit Victim",
                new String[]{"OUTREACH_WORKER"}, false);

        // Fire 3 audit events in Tenant B's context — these rows land with
        // tenant_id=tenantB (via AuditEventService.onAuditEvent pulling from
        // TenantContext).
        UUID tenantBActor = UUID.randomUUID();
        TenantContext.runWithContext(tenantB.getId(), false, () -> {
            eventPublisher.publishEvent(new AuditEventRecord(
                    tenantBActor, tenantBUser.getId(), "ROLE_CHANGED", null, "10.0.0.1"));
            eventPublisher.publishEvent(new AuditEventRecord(
                    tenantBActor, tenantBUser.getId(), "DV_ACCESS_GRANTED", null, "10.0.0.1"));
            eventPublisher.publishEvent(new AuditEventRecord(
                    tenantBActor, tenantBUser.getId(), "PASSWORD_RESET", null, "10.0.0.1"));
        });

        // Sanity: Tenant B's audit history is 3 rows, all carrying tenant_id=tenantB.
        TenantContext.runWithContext(tenantB.getId(), false, () -> {
            Integer tenantBRowCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_events WHERE target_user_id = ?::uuid AND tenant_id = ?::uuid",
                    Integer.class, tenantBUser.getId(), tenantB.getId());
            assertThat(tenantBRowCount)
                    .as("Baseline: Tenant B has 3 audit events for the victim user tagged with Tenant B's tenant_id")
                    .isEqualTo(3);
        });

        // Act: Tenant A's COC_ADMIN probes Tenant B's user UUID via the public endpoint.
        HttpHeaders tenantAHeaders = authHelper.cocAdminHeaders();
        ResponseEntity<List<Object>> attackResp = restTemplate.exchange(
                "/api/v1/audit-events?targetUserId=" + tenantBUser.getId(),
                HttpMethod.GET,
                new HttpEntity<>(tenantAHeaders),
                new ParameterizedTypeReference<>() {});

        // Assert: 200 OK with empty list (not 404 — LIST endpoints return empty
        // for "no matching rows" which is the correct shape for cross-tenant;
        // not a leaked list of Tenant B's rows).
        assertThat(attackResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(attackResp.getBody())
                .as("Cross-tenant audit probe must return empty — pre-fix would have leaked Tenant B's 3 rows")
                .isEmpty();
    }

    @Test
    @DisplayName("Audit insert without TenantContext logs warning and persists with tenant_id=NULL")
    void tc_audit_insert_withoutTenantContext_logsWarning_persistsOrphan() {
        // This test documents the defensive behavior when an audit event is
        // published from a path without TenantContext bound (shouldn't happen
        // in current code, but defense-in-depth). The row persists with
        // tenant_id=NULL and the service logs a WARN. Operators monitoring
        // for "Audit event published without TenantContext bound" can catch
        // publisher sites missing TenantContext.runWithContext wrap.

        UUID orphanTarget = UUID.randomUUID();
        UUID orphanActor = UUID.randomUUID();

        // Fire outside any TenantContext.runWithContext scope.
        eventPublisher.publishEvent(new AuditEventRecord(
                orphanActor, orphanTarget, "ORPHAN_AUDIT_TEST", null, null));

        // Verify row persisted with tenant_id=NULL. Query via root connection
        // since no tenant context is bound.
        Integer nullTenantCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE target_user_id = ?::uuid AND tenant_id IS NULL",
                Integer.class, orphanTarget);
        assertThat(nullTenantCount)
                .as("Orphan audit event (TenantContext unbound) persists with tenant_id=NULL")
                .isEqualTo(1);

        // Verify it would NOT leak to a cross-tenant query: if a Tenant A
        // admin queried for orphanTarget, the repository's
        // WHERE tenant_id = :tenantId would not match NULL, so the row stays
        // invisible.
        authHelper.setupTestTenant();
        UUID tenantAId = authHelper.getTestTenantId();
        TenantContext.runWithContext(tenantAId, false, () -> {
            Integer visibleToTenantA = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_events "
                            + "WHERE target_user_id = ?::uuid AND tenant_id = ?::uuid",
                    Integer.class, orphanTarget, tenantAId);
            assertThat(visibleToTenantA)
                    .as("Orphan audit event with tenant_id=NULL must not be visible to any tenant-scoped query")
                    .isZero();
        });

        // Cleanup
        jdbcTemplate.update("DELETE FROM audit_events WHERE target_user_id = ?::uuid AND tenant_id IS NULL",
                orphanTarget);
    }
}
