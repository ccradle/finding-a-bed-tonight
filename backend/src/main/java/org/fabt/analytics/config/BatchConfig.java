package org.fabt.analytics.config;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Batch configuration (Design D13).
 *
 * - {@code spring.batch.job.enabled=false} — jobs do not auto-run on startup.
 * - {@code spring.batch.jdbc.initialize-schema=never} — Flyway manages DDL (V24).
 * - JobLauncher and JobRepository auto-configured from the primary DataSource.
 * - Cron expressions stored in tenant config JSONB, editable from Admin UI.
 */
@Configuration
@EnableBatchProcessing
public class BatchConfig {
    // Spring Boot auto-configures JobLauncher, JobRepository, JobExplorer, and
    // JobOperator using the @Primary DataSource. No explicit beans needed here.
}
