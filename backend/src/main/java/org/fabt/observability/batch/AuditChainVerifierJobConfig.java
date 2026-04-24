package org.fabt.observability.batch;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.fabt.analytics.config.BatchJobScheduler;
import org.fabt.shared.audit.AuditChainHasher;
import org.fabt.shared.audit.AuditEventEntity;
import org.fabt.shared.config.JsonString;
import org.fabt.shared.security.TenantUnscoped;
import org.fabt.shared.web.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase G slice G-2 — Spring Batch job that re-verifies every tenant's audit
 * hash chain daily ({@code multi-tenant-production-readiness} §8.6, §8.15).
 *
 * <p><b>Package home.</b> Lives in {@code org.fabt.observability.batch} rather
 * than {@code shared.audit.batch} so the dependency on
 * {@link BatchJobScheduler} (an {@code analytics.*} class) doesn't violate
 * the shared-kernel-no-domain-deps ArchUnit rule. The verifier is
 * conceptually an observability concern — it monitors audit-trail integrity
 * rather than being part of the audit domain itself.
 *
 * <h2>What it does</h2>
 *
 * <p>For each tenant listed in {@code tenant_audit_chain_head}, the tasklet:
 *
 * <ol>
 *   <li>Binds {@link TenantContext} to the tenant (needed for Phase B FORCE RLS on
 *       {@code audit_events})</li>
 *   <li>Walks every row with a non-null {@code row_hash} in publish order
 *       via a cursor-style keyset pagination ({@code (timestamp, id) > (?, ?)})
 *       with 1000-row batches — constant memory regardless of per-tenant row
 *       count. Replaces a naive load-all-rows-in-memory approach that would
 *       grow O(N) with the audit volume.</li>
 *   <li>For each row, recomputes
 *       {@code expected_hash = SHA-256(prev_hash_from_row || canonical_json(row))}
 *       using the same {@link AuditChainHasher#canonicalJson} that the writer uses
 *       — single source of truth for the canonical form (§8.6a)</li>
 *   <li>Compares the computed hash to the stored {@code row_hash}. On mismatch,
 *       increments {@code fabt.audit.chain_verify.drift.count{tenant_id}} and logs
 *       the offending row id + timestamp.</li>
 *   <li>Verifies chain continuity: row N+1's {@code prev_hash} must equal row N's
 *       {@code row_hash}. Gap → same drift counter + log.</li>
 *   <li>Verifies chain-head alignment: the last row's {@code row_hash} must equal
 *       {@code tenant_audit_chain_head.last_hash}. Mismatch indicates an
 *       {@code advanceChainHead} failure (G-1 path) or post-advance corruption.</li>
 * </ol>
 *
 * <h2>What it doesn't do (yet)</h2>
 *
 * <ul>
 *   <li>Does NOT page on drift — emits metric only. Prometheus rule
 *       {@code AuditChainDrift} in {@code deploy/prometheus/phase-g-chain-verify.rules.yml}
 *       handles alerting.</li>
 *   <li>Does NOT verify hard-deleted tenants (no chain head row to iterate).
 *       A future slice can cross-reference the {@code TENANT_HARD_DELETED}
 *       tombstone's captured terminal hash against the orphaned rows.</li>
 *   <li>Does NOT re-verify rows with NULL {@code row_hash} (pre-V85 historical
 *       rows + {@code SYSTEM_TENANT_ID} orphans). By design — these were never
 *       chained.</li>
 * </ul>
 *
 * <h2>Scheduling</h2>
 *
 * <p>Registered with {@link BatchJobScheduler} on {@link ApplicationReadyEvent}
 * with cron {@code 0 0 4 * * *} (04:00 UTC daily) and {@code dvAccess=false}
 * (verifier operates on hash bytes + structural columns only; never reads the
 * {@code details} payload's business content, so DV row-level access is not
 * required).
 *
 * <p>On-demand runs available via the existing
 * {@code POST /api/v1/batch/jobs/auditChainVerifier/run} endpoint
 * ({@code BatchJobController}). Operators triggering the verifier during an
 * incident get job-execution history recorded in the standard {@code
 * BATCH_JOB_EXECUTION} tables.
 */
@Configuration
public class AuditChainVerifierJobConfig {

    private static final Logger log = LoggerFactory.getLogger(AuditChainVerifierJobConfig.class);

    /** The canonical job name; matches the {@code /api/v1/batch/jobs/{name}/run} path. */
    public static final String JOB_NAME = "auditChainVerifier";

    /** 04:00 UTC daily — chosen to avoid business-hour I/O contention. */
    private static final String DEFAULT_CRON = "0 0 4 * * *";

    /**
     * Per-tenant keyset pagination batch size. 1000 balances round-trip
     * overhead (larger = fewer SELECTs) against memory footprint (smaller =
     * lower peak heap per batch). At FABT's current scale every tenant fits
     * in one batch; the pagination matters for future growth past ~10k
     * audits per tenant.
     */
    static final int PAGE_SIZE = 1000;

    private final JobRepository jobRepository;
    private final JdbcTemplate jdbc;
    private final AuditChainHasher chainHasher;
    private final BatchJobScheduler batchJobScheduler;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate perTenantTx;

    public AuditChainVerifierJobConfig(JobRepository jobRepository,
                                       JdbcTemplate jdbc,
                                       AuditChainHasher chainHasher,
                                       BatchJobScheduler batchJobScheduler,
                                       ObjectProvider<MeterRegistry> meterRegistryProvider,
                                       PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.jdbc = jdbc;
        this.chainHasher = chainHasher;
        this.batchJobScheduler = batchJobScheduler;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
        this.perTenantTx = new TransactionTemplate(transactionManager);
    }

    @Bean
    public Job auditChainVerifierJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(verifyChainsStep())
                .build();
    }

    @Bean
    public Step verifyChainsStep() {
        // ResourcelessTransactionManager: the tasklet opens its own per-tenant
        // read tx via TenantContext.runWithContext. The outer step does not
        // need to hold a DB tx; letting Spring Batch use a resourceless manager
        // avoids joining an outer tx across tenants and keeps RLS binding
        // clean.
        return new StepBuilder("verifyChainsStep", jobRepository)
                .tasklet(verifyChainsTasklet(), new ResourcelessTransactionManager())
                .build();
    }

    @Bean
    @TenantUnscoped("Spring Batch audit chain verifier — iterates every tenant's chain; per-tenant reads bind TenantContext internally")
    public Tasklet verifyChainsTasklet() {
        return (StepContribution contribution, ChunkContext chunkContext) -> {
            Timer.Sample sample = meterRegistry == null ? null : Timer.start(meterRegistry);
            Instant runStart = Instant.now();

            List<UUID> tenantIds = jdbc.queryForList(
                    "SELECT tenant_id FROM tenant_audit_chain_head ORDER BY tenant_id",
                    UUID.class);

            int tenantsVerified = 0;
            int totalRows = 0;
            int totalDrift = 0;

            for (UUID tenantId : tenantIds) {
                try {
                    // Run per-tenant verification inside a TransactionTemplate
                    // so set_config(app.tenant_id, ?, is_local=true) persists
                    // across every SELECT within the same connection/tx.
                    // Without this wrapper, each JdbcTemplate call auto-commits
                    // and loses the GUC binding, leaving Phase B FORCE RLS on
                    // audit_events to return zero rows. TenantContext binds
                    // OUTSIDE the tx boundary per the B11 ordering rule
                    // (feedback_transactional_rls_scoped_value_ordering).
                    VerifyResult result = TenantContext.callWithContext(tenantId, false, () ->
                            perTenantTx.execute(status -> verifyTenantChain(tenantId)));
                    tenantsVerified++;
                    totalRows += result.rowsVerified();
                    totalDrift += result.driftCount();

                    if (result.driftCount() > 0) {
                        log.error("Audit chain drift detected for tenant={} — rowsVerified={}, drift={}",
                                tenantId, result.rowsVerified(), result.driftCount());
                        if (meterRegistry != null) {
                            Counter.builder("fabt.audit.chain_verify.drift.count")
                                    .tag("tenant_id", tenantId.toString())
                                    .description("Audit rows whose stored row_hash disagrees with recomputed hash, by tenant")
                                    .register(meterRegistry)
                                    .increment(result.driftCount());
                        }
                    } else {
                        log.info("Audit chain verified clean for tenant={} — rowsVerified={}",
                                tenantId, result.rowsVerified());
                    }
                } catch (Exception e) {
                    // A single tenant's verification failure should not block the rest.
                    // Log loudly + continue.
                    log.error("Audit chain verifier FAILED for tenant={}: {}",
                            tenantId, e.getMessage(), e);
                    if (meterRegistry != null) {
                        Counter.builder("fabt.audit.chain_verify.error.count")
                                .tag("tenant_id", tenantId.toString())
                                .register(meterRegistry)
                                .increment();
                    }
                }
            }

            String resultTag = totalDrift > 0 ? "drift_detected" : "pass";
            if (meterRegistry != null) {
                Counter.builder("fabt.audit.chain_verify.runs.count")
                        .tag("result", resultTag)
                        .description("Audit chain verifier runs, tagged by overall result")
                        .register(meterRegistry)
                        .increment();
                Counter.builder("fabt.audit.chain_verify.rows_verified.count")
                        .description("Total audit rows walked by the verifier across all tenants per run")
                        .register(meterRegistry)
                        .increment(totalRows);
                if (sample != null) {
                    sample.stop(Timer.builder("fabt.audit.chain_verify.duration.seconds")
                            .description("Wall-clock duration of the audit chain verifier tasklet")
                            .register(meterRegistry));
                }
            }

            log.info("Audit chain verifier complete — tenants={}, rowsVerified={}, drift={}, started={}",
                    tenantsVerified, totalRows, totalDrift, runStart);

            return RepeatStatus.FINISHED;
        };
    }

    /**
     * Walk one tenant's audit chain via cursor-style keyset pagination and
     * return the drift count.
     *
     * <p>Must be invoked with {@link TenantContext} bound to the tenant — the
     * SELECT against {@code audit_events} relies on FORCE RLS binding
     * {@code app.tenant_id} to admit rows for this tenant only.
     *
     * <p>Pagination invariant: rows are walked in {@code (timestamp, id)}
     * order. Each batch's last row's {@code (timestamp, id)} becomes the
     * cursor for the next batch via {@code (timestamp, id) > (?, ?)}. This
     * gives O(1) memory per batch regardless of total per-tenant row count.
     */
    private VerifyResult verifyTenantChain(UUID tenantId) {
        // Explicit set_config('app.tenant_id', ...) matching the pattern
        // AuditEventPersister uses: Phase B FORCE RLS on audit_events requires
        // this GUC to be bound on the connection. TenantContext.callWithContext
        // binds the ScopedValue, but RlsAwareDataSource's automatic GUC binding
        // fires reliably only at new-connection-acquisition, not on pooled
        // reuse inside a non-transactional JdbcTemplate chain. Explicit
        // set_config is belt-and-braces + matches the writer path.
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantId.toString());

        int drift = 0;
        int totalRows = 0;
        byte[] previousRowHash = null;
        Instant cursorTimestamp = null;
        UUID cursorId = null;

        while (true) {
            List<Map<String, Object>> batch = fetchBatch(tenantId, cursorTimestamp, cursorId);
            if (batch.isEmpty()) break;

            for (Map<String, Object> row : batch) {
                UUID rowId = (UUID) row.get("id");
                byte[] storedPrevHash = (byte[]) row.get("prev_hash");
                byte[] storedRowHash = (byte[]) row.get("row_hash");

                // Chain continuity: row N+1's prev_hash should equal row N's row_hash.
                // Skip the check for the first hashed row we see across the whole
                // walk — pre-V85 rows may precede, so there's no prior hash to
                // compare against on the very first entry.
                if (previousRowHash != null && !Arrays.equals(previousRowHash, storedPrevHash)) {
                    log.error("Chain continuity gap at row={} tenant={}: previous row_hash did not match this row's prev_hash",
                            rowId, tenantId);
                    drift++;
                }

                // Re-hash the row and compare.
                AuditEventEntity reconstructed = reconstructEntity(row);
                String canonical = chainHasher.canonicalJson(reconstructed);
                byte[] expected = sha256(concat(storedPrevHash,
                        canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

                if (!Arrays.equals(expected, storedRowHash)) {
                    log.error("row_hash mismatch at row={} tenant={} — forensic evidence of tampering or hash-input drift",
                            rowId, tenantId);
                    drift++;
                }

                previousRowHash = storedRowHash;
                // Update cursor to this row's (timestamp, id) for the next
                // batch's keyset continuation.
                cursorTimestamp = ((Timestamp) row.get("timestamp")).toInstant();
                cursorId = rowId;
            }

            totalRows += batch.size();
            // If the batch came back smaller than the page size, we've
            // reached the tail — short-circuit rather than issuing a final
            // empty query.
            if (batch.size() < PAGE_SIZE) break;
        }

        // Chain-head alignment: tenant_audit_chain_head.last_hash should equal
        // the most recent audit_events row's row_hash.
        if (previousRowHash != null) {
            byte[] chainHeadHash = jdbc.query(
                    "SELECT last_hash FROM tenant_audit_chain_head WHERE tenant_id = ?",
                    rs -> rs.next() ? rs.getBytes(1) : null,
                    tenantId);
            if (chainHeadHash != null && !Arrays.equals(chainHeadHash, previousRowHash)) {
                log.error("Chain head mismatch for tenant={} — advanceChainHead failure or post-hoc tampering",
                        tenantId);
                drift++;
            }
        }

        return new VerifyResult(totalRows, drift);
    }

    /**
     * Fetch the next batch of audit rows for a tenant via keyset pagination.
     * First call passes {@code cursorTimestamp = null} and {@code cursorId =
     * null}, the query uses an unbounded {@code WHERE row_hash IS NOT NULL}
     * clause. Subsequent calls pass the (timestamp, id) of the last row of
     * the previous batch to continue from there.
     */
    private List<Map<String, Object>> fetchBatch(UUID tenantId, Instant cursorTimestamp, UUID cursorId) {
        if (cursorTimestamp == null || cursorId == null) {
            return jdbc.queryForList(
                    "SELECT id, timestamp, tenant_id, actor_user_id, target_user_id, "
                    + "action, details::text AS details, ip_address, prev_hash, row_hash "
                    + "FROM audit_events "
                    + "WHERE tenant_id = ? AND row_hash IS NOT NULL "
                    + "ORDER BY timestamp, id "
                    + "LIMIT " + PAGE_SIZE,
                    tenantId);
        }
        // Keyset continuation: (timestamp, id) > (cursor_timestamp, cursor_id).
        // PostgreSQL evaluates row constructors lexicographically, so this
        // picks up at the row after the cursor. Index on (tenant_id,
        // timestamp, id) would make this O(log n); even without, the planner
        // applies the WHERE before the ORDER BY.
        return jdbc.queryForList(
                "SELECT id, timestamp, tenant_id, actor_user_id, target_user_id, "
                + "action, details::text AS details, ip_address, prev_hash, row_hash "
                + "FROM audit_events "
                + "WHERE tenant_id = ? AND row_hash IS NOT NULL "
                + "  AND (timestamp, id) > (?, ?) "
                + "ORDER BY timestamp, id "
                + "LIMIT " + PAGE_SIZE,
                tenantId, Timestamp.from(cursorTimestamp), cursorId);
    }

    private static AuditEventEntity reconstructEntity(Map<String, Object> row) {
        AuditEventEntity e = new AuditEventEntity();
        e.setId((UUID) row.get("id"));
        Timestamp ts = (Timestamp) row.get("timestamp");
        if (ts != null) e.setTimestamp(ts.toInstant());
        e.setTenantId((UUID) row.get("tenant_id"));
        e.setActorUserId((UUID) row.get("actor_user_id"));
        e.setTargetUserId((UUID) row.get("target_user_id"));
        e.setAction((String) row.get("action"));
        String detailsText = (String) row.get("details");
        if (detailsText != null) e.setDetails(new JsonString(detailsText));
        e.setIpAddress((String) row.get("ip_address"));
        return e;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerWithScheduler() {
        // dvAccess=false: verifier reads hash bytes + structural columns only,
        // never the details payload's business content. DV row-level access
        // is not required for chain verification.
        batchJobScheduler.registerJob(JOB_NAME, auditChainVerifierJob(),
                DEFAULT_CRON, false);
    }

    /**
     * Per-tenant verification result returned by {@link #verifyTenantChain}.
     */
    public record VerifyResult(int rowsVerified, int driftCount) {}
}
