package org.fabt.analytics.config;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.fabt.shared.web.TenantContext;
import org.fabt.tenant.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

/**
 * Dynamically registers Spring Batch jobs with cron expressions from tenant config (Design D13).
 *
 * Reads {@code batch_schedules} from tenant config JSONB:
 * <pre>
 * {
 *   "batch_schedules": {
 *     "dailyAggregation": { "cron": "0 0 3 * * *", "enabled": true },
 *     "hmisPush":         { "cron": "0 0 *&#47;6 * * *", "enabled": true },
 *     "hicExport":        { "cron": "0 0 4 29 1 *", "enabled": true },
 *     "pitExport":        { "cron": "0 0 4 29 1 *", "enabled": true }
 *   }
 * }
 * </pre>
 *
 * Supports runtime schedule updates: call {@link #rescheduleJob} to change cron
 * or enable/disable a job without restart.
 */
@Configuration
public class BatchJobScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(BatchJobScheduler.class);

    private static final Map<String, String> DEFAULT_SCHEDULES = Map.of(
            "dailyAggregation", "0 0 3 * * *",
            "hmisPush", "0 0 */6 * * *",
            "hicExport", "0 0 4 29 1 *",
            "pitExport", "0 0 4 29 1 *"
    );

    private final JobLauncher jobLauncher;
    private final Map<String, Job> registeredJobs = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();
    private final Map<String, String> currentCrons = new ConcurrentHashMap<>();
    private final Map<String, Boolean> enabledState = new ConcurrentHashMap<>();
    private final Map<String, Boolean> dvAccessRequired = new ConcurrentHashMap<>();
    private TaskScheduler taskScheduler;

    public BatchJobScheduler(JobLauncher jobLauncher) {
        this.jobLauncher = jobLauncher;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        this.taskScheduler = registrar.getScheduler();
    }

    /**
     * Register a Spring Batch job for dynamic scheduling.
     * Called by job configuration beans during startup.
     */
    public void registerJob(String jobName, Job job, String defaultCron) {
        registerJob(jobName, job, defaultCron, false);
    }

    /**
     * Register a Spring Batch job that requires DV access for RLS context.
     *
     * <p>When {@code dvAccess} is true, the job execution is wrapped in
     * {@link TenantContext#runWithContext} with dvAccess=true. This ensures that
     * when Spring Batch's TransactionManager acquires a connection (before the
     * Tasklet/Step code runs), the RLS-aware DataSource reads dvAccess=true from
     * TenantContext and sets {@code app.dv_access='true'} on the connection.</p>
     *
     * <p>Without this, jobs that query DV-protected tables (referral_token, shelter)
     * see zero rows because ScopedValue is not bound when the connection is acquired.</p>
     */
    public void registerJob(String jobName, Job job, String defaultCron, boolean dvAccess) {
        registeredJobs.put(jobName, job);
        currentCrons.put(jobName, defaultCron);
        enabledState.put(jobName, true);
        dvAccessRequired.put(jobName, dvAccess);
        scheduleJob(jobName, defaultCron);
        log.info("Registered batch job '{}' with cron '{}' (dvAccess={})", jobName, defaultCron, dvAccess);
    }

    /**
     * Update the cron expression for a job and reschedule.
     */
    public void rescheduleJob(String jobName, String newCron) {
        if (!registeredJobs.containsKey(jobName)) {
            throw new IllegalArgumentException("Unknown job: " + jobName);
        }
        cancelSchedule(jobName);
        currentCrons.put(jobName, newCron);
        if (Boolean.TRUE.equals(enabledState.get(jobName))) {
            scheduleJob(jobName, newCron);
        }
        log.info("Rescheduled batch job '{}' with cron '{}'", jobName, newCron);
    }

    /**
     * Enable or disable a scheduled job.
     */
    public void setEnabled(String jobName, boolean enabled) {
        if (!registeredJobs.containsKey(jobName)) {
            throw new IllegalArgumentException("Unknown job: " + jobName);
        }
        enabledState.put(jobName, enabled);
        if (enabled) {
            scheduleJob(jobName, currentCrons.get(jobName));
        } else {
            cancelSchedule(jobName);
        }
        log.info("Batch job '{}' {}", jobName, enabled ? "enabled" : "disabled");
    }

    /**
     * Trigger a manual run of a batch job with optional parameters.
     * Respects the job's dvAccess flag for proper RLS context.
     */
    public void triggerJob(String jobName, Map<String, String> params) throws Exception {
        Job job = registeredJobs.get(jobName);
        if (job == null) {
            throw new IllegalArgumentException("Unknown job: " + jobName);
        }
        boolean dvAccess = dvAccessRequired.getOrDefault(jobName, false);
        JobParametersBuilder builder = new JobParametersBuilder();
        builder.addString("triggerTime", Instant.now().toString());
        if (params != null) {
            params.forEach(builder::addString);
        }
        JobParameters jobParams = builder.toJobParameters();
        if (dvAccess) {
            TenantContext.runWithContext(null, true, () -> {
                try {
                    jobLauncher.run(job, jobParams);
                } catch (Exception e) {
                    throw new RuntimeException("Manual trigger failed for job: " + jobName, e);
                }
            });
        } else {
            jobLauncher.run(job, jobParams);
        }
        log.info("Manually triggered batch job '{}' (dvAccess={})", jobName, dvAccess);
    }

    public Map<String, String> getCurrentCrons() {
        return Map.copyOf(currentCrons);
    }

    public Map<String, Boolean> getEnabledState() {
        return Map.copyOf(enabledState);
    }

    public boolean isRegistered(String jobName) {
        return registeredJobs.containsKey(jobName);
    }

    private void scheduleJob(String jobName, String cron) {
        if (taskScheduler == null) {
            log.debug("TaskScheduler not yet available; job '{}' will be scheduled on next refresh", jobName);
            return;
        }
        cancelSchedule(jobName);
        Job job = registeredJobs.get(jobName);
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> runJob(jobName, job),
                new CronTrigger(cron));
        scheduledFutures.put(jobName, future);
    }

    private void cancelSchedule(String jobName) {
        ScheduledFuture<?> future = scheduledFutures.remove(jobName);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void runJob(String jobName, Job job) {
        boolean dvAccess = dvAccessRequired.getOrDefault(jobName, false);
        if (dvAccess) {
            // Wrap entire job execution in TenantContext so that when Spring Batch's
            // TransactionManager acquires connections, the RLS-aware DataSource reads
            // dvAccess=true from TenantContext. SimpleJobLauncher runs synchronously
            // on the caller's thread, so ScopedValue scope covers all connection acquisitions.
            TenantContext.runWithContext(null, dvAccess, () -> launchJob(jobName, job));
        } else {
            launchJob(jobName, job);
        }
    }

    private void launchJob(String jobName, Job job) {
        try {
            JobParameters params = new JobParametersBuilder()
                    .addString("scheduledTime", Instant.now().toString())
                    .toJobParameters();
            jobLauncher.run(job, params);
            log.info("Scheduled batch job '{}' completed", jobName);
        } catch (Exception e) {
            log.error("Scheduled batch job '{}' failed", jobName, e);
        }
    }
}
