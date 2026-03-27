package org.fabt.analytics.config;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.fabt.tenant.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
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
@EnableScheduling
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
        registeredJobs.put(jobName, job);
        currentCrons.put(jobName, defaultCron);
        enabledState.put(jobName, true);
        scheduleJob(jobName, defaultCron);
        log.info("Registered batch job '{}' with cron '{}'", jobName, defaultCron);
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
     */
    public void triggerJob(String jobName, Map<String, String> params) throws Exception {
        Job job = registeredJobs.get(jobName);
        if (job == null) {
            throw new IllegalArgumentException("Unknown job: " + jobName);
        }
        JobParametersBuilder builder = new JobParametersBuilder();
        builder.addString("triggerTime", Instant.now().toString());
        if (params != null) {
            params.forEach(builder::addString);
        }
        jobLauncher.run(job, builder.toJobParameters());
        log.info("Manually triggered batch job '{}'", jobName);
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
