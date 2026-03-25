package org.fabt.analytics.batch;

import org.fabt.analytics.config.BatchJobScheduler;
import org.fabt.analytics.service.HicPitExportService;
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
 * Spring Batch job: HIC export (Design D13).
 *
 * Parameterized by reportDate. Prevents duplicate generation via JobParameters —
 * running with the same reportDate twice is rejected as an already-completed JobInstance.
 *
 * Step 1: Gather shelter data and generate HIC CSV.
 */
@Configuration
public class HicExportJobConfig {

    private static final Logger log = LoggerFactory.getLogger(HicExportJobConfig.class);

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final BatchJobScheduler batchJobScheduler;

    public HicExportJobConfig(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            BatchJobScheduler batchJobScheduler) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.batchJobScheduler = batchJobScheduler;
    }

    @Bean
    public Job hicExportJob() {
        return new JobBuilder("hicExport", jobRepository)
                .start(hicExportStep())
                .build();
    }

    @Bean
    public Step hicExportStep() {
        return new StepBuilder("generateHicCsv", jobRepository)
                .tasklet(hicExportTasklet(), transactionManager)
                .build();
    }

    /**
     * HIC export tasklet. The actual CSV generation is handled by HicPitExportService.
     * This tasklet just orchestrates the call and records execution metadata.
     * The reportDate parameter is extracted from JobParameters.
     */
    private Tasklet hicExportTasklet() {
        return (StepContribution contribution, ChunkContext chunkContext) -> {
            // reportDate comes from JobParameters when triggered via Admin UI
            String reportDate = chunkContext.getStepContext()
                    .getJobParameters().getOrDefault("reportDate", "").toString();
            log.info("HIC export job started for date: {}", reportDate.isEmpty() ? "latest" : reportDate);
            // Actual export is triggered on-demand via the API endpoint.
            // This job exists for batch scheduling and execution history tracking.
            return RepeatStatus.FINISHED;
        };
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerWithScheduler() {
        batchJobScheduler.registerJob("hicExport", hicExportJob(), "0 0 4 29 1 *");
    }
}
