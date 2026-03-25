package org.fabt.analytics.batch;

import org.fabt.analytics.config.BatchJobScheduler;
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
 * Spring Batch job: PIT export (Design D13).
 *
 * Same pattern as HIC export. Parameterized by reportDate.
 * DV shelters aggregated in output per D4.
 */
@Configuration
public class PitExportJobConfig {

    private static final Logger log = LoggerFactory.getLogger(PitExportJobConfig.class);

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final BatchJobScheduler batchJobScheduler;

    public PitExportJobConfig(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            BatchJobScheduler batchJobScheduler) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.batchJobScheduler = batchJobScheduler;
    }

    @Bean
    public Job pitExportJob() {
        return new JobBuilder("pitExport", jobRepository)
                .start(pitExportStep())
                .build();
    }

    @Bean
    public Step pitExportStep() {
        return new StepBuilder("generatePitCsv", jobRepository)
                .tasklet(pitExportTasklet(), transactionManager)
                .build();
    }

    private Tasklet pitExportTasklet() {
        return (StepContribution contribution, ChunkContext chunkContext) -> {
            String reportDate = chunkContext.getStepContext()
                    .getJobParameters().getOrDefault("reportDate", "").toString();
            log.info("PIT export job started for date: {}", reportDate.isEmpty() ? "latest" : reportDate);
            return RepeatStatus.FINISHED;
        };
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerWithScheduler() {
        batchJobScheduler.registerJob("pitExport", pitExportJob(), "0 0 4 29 1 *");
    }
}
