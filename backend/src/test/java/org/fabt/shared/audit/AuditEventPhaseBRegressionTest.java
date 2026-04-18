package org.fabt.shared.audit;

import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.fabt.BaseIntegrationTest;
import org.fabt.shared.web.TenantContext;
import org.fabt.testsupport.WithTenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B regression guards for the audit-path bug class resolved in the
 * AuditEventPersister extraction + SYSTEM_TENANT_ID sentinel fallback:
 *
 * <ul>
 *   <li><b>Bug A+D:</b> proxy-bypass on self-invocation of an {@code
 *       @Transactional} helper — under autoCommit=true with no outer
 *       publisher tx, {@code set_config(is_local=true)} dies at the
 *       statement boundary → INSERT runs with stale GUC → FORCE RLS
 *       rejects. Extracted {@link AuditEventPersister} eliminates the
 *       self-invocation; PROPAGATION_REQUIRED opens a real tx when none
 *       exists.</li>
 *   <li><b>SYSTEM_TENANT_ID sentinel:</b> per Phase B D55, audit events
 *       published without TenantContext bound must persist under
 *       {@code 00000000-0000-0000-0000-000000000001} rather than
 *       tenant_id=NULL (which FORCE RLS would reject).</li>
 *   <li><b>Micrometer counter:</b> every SYSTEM_TENANT_ID fallback
 *       increments {@code fabt.audit.system_insert.count} tagged by
 *       action — the operational canary for "publisher forgot to bind
 *       TenantContext."</li>
 * </ul>
 *
 * <p>These tests exercise the EXACT bug shape that took 9 test failures
 * + a three-persona warroom to diagnose in the Phase B implementation
 * cycle. If they fail in the future, the proxy/tx/sentinel contract has
 * regressed and Phase B's core security posture is at risk.
 */
@DisplayName("Phase B audit-path regression guards (proxy-bypass + SYSTEM_TENANT_ID sentinel + counter)")
class AuditEventPhaseBRegressionTest extends BaseIntegrationTest {

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MeterRegistry meterRegistry;

    @Test
    @DisplayName("Orphan audit (no TenantContext, no outer tx) persists under SYSTEM_TENANT_ID "
            + "— Bug A+D regression guard")
    void orphanAuditWithoutOuterTx_persistsUnderSystemSentinel() {
        // Unique action so we can assert on the fresh row only.
        String action = "PB_ORPHAN_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID target = UUID.randomUUID();

        // Publish outside any TenantContext + outside any @Transactional.
        // Pre-fix: AuditEventService.onAuditEvent would delegate via
        // this.persistWithTenantBinding → proxy bypassed → @Transactional
        // inert → set_config(is_local=true) on autoCommit=true dies → INSERT
        // rejected with PSQL 42501 → swallowed → row missing.
        eventPublisher.publishEvent(new AuditEventRecord(
                null, target, action, null, null));

        // Row MUST land. Use SYSTEM context to read (FORCE RLS filters otherwise).
        Integer count = WithTenantContext.readAsSystem(() ->
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events "
                + "WHERE action = ? AND tenant_id = ?::uuid",
                Integer.class, action, TenantContext.SYSTEM_TENANT_ID));
        assertThat(count)
                .as("Orphan audit must land with tenant_id=SYSTEM_TENANT_ID (D55); "
                    + "if 0, the proxy-bypass regression is back")
                .isEqualTo(1);

        // No cleanup — Phase B V70 REVOKEd DELETE on audit_events (append-only
        // audit posture per G2). Action name is UUID-unique per test run, so
        // no contamination between runs. Testcontainers starts fresh anyway.
    }

    @Test
    @DisplayName("Orphan audit increments fabt.audit.system_insert.count counter "
            + "— D62 observability regression guard")
    void orphanAudit_incrementsSystemInsertCounter() {
        String action = "PB_COUNTER_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        double before = counterValue(action);

        eventPublisher.publishEvent(new AuditEventRecord(
                null, UUID.randomUUID(), action, null, null));

        double after = counterValue(action);
        assertThat(after - before)
                .as("Every SYSTEM_TENANT_ID fallback must increment fabt.audit.system_insert.count "
                    + "tagged by action — if this regresses, Prometheus alerts on "
                    + "'publisher forgot TenantContext' go silent")
                .isEqualTo(1.0);

        // No cleanup — V70 REVOKEd DELETE on audit_events.
    }

    private double counterValue(String action) {
        Search search = meterRegistry.find("fabt.audit.system_insert.count").tag("action", action);
        if (search.counter() == null) {
            return 0.0;
        }
        return search.counter().count();
    }
}
