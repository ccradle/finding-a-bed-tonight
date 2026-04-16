package org.fabt.availability.batch;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.Timer;
import tools.jackson.databind.ObjectMapper;
import org.fabt.availability.repository.BedAvailabilityRepository;
import org.fabt.availability.repository.BedAvailabilityRepository.DriftRow;
import org.fabt.observability.ObservabilityMetrics;
import org.fabt.reservation.service.ReservationService;
import org.fabt.shared.audit.AuditEventEntity;
import org.fabt.shared.audit.AuditEventTypes;
import org.fabt.shared.audit.repository.AuditEventRepository;
import org.fabt.shared.config.JsonString;
import org.fabt.shared.web.TenantContext;
import org.fabt.analytics.config.BatchJobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.fabt.shared.security.TenantUnscoped;
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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch job: bed-holds reconciliation (Issue #102 RCA, bed-hold-integrity).
 *
 * <p>Defense-in-depth for the {@code beds_on_hold} denormalization. Every five
 * minutes, scans for {@code (shelter, population)} pairs where the latest
 * {@code bed_availability} snapshot's {@code beds_on_hold} value disagrees with
 * the actual count of {@code HELD} reservations from the source-of-truth
 * reservation table, and writes corrective snapshots through the canonical
 * single-write-path method ({@link ReservationService#recomputeBedsOnHold}).
 * Each correction also writes one audit row tagged
 * {@link AuditEventTypes#BED_HOLDS_RECONCILED}.</p>
 *
 * <p>The job is registered with {@code dvAccess=true} via
 * {@link BatchJobScheduler#registerJob(String, Job, String, boolean)} so that
 * DV-shelter rows are visible to the reconciliation query under RLS — same
 * gotcha as the existing {@code referralEscalation} job. Without that, DV
 * shelter drift would be silently invisible to the tasklet, which is exactly
 * the failure mode this feature is designed to prevent.</p>
 *
 * <p>The single write path discipline introduced by this change should make
 * drift impossible under normal operation; this job exists to catch any
 * future unforeseen drift source and to clean up historical drift left over
 * from versions before v0.34.0.</p>
 */
@Configuration
public class BedHoldsReconciliationJobConfig {

    private static final Logger log = LoggerFactory.getLogger(BedHoldsReconciliationJobConfig.class);

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final BedAvailabilityRepository bedAvailabilityRepository;
    private final ReservationService reservationService;
    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;
    private final ObservabilityMetrics metrics;
    private final BatchJobScheduler batchJobScheduler;

    public BedHoldsReconciliationJobConfig(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            BedAvailabilityRepository bedAvailabilityRepository,
            ReservationService reservationService,
            AuditEventRepository auditEventRepository,
            ObjectMapper objectMapper,
            ObservabilityMetrics metrics,
            BatchJobScheduler batchJobScheduler) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.bedAvailabilityRepository = bedAvailabilityRepository;
        this.reservationService = reservationService;
        this.auditEventRepository = auditEventRepository;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.batchJobScheduler = batchJobScheduler;
    }

    @Bean
    public Job bedHoldsReconciliationJob() {
        return new JobBuilder("bedHoldsReconciliation", jobRepository)
                .start(reconciliationStep())
                .build();
    }

    @Bean
    public Step reconciliationStep() {
        // Use a resourceless transaction manager for the step itself so the tasklet
        // body does NOT run inside an outer database transaction. Each per-row call
        // to recomputeBedsOnHold then opens its own fresh @Transactional(REQUIRED)
        // boundary in ReservationService → AvailabilityService.createSnapshot. A
        // failure on one row only rolls back that row's transaction; the other rows
        // continue to commit independently. This avoids the
        // UnexpectedRollbackException that occurs when the outer step transaction
        // gets marked rollback-only by an exception caught inside the loop.
        return new StepBuilder("scanAndCorrectBedHoldDrift", jobRepository)
                .tasklet(reconciliationTasklet(), new ResourcelessTransactionManager())
                .build();
    }

    @Bean
    @TenantUnscoped("Spring Batch reconciler — platform-wide defense-in-depth for bed_availability drift")
    public Tasklet reconciliationTasklet() {
        return (StepContribution contribution, ChunkContext chunkContext) -> {
            // dvAccess=true is bound by BatchJobScheduler.runJob() before Spring Batch
            // begins acquiring connections. Defense-in-depth: fail fast if something
            // changed and the binding is missing — without dvAccess, DV shelters are
            // hidden by RLS and their drift would silently never be corrected.
            if (!TenantContext.getDvAccess()) {
                throw new IllegalStateException(
                        "Bed-holds reconciliation tasklet requires dvAccess=true — "
                        + "register job with BatchJobScheduler.registerJob(name, job, cron, true)");
            }

            metrics.bedHoldReconciliationRunsCounter().increment();
            Timer.Sample sample = Timer.start();

            int corrected = 0;
            try {
                List<DriftRow> drifted = bedAvailabilityRepository.findDriftedRows();
                if (drifted.isEmpty()) {
                    log.info("Bed holds reconciliation complete: 0 corrections");
                } else {
                    log.info("Bed holds reconciliation: scanning {} drifted (shelter, population) pair(s)",
                            drifted.size());
                    for (DriftRow row : drifted) {
                        try {
                            // Wrap each per-row recompute in the row's own tenant context.
                            // The drift query runs under (tenant=null, dvAccess=true) so it
                            // sees every tenant's rows; but the recompute path calls
                            // shelterService.findById which is tenant-scoped — without the
                            // per-row tenant binding it would throw "Shelter not found".
                            TenantContext.runWithContext(row.tenantId(), true, () -> {
                                reservationService.recomputeBedsOnHold(
                                        row.shelterId(), row.populationType(),
                                        "reconciliation: drift corrected", "system:reconciliation");

                                // Write the audit row directly via the repository rather
                                // than via ApplicationEventPublisher → AuditEventService.
                                // The latter is a synchronous @EventListener: any exception
                                // inside it (caught or not) marks this tasklet's transaction
                                // rollback-only at the AOP layer, which then surfaces as
                                // UnexpectedRollbackException at Spring Batch commit time
                                // (memory: feedback_transactional_eventlistener).
                                Map<String, Object> details = new HashMap<>();
                                details.put("shelter_id", row.shelterId().toString());
                                details.put("population_type", row.populationType());
                                details.put("snapshot_value_before", row.snapshotValue());
                                details.put("actual_count", row.actualCount());
                                details.put("delta", row.delta());
                                writeAuditRowDirect(details);
                            });

                            corrected++;
                            log.info("Reconciled bed holds for shelter {} / {}: {} -> {} (delta {})",
                                    row.shelterId(), row.populationType(),
                                    row.snapshotValue(), row.actualCount(), row.delta());
                        } catch (Exception e) {
                            // Per-row failures are logged and skipped so a single bad shelter
                            // does not halt the entire reconciliation pass.
                            log.error("Failed to reconcile bed holds for shelter {} / {}: {}",
                                    row.shelterId(), row.populationType(), e.getMessage(), e);
                        }
                    }
                    log.info("Bed holds reconciliation complete: {} correction(s)", corrected);
                }
            } finally {
                sample.stop(metrics.bedHoldReconciliationDurationTimer());
            }

            if (corrected > 0) {
                metrics.bedHoldReconciliationCorrectionsCounter().increment(corrected);
            }
            if (contribution != null) {
                contribution.incrementWriteCount(corrected);
            }
            return RepeatStatus.FINISHED;
        };
    }

    private void writeAuditRowDirect(Map<String, Object> details) {
        try {
            JsonString detailsJson = new JsonString(objectMapper.writeValueAsString(details));
            // cross-tenant-isolation-audit Phase 2.12: batch reconciler emits
            // platform-wide audit events (tenant_id=null is semantically
            // correct here — the reconciliation is not tenant-scoped by design).
            // This is a legitimate @TenantUnscoped path for audit data; Phase
            // 3 ArchUnit rules accept it because the batch-job package is
            // exempt from the service-layer tenant guard.
            AuditEventEntity entity = new AuditEventEntity(
                    null,                                // tenant id (platform-wide batch)
                    null,                                // actor user id
                    null,                                // target user id
                    AuditEventTypes.BED_HOLDS_RECONCILED,
                    detailsJson,
                    null                                 // ip address
            );
            auditEventRepository.save(entity);
        } catch (Exception e) {
            // Audit write failure is logged and swallowed so a single bad audit
            // does not roll back the corrective snapshot. The corrections matter
            // more than perfect audit coverage in the rare write-failure case.
            log.error("Failed to write BED_HOLDS_RECONCILED audit row: {}", e.getMessage(), e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerWithScheduler() {
        // Every 5 minutes. dvAccess=true so DV shelter rows are visible to the
        // reconciliation query under RLS — required for the safety property.
        batchJobScheduler.registerJob("bedHoldsReconciliation", bedHoldsReconciliationJob(),
                "0 */5 * * * *", true);
    }
}
