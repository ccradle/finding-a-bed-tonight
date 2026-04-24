package org.fabt.observability.batch;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.fabt.BaseIntegrationTest;
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.audit.AuditEventType;
import org.fabt.shared.web.TenantContext;
import org.fabt.testsupport.WithTenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Phase G-2 audit chain verifier
 * ({@code AuditChainVerifierJobConfig}).
 *
 * <h2>T1 — clean chain passes</h2>
 * Seed a tenant, publish 3 audit events (all hashed by G-1), run the
 * verifier job, assert {@code COMPLETED} status and zero drift-counter
 * increment.
 *
 * <h2>T2 — tampered row detected (direct INSERT with wrong row_hash)</h2>
 * Phase B V70 REVOKEd {@code UPDATE} + {@code DELETE} on {@code audit_events}
 * from {@code fabt_app} — audit is append-only at the DB permission layer.
 * To simulate direct-DB tampering (the attacker scenario the hash chain is
 * designed to detect), the test INSERTs a row directly with a deliberately
 * wrong {@code row_hash}. The verifier recomputes the expected hash from
 * the row's content, sees the mismatch, increments the drift counter.
 *
 * <h2>T3 — chain-head mismatch detected (UPDATE on chain_head)</h2>
 * Corrupts {@code tenant_audit_chain_head.last_hash} directly (V80 grants
 * UPDATE on that table so the corruption SQL works under {@code fabt_app}
 * without a REVOKE dance). The verifier's chain-head-alignment check catches it.
 */
class AuditChainVerifierIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job auditChainVerifierJob;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("T1 — clean chain: verifier COMPLETES with zero drift-counter increment")
    void t1_cleanChainPassesVerification() throws Exception {
        UUID tenantId = seedTenantAndChainHead("verify-clean-");
        UUID actor = UUID.randomUUID();
        String probeToken = "G2_V1_" + UUID.randomUUID();

        TenantContext.runWithContext(tenantId, false, () -> {
            for (int i = 1; i <= 3; i++) {
                eventPublisher.publishEvent(new AuditEventRecord(
                        actor, null, AuditEventType.TEST_PROBE,
                        Map.of("probe_token", probeToken, "seq", i),
                        null));
            }
        });

        double driftBefore = driftCounterFor(tenantId);
        JobExecution exec = runVerifier();

        assertThat(exec.getStatus().toString())
                .as("Verifier should complete cleanly when the chain is untampered")
                .isEqualTo("COMPLETED");
        assertThat(driftCounterFor(tenantId) - driftBefore)
                .as("Clean chain must produce zero drift-counter increments")
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("T2 — tampered row detected: INSERT with wrong row_hash increments drift counter")
    void t2_tamperedRowDetected() throws Exception {
        UUID tenantId = seedTenantAndChainHead("verify-tamper-");
        UUID actor = UUID.randomUUID();
        String probeToken = "G2_V2_" + UUID.randomUUID();

        // Write one properly-chained audit row first so the chain is
        // non-empty when the tamper arrives.
        TenantContext.runWithContext(tenantId, false, () -> {
            eventPublisher.publishEvent(new AuditEventRecord(
                    actor, null, AuditEventType.TEST_PROBE,
                    Map.of("probe_token", probeToken, "seq", 1),
                    null));
        });

        // Phase B V70 REVOKEs UPDATE on audit_events — we can't UPDATE existing rows
        // under fabt_app. To simulate tamper, INSERT a new row with a deliberately
        // wrong row_hash (valid 32 bytes so the CHECK constraint passes, but
        // content won't match the recomputed hash). The verifier's row-hash
        // recompute sees the mismatch and fires drift.
        TenantContext.runWithContext(tenantId, false, () -> {
            byte[] wrongRowHash = new byte[32];
            for (int i = 0; i < 32; i++) wrongRowHash[i] = (byte) 0xAA;
            byte[] arbitraryPrevHash = new byte[32]; // zeros; won't match real chain state

            jdbc.update(
                    "INSERT INTO audit_events "
                    + "(id, timestamp, tenant_id, actor_user_id, target_user_id, "
                    + " action, details, ip_address, prev_hash, row_hash) "
                    + "VALUES (gen_random_uuid(), ?, ?, ?, NULL, ?, ?::jsonb, NULL, ?, ?)",
                    Timestamp.from(Instant.now().plusSeconds(5)), // after the real row
                    tenantId,
                    actor,
                    AuditEventType.TEST_PROBE.name(),
                    "{\"probe_token\":\"" + probeToken + "\",\"tamper\":true}",
                    arbitraryPrevHash,
                    wrongRowHash);
        });

        double driftBefore = driftCounterFor(tenantId);
        JobExecution exec = runVerifier();
        assertThat(exec.getStatus().toString()).isEqualTo("COMPLETED");

        double driftAfter = driftCounterFor(tenantId);
        assertThat(driftAfter - driftBefore)
                .as("Verifier MUST detect the fake-row-hash tamper and increment "
                    + "fabt.audit.chain_verify.drift.count{tenant_id=%s}. Without this "
                    + "assertion, the test only proves the verifier ran to completion — "
                    + "not that it actually caught the tamper.", tenantId)
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("T3 — chain-head mismatch detected: drift counter increments")
    void t3_chainHeadMismatchDetected() throws Exception {
        UUID tenantId = seedTenantAndChainHead("verify-head-");
        UUID actor = UUID.randomUUID();
        String probeToken = "G2_V3_" + UUID.randomUUID();

        TenantContext.runWithContext(tenantId, false, () -> {
            eventPublisher.publishEvent(new AuditEventRecord(
                    actor, null, AuditEventType.TEST_PROBE,
                    Map.of("probe_token", probeToken),
                    null));
        });

        // Corrupt the chain head — set last_hash to a different 32-byte value.
        // V80 grants UPDATE on tenant_audit_chain_head to fabt_app so this
        // SQL works directly; the REVOKE on audit_events does not apply here.
        byte[] wrongHash = new byte[32];
        for (int i = 0; i < 32; i++) wrongHash[i] = (byte) 0xEE;
        jdbc.update(
                "UPDATE tenant_audit_chain_head SET last_hash = ? WHERE tenant_id = ?",
                wrongHash, tenantId);

        double driftBefore = driftCounterFor(tenantId);
        JobExecution exec = runVerifier();
        assertThat(exec.getStatus().toString())
                .as("Verifier completes even when it detects drift — alerting is downstream")
                .isEqualTo("COMPLETED");

        double driftAfter = driftCounterFor(tenantId);
        assertThat(driftAfter - driftBefore)
                .as("Verifier MUST detect the chain-head corruption and increment the drift counter")
                .isGreaterThanOrEqualTo(1.0);
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private JobExecution runVerifier() throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addLong("runId", System.nanoTime())
                .toJobParameters();
        return jobLauncher.run(auditChainVerifierJob, params);
    }

    /**
     * Reads the Micrometer drift counter value for a given tenant. Returns 0
     * if the counter doesn't exist yet (first tamper for this tenant).
     */
    private double driftCounterFor(UUID tenantId) {
        Counter counter = meterRegistry.find("fabt.audit.chain_verify.drift.count")
                .tag("tenant_id", tenantId.toString())
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    /**
     * Creates a tenant row directly via SQL (bypassing TenantLifecycleService
     * so this test suite doesn't depend on the full lifecycle bootstrap) and
     * inserts a tenant_audit_chain_head seed row with the 32-zero sentinel
     * (matching what V80 backfill + TenantLifecycleService.create do).
     */
    private UUID seedTenantAndChainHead(String slugPrefix) {
        UUID tenantId = UUID.randomUUID();
        String slug = slugPrefix + tenantId.toString().substring(0, 8);
        WithTenantContext.readAsSystem(() -> {
            jdbc.update(
                    "INSERT INTO tenant (id, slug, name, state, "
                    + "jwt_key_generation, data_residency_region) "
                    + "VALUES (?, ?, ?, 'ACTIVE', 1, 'us-any')",
                    tenantId, slug, "G-2 verifier test");
            jdbc.update(
                    "INSERT INTO tenant_audit_chain_head (tenant_id, last_hash, last_row_id) "
                    + "VALUES (?, decode('"
                    + "0000000000000000000000000000000000000000000000000000000000000000"
                    + "', 'hex'), NULL)",
                    tenantId);
            return null;
        });
        return tenantId;
    }
}
