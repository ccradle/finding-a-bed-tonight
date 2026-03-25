package org.fabt.analytics.batch;

import java.util.UUID;

import org.fabt.analytics.config.BatchJobScheduler;
import org.fabt.hmis.service.HmisPushService;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch job: HMIS push (Design D13).
 *
 * Refactored from {@code HmisPushScheduler}'s @Scheduled method. The business logic
 * (outbox creation, vendor push, retry/dead-letter) remains in {@link HmisPushService} —
 * only the scheduling and retry wrapper moves to Batch.
 *
 * Step 1: Create outbox entries for all tenants with enabled vendors.
 * Step 2: Process pending outbox entries (push to vendor, retry/skip).
 */
@Configuration
public class HmisPushJobConfig {

    private static final Logger log = LoggerFactory.getLogger(HmisPushJobConfig.class);

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final HmisPushService hmisPushService;
    private final TenantService tenantService;
    private final BatchJobScheduler batchJobScheduler;

    public HmisPushJobConfig(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            HmisPushService hmisPushService,
            TenantService tenantService,
            BatchJobScheduler batchJobScheduler) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.hmisPushService = hmisPushService;
        this.tenantService = tenantService;
        this.batchJobScheduler = batchJobScheduler;
    }

    @Bean
    public Job hmisPushJob() {
        return new JobBuilder("hmisPush", jobRepository)
                .start(createOutboxStep())
                .next(processOutboxStep())
                .build();
    }

    @Bean
    public Step createOutboxStep() {
        return new StepBuilder("createOutboxEntries", jobRepository)
                .tasklet(createOutboxTasklet(), transactionManager)
                .build();
    }

    @Bean
    public Step processOutboxStep() {
        return new StepBuilder("processOutboxEntries", jobRepository)
                .tasklet(processOutboxTasklet(), transactionManager)
                .build();
    }

    private Tasklet createOutboxTasklet() {
        return (StepContribution contribution, ChunkContext chunkContext) -> {
            int totalCreated = 0;
            for (Tenant tenant : tenantService.findAll()) {
                try {
                    int created = hmisPushService.createOutboxEntries(tenant.getId());
                    totalCreated += created;
                    if (created > 0) {
                        log.info("Created {} HMIS outbox entries for tenant {}", created, tenant.getId());
                    }
                } catch (Exception e) {
                    log.error("Failed to create outbox entries for tenant {}: {}", tenant.getId(), e.getMessage());
                }
            }
            contribution.incrementWriteCount(totalCreated);
            return RepeatStatus.FINISHED;
        };
    }

    private Tasklet processOutboxTasklet() {
        return (StepContribution contribution, ChunkContext chunkContext) -> {
            hmisPushService.processOutbox();
            return RepeatStatus.FINISHED;
        };
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerWithScheduler() {
        batchJobScheduler.registerJob("hmisPush", hmisPushJob(), "0 0 */6 * * *");
    }
}
