package org.fabt.architecture;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.web.TenantContext;
import org.fabt.testsupport.WithTenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime-property proof tests for the two entries on
 * {@code TenantContextTransactionalRuleTest.ALLOWLIST}:
 *
 * <ul>
 *   <li>{@code HmisPushService.processOutbox} — the outer
 *       {@code @Transactional} method submits per-entry work to a
 *       {@code VirtualThreadPerTaskExecutor}; the inner lambda calls
 *       {@code TenantContext.runWithContext(entry.getTenantId(), …)}.
 *       ArchUnit sees the lexical call from a {@code @Transactional}
 *       method into {@code runWithContext}; B11 would normally flag
 *       that. The allowlist's correctness claim is that at runtime the
 *       executor's virtual worker thread borrows a <em>fresh</em>
 *       HikariCP connection, so the inner tenant GUC is what the
 *       worker thread's queries see — not the outer transaction's
 *       binding.</li>
 *   <li>{@code ReservationService.expireReservation} — the outer
 *       {@code @Transactional} is {@code @TenantUnscoped} (scheduler-
 *       driven, no outer TenantContext). Inside the method,
 *       {@code runWithContext(reservation.getTenantId(), …)} wraps an
 *       {@code ApplicationEventPublisher.publishEvent(...)} that
 *       routes through {@code AuditEventService} →
 *       {@code AuditEventPersister.persist}. The persister is
 *       {@code @Transactional(REQUIRED)} and, as its first statement,
 *       runs {@code SELECT set_config('app.tenant_id', ?, true)} to
 *       re-bind the GUC to the inner tenant on the <em>joined</em>
 *       transaction's already-borrowed connection. The allowlist's
 *       correctness claim is that this re-bind makes the audit INSERT
 *       land with {@code tenant_id = innerTenant}, not the outer
 *       null/system binding.</li>
 * </ul>
 *
 * <p>If either property breaks (e.g., an executor reconfiguration
 * silently adopts caller-runs semantics, or a refactor drops the
 * persister's {@code set_config} prologue), the allowlist entry
 * becomes unsafe and these tests fail loudly — preventing a silent
 * cross-tenant leak that ArchUnit's static analysis cannot detect.
 *
 * <p>See Phase B warroom action item W-B11-1.
 */
class B11AllowlistIsolationTest extends BaseIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private TestAuthHelper authHelper;

    @Test
    @DisplayName("B11 allowlist — HmisPushService.processOutbox: VirtualThreadPerTaskExecutor lambda "
            + "sees the inner runWithContext tenant, not the outer @Transactional binding")
    void virtualThreadExecutor_innerRunWithContext_usesFreshConnectionWithInnerTenant()
            throws Exception {
        authHelper.setupTestTenant();
        UUID outerTenant = UUID.randomUUID();  // synthetic — not a real tenant row
        UUID innerTenant = authHelper.getTestTenantId();

        // Outer @Transactional-equivalent: bind TenantContext=outerTenant, open tx,
        // borrow a connection (RlsDataSourceConfig.applyRlsContext sets
        // app.tenant_id=outerTenant on it). Then submit to a virtual-thread executor
        // with the inner runWithContext(innerTenant). Assert the inner lambda's
        // JDBC query reads app.tenant_id=innerTenant — proving the worker thread
        // got a different Hikari connection with the inner binding.
        String innerSessionBinding = WithTenantContext.readAs(outerTenant, () ->
                transactionTemplate.execute(status -> {
                    // Sanity: the outer tx's own connection sees outerTenant.
                    String outerBinding = jdbcTemplate.queryForObject(
                            "SELECT current_setting('app.tenant_id', true)", String.class);
                    assertThat(outerBinding)
                            .as("Outer @Transactional connection must be bound to outer tenant")
                            .isEqualTo(outerTenant.toString());

                    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        return executor.submit(() ->
                                TenantContext.callWithContext(innerTenant, false, () ->
                                        jdbcTemplate.queryForObject(
                                                "SELECT current_setting('app.tenant_id', true)",
                                                String.class)))
                                .get();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));

        assertThat(innerSessionBinding)
                .as("B11 allowlist invariant: the virtual-thread worker borrows a FRESH "
                        + "connection that applyRlsContext binds to the inner tenant — "
                        + "NOT the outer @Transactional's already-borrowed connection. "
                        + "If this equals the outer tenant, executor.submit silently "
                        + "degraded to caller-runs semantics and the allowlist entry "
                        + "is unsafe.")
                .isEqualTo(innerTenant.toString());
    }

    @Test
    @DisplayName("B11 allowlist — ReservationService.expireReservation: inner runWithContext + "
            + "ApplicationEventPublisher lands audit row with inner tenant via "
            + "AuditEventPersister.set_config re-bind")
    void innerRunWithContext_publishAuditEvent_rowGetsInnerTenantViaPersisterRebind() {
        authHelper.setupTestTenant();
        UUID innerTenant = authHelper.getTestTenantId();

        // Unique action string so we can find exactly this row in audit_events.
        // ACTION_TAG_PATTERN requires ^[A-Z0-9_]{1,64}$ — uppercase + underscore.
        String uniqueAction = "B11_TEST_"
                + UUID.randomUUID().toString().replace("-", "_").toUpperCase();

        // Simulate the ReservationService.expireReservation shape: outer
        // @Transactional runs with no outer TenantContext (scheduler thread),
        // inner runWithContext wraps a publishEvent that routes through
        // AuditEventService → AuditEventPersister. The persister's set_config
        // prologue re-binds app.tenant_id to innerTenant on the joined tx's
        // already-borrowed connection.
        //
        // Use the outer TransactionTemplate to emulate the method-level
        // @Transactional without having to instantiate ReservationService
        // (which needs a real reservation row + shelter + bed_availability).
        transactionTemplate.executeWithoutResult(status -> {
            TenantContext.runWithContext(innerTenant, false, () -> {
                eventPublisher.publishEvent(new AuditEventRecord(
                        null,         // actor user id
                        null,         // target user id
                        uniqueAction, // action (ACTION_TAG_PATTERN: ^[A-Z0-9_]{1,64}$)
                        null,         // details
                        null          // ip address
                ));
            });
        });

        // Read the audit row — audit_events has FOR ALL RLS policy so read
        // must be inside a matching tenant binding or the row is filtered.
        Map<String, Object> row = WithTenantContext.readAs(innerTenant, () ->
                jdbcTemplate.queryForMap(
                        "SELECT tenant_id::text AS tenant_id_text, action FROM audit_events "
                                + "WHERE action = ?",
                        uniqueAction));

        assertThat(row)
                .as("Audit row must exist under innerTenant — if the persister's "
                        + "set_config re-bind regressed, the row would have landed under "
                        + "SYSTEM_TENANT_ID fallback (or been rejected with 42501) and "
                        + "this read would return empty")
                .isNotNull();
        assertThat(row.get("tenant_id_text"))
                .as("B11 allowlist invariant: AuditEventPersister re-binds app.tenant_id "
                        + "to the inner runWithContext tenant before the INSERT, so the "
                        + "row lands with tenant_id=innerTenant. If this is the SYSTEM "
                        + "sentinel (00000000-0000-0000-0000-000000000001), the re-bind "
                        + "regressed and the allowlist entry for "
                        + "ReservationService.expireReservation is unsafe.")
                .isEqualTo(innerTenant.toString());
    }
}
