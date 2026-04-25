package org.fabt.observability.batch;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.fabt.analytics.config.BatchJobScheduler;
import org.fabt.observability.anchor.AuditChainAnchorService;
import org.fabt.observability.anchor.OciAuditAnchorProperties;
import org.fabt.shared.security.TenantUnscoped;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase G-3 — Spring Batch job that uploads weekly audit-chain anchors to
 * OCI Object Storage (multi-tenant-production-readiness §8.5).
 *
 * <p><b>Conditional on {@code fabt.oci.audit-anchor.enabled=true}</b>. When
 * disabled, the {@code @Configuration} class is not loaded; the job is
 * never registered with {@link BatchJobScheduler}; no OCI dependencies
 * are required at runtime. Dev / CI / local builds are entirely unaffected.
 *
 * <p>For each tenant in {@code tenant_audit_chain_head}, the tasklet:
 * <ol>
 *   <li>Reads the tenant's {@code last_hash} + {@code last_row_id} (under
 *       SYSTEM context — chain head table has no RLS per V80)</li>
 *   <li>Skips tenants whose chain head still has the 32-byte zero sentinel
 *       (no audits written yet) — anchoring an empty chain is forensically
 *       useless</li>
 *   <li>Calls {@link AuditChainAnchorService#uploadAnchor} which performs
 *       the OCI {@code PutObject}</li>
 *   <li>Emits success / failure counters tagged by tenant</li>
 * </ol>
 *
 * <p>Per-tenant failure is logged + metric-counted; the run continues for
 * remaining tenants. Sustained failure rate is alerted via the Prometheus
 * rule {@code FabtAuditAnchorUploadFailing} in
 * {@code deploy/prometheus/phase-g-chain-verify.rules.yml}.
 *
 * <p>On-demand runs available via the existing
 * {@code POST /api/v1/batch/jobs/auditChainAnchor/run} endpoint
 * ({@code BatchJobController}).
 */
@Configuration
@ConditionalOnProperty(prefix = "fabt.oci.audit-anchor", name = "enabled", havingValue = "true")
public class AuditChainAnchorJobConfig {

    private static final Logger log = LoggerFactory.getLogger(AuditChainAnchorJobConfig.class);

    /** Job name; matches the {@code /api/v1/batch/jobs/{name}/run} path. */
    public static final String JOB_NAME = "auditChainAnchor";

    /** 32-byte zero sentinel — the V80 / TenantLifecycleService.create seed. */
    private static final byte[] ZERO_SENTINEL = new byte[32];

    private final JobRepository jobRepository;
    private final JdbcTemplate jdbc;
    private final AuditChainAnchorService anchorService;
    private final BatchJobScheduler batchJobScheduler;
    private final OciAuditAnchorProperties props;
    private final MeterRegistry meterRegistry;

    public AuditChainAnchorJobConfig(JobRepository jobRepository,
                                      JdbcTemplate jdbc,
                                      AuditChainAnchorService anchorService,
                                      BatchJobScheduler batchJobScheduler,
                                      OciAuditAnchorProperties props,
                                      ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.jobRepository = jobRepository;
        this.jdbc = jdbc;
        this.anchorService = anchorService;
        this.batchJobScheduler = batchJobScheduler;
        this.props = props;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    @Bean
    public Job auditChainAnchorJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(anchorTenantsStep())
                .build();
    }

    @Bean
    public Step anchorTenantsStep() {
        return new StepBuilder("anchorTenantsStep", jobRepository)
                .tasklet(anchorTenantsTasklet(), new ResourcelessTransactionManager())
                .build();
    }

    @Bean
    @TenantUnscoped("Audit-anchor uploader — iterates every tenant's chain head; chain_head table has no RLS (V80) so no per-tenant binding is needed")
    public Tasklet anchorTenantsTasklet() {
        return (StepContribution contribution, ChunkContext chunkContext) -> {
            Timer.Sample sample = meterRegistry == null ? null : Timer.start(meterRegistry);
            Instant runStart = Instant.now();
            UUID runId = UUID.randomUUID();

            // tenant_audit_chain_head has no RLS; safe to read across all tenants.
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT tenant_id, last_hash, last_row_id FROM tenant_audit_chain_head ORDER BY tenant_id");

            int succeeded = 0;
            int failed = 0;
            int skipped = 0;

            for (Map<String, Object> row : rows) {
                UUID tenantId = (UUID) row.get("tenant_id");
                byte[] lastHash = (byte[]) row.get("last_hash");
                UUID lastRowId = (UUID) row.get("last_row_id");

                if (lastHash == null || java.util.Arrays.equals(lastHash, ZERO_SENTINEL)) {
                    // Chain not yet bumped past the zero-sentinel seed — no
                    // audits to anchor. Skip silently.
                    skipped++;
                    continue;
                }

                try {
                    anchorService.uploadAnchor(tenantId, lastHash, lastRowId, runStart, runId);
                    succeeded++;
                    if (meterRegistry != null) {
                        Counter.builder("fabt.audit.anchor.upload.count")
                                .tag("result", "success")
                                .description("OCI audit-anchor upload outcomes")
                                .register(meterRegistry)
                                .increment();
                    }
                } catch (Exception e) {
                    failed++;
                    log.error("OCI audit-anchor upload FAILED for tenant={} runId={}: {}",
                            tenantId, runId, e.getMessage(), e);
                    if (meterRegistry != null) {
                        Counter.builder("fabt.audit.anchor.upload.count")
                                .tag("result", "failure")
                                .tag("tenant_id", tenantId.toString())
                                .description("OCI audit-anchor upload outcomes")
                                .register(meterRegistry)
                                .increment();
                    }
                }
            }

            if (meterRegistry != null) {
                Counter.builder("fabt.audit.anchor.tenants_anchored.count")
                        .description("Tenants whose chain head was successfully anchored to OCI in this run")
                        .register(meterRegistry)
                        .increment(succeeded);
                if (sample != null) {
                    sample.stop(Timer.builder("fabt.audit.anchor.duration.seconds")
                            .description("Wall-clock duration of the OCI audit-anchor batch job")
                            .register(meterRegistry));
                }
            }

            log.info("OCI audit-anchor batch complete — runId={} succeeded={} failed={} skipped={} startedAt={}",
                    runId, succeeded, failed, skipped, runStart);

            return RepeatStatus.FINISHED;
        };
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerWithScheduler() {
        // dvAccess=false: anchor service reads chain-head metadata + uploads
        // hash bytes; never reads audit_events details payload.
        batchJobScheduler.registerJob(JOB_NAME, auditChainAnchorJob(), props.cron(), false);
    }
}
