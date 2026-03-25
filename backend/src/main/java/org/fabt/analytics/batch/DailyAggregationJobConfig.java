package org.fabt.analytics.batch;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

import javax.sql.DataSource;

import org.fabt.analytics.config.BatchJobScheduler;
import org.fabt.analytics.repository.DailyUtilizationSummaryRepository;
import org.fabt.shared.web.TenantContext;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch job: daily pre-aggregation of bed_availability snapshots into
 * daily_utilization_summary (Design D12, D13).
 *
 * Chunk-oriented: reads raw snapshots grouped by (shelter, population, date),
 * computes avg_utilization/max_occupied/min_available, upserts to summary table.
 * Commit interval 100. Restartable.
 */
@Configuration
public class DailyAggregationJobConfig {

    private static final Logger log = LoggerFactory.getLogger(DailyAggregationJobConfig.class);

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DailyUtilizationSummaryRepository summaryRepository;
    private final TenantService tenantService;
    private final DataSource analyticsDataSource;
    private final BatchJobScheduler batchJobScheduler;

    public DailyAggregationJobConfig(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            DailyUtilizationSummaryRepository summaryRepository,
            TenantService tenantService,
            @Qualifier("analyticsDataSource") DataSource analyticsDataSource,
            BatchJobScheduler batchJobScheduler) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.summaryRepository = summaryRepository;
        this.tenantService = tenantService;
        this.analyticsDataSource = analyticsDataSource;
        this.batchJobScheduler = batchJobScheduler;
    }

    @Bean
    public Job dailyAggregationJob() {
        return new JobBuilder("dailyAggregation", jobRepository)
                .start(aggregationStep())
                .build();
    }

    @Bean
    public Step aggregationStep() {
        return new StepBuilder("aggregateSnapshots", jobRepository)
                .<SnapshotAggregate, SnapshotAggregate>chunk(100, transactionManager)
                .reader(snapshotAggregateReader())
                .processor(passThroughProcessor())
                .writer(summaryWriter())
                .build();
    }

    /**
     * Reads pre-aggregated snapshot data grouped by (tenant, shelter, population_type, date).
     * Uses the analytics DataSource to avoid impacting OLTP.
     */
    @Bean
    public JdbcCursorItemReader<SnapshotAggregate> snapshotAggregateReader() {
        JdbcCursorItemReader<SnapshotAggregate> reader = new JdbcCursorItemReader<>();
        reader.setDataSource(analyticsDataSource);
        reader.setSql("""
                SELECT tenant_id, shelter_id, population_type,
                       DATE(snapshot_ts) AS snapshot_date,
                       AVG(CASE WHEN beds_total > 0
                           THEN CAST(beds_occupied AS DOUBLE PRECISION) / beds_total
                           ELSE 0 END) AS avg_utilization,
                       MAX(beds_occupied) AS max_occupied,
                       MIN(beds_total - beds_occupied - beds_on_hold) AS min_available,
                       COUNT(*) AS snapshot_count
                FROM bed_availability
                WHERE DATE(snapshot_ts) = CURRENT_DATE - INTERVAL '1 day'
                GROUP BY tenant_id, shelter_id, population_type, DATE(snapshot_ts)
                ORDER BY tenant_id, shelter_id, population_type
                """);
        reader.setRowMapper(this::mapAggregate);
        return reader;
    }

    private ItemProcessor<SnapshotAggregate, SnapshotAggregate> passThroughProcessor() {
        return item -> item;
    }

    private ItemWriter<SnapshotAggregate> summaryWriter() {
        return items -> {
            for (SnapshotAggregate agg : items) {
                summaryRepository.upsert(
                        agg.tenantId(), agg.shelterId(), agg.populationType(),
                        agg.summaryDate(), agg.avgUtilization(),
                        agg.maxOccupied(), agg.minAvailable(), agg.snapshotCount());
            }
        };
    }

    private SnapshotAggregate mapAggregate(ResultSet rs, int rowNum) throws SQLException {
        return new SnapshotAggregate(
                UUID.fromString(rs.getString("tenant_id")),
                UUID.fromString(rs.getString("shelter_id")),
                rs.getString("population_type"),
                rs.getDate("snapshot_date").toLocalDate(),
                rs.getDouble("avg_utilization"),
                rs.getInt("max_occupied"),
                rs.getInt("min_available"),
                rs.getInt("snapshot_count")
        );
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerWithScheduler() {
        batchJobScheduler.registerJob("dailyAggregation", dailyAggregationJob(), "0 0 3 * * *");
    }

    record SnapshotAggregate(
            UUID tenantId,
            UUID shelterId,
            String populationType,
            LocalDate summaryDate,
            double avgUtilization,
            int maxOccupied,
            int minAvailable,
            int snapshotCount
    ) {}
}
