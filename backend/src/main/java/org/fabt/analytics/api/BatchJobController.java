package org.fabt.analytics.api;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.fabt.analytics.config.BatchJobScheduler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Batch job management API (Design D14).
 * GET endpoints: COC_ADMIN, PLATFORM_ADMIN.
 * Mutating endpoints: PLATFORM_ADMIN only.
 */
@RestController
@RequestMapping("/api/v1/batch/jobs")
@Tag(name = "Batch Jobs", description = "Spring Batch job management and monitoring")
public class BatchJobController {

    private final JobExplorer jobExplorer;
    private final JobOperator jobOperator;
    private final BatchJobScheduler batchJobScheduler;

    public BatchJobController(JobExplorer jobExplorer,
                               JobOperator jobOperator,
                               BatchJobScheduler batchJobScheduler) {
        this.jobExplorer = jobExplorer;
        this.jobOperator = jobOperator;
        this.batchJobScheduler = batchJobScheduler;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(summary = "List all batch jobs",
            description = "Returns all registered batch jobs with current schedule, enabled/disabled state, and last execution status.")
    public ResponseEntity<List<Map<String, Object>>> listJobs() {
        List<Map<String, Object>> jobs = new ArrayList<>();
        Map<String, String> crons = batchJobScheduler.getCurrentCrons();
        Map<String, Boolean> enabled = batchJobScheduler.getEnabledState();

        for (Map.Entry<String, String> entry : crons.entrySet()) {
            String jobName = entry.getKey();
            Map<String, Object> jobInfo = new HashMap<>();
            jobInfo.put("jobName", jobName);
            jobInfo.put("cron", entry.getValue());
            jobInfo.put("enabled", enabled.getOrDefault(jobName, false));

            // Last execution status
            List<JobInstance> instances = jobExplorer.getJobInstances(jobName, 0, 1);
            if (!instances.isEmpty()) {
                List<JobExecution> executions = jobExplorer.getJobExecutions(instances.get(0));
                if (!executions.isEmpty()) {
                    JobExecution last = executions.get(0);
                    jobInfo.put("lastStatus", last.getStatus().name());
                    jobInfo.put("lastStartTime", last.getStartTime());
                    jobInfo.put("lastEndTime", last.getEndTime());
                }
            }

            jobs.add(jobInfo);
        }

        return ResponseEntity.ok(jobs);
    }

    @GetMapping("/{jobName}/executions")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(summary = "Job execution history",
            description = "Returns execution history for a specific job with step-level detail.")
    public ResponseEntity<List<Map<String, Object>>> getExecutions(@PathVariable String jobName) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<JobInstance> instances = jobExplorer.getJobInstances(jobName, 0, 20);

        for (JobInstance instance : instances) {
            for (JobExecution execution : jobExplorer.getJobExecutions(instance)) {
                Map<String, Object> execInfo = new HashMap<>();
                execInfo.put("executionId", execution.getId());
                execInfo.put("status", execution.getStatus().name());
                execInfo.put("startTime", execution.getStartTime());
                execInfo.put("endTime", execution.getEndTime());
                if (execution.getExitStatus() != null) {
                    execInfo.put("exitCode", execution.getExitStatus().getExitCode());
                    execInfo.put("exitMessage", execution.getExitStatus().getExitDescription());
                }

                if (execution.getStartTime() != null && execution.getEndTime() != null) {
                    execInfo.put("durationMs", Duration.between(
                            execution.getStartTime().atZone(ZoneId.systemDefault()).toInstant(),
                            execution.getEndTime().atZone(ZoneId.systemDefault()).toInstant()).toMillis());
                }

                // Step detail
                List<Map<String, Object>> steps = new ArrayList<>();
                for (StepExecution step : execution.getStepExecutions()) {
                    Map<String, Object> stepInfo = new HashMap<>();
                    stepInfo.put("stepName", step.getStepName());
                    stepInfo.put("status", step.getStatus().name());
                    stepInfo.put("readCount", step.getReadCount());
                    stepInfo.put("writeCount", step.getWriteCount());
                    stepInfo.put("skipCount", step.getSkipCount());
                    stepInfo.put("commitCount", step.getCommitCount());
                    steps.add(stepInfo);
                }
                execInfo.put("steps", steps);

                result.add(execInfo);
            }
        }

        return ResponseEntity.ok(result);
    }

    @PostMapping("/{jobName}/run")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Trigger manual job run",
            description = "Triggers a batch job immediately with optional date parameter.")
    public ResponseEntity<Map<String, Object>> triggerRun(
            @PathVariable String jobName,
            @RequestBody(required = false) Map<String, String> params) {
        try {
            batchJobScheduler.triggerJob(jobName, params);
            return ResponseEntity.ok(Map.of("status", "triggered", "jobName", jobName));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{jobName}/restart/{executionId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Restart failed execution",
            description = "Restarts a failed job execution from the last committed chunk.")
    public ResponseEntity<Map<String, Object>> restartExecution(
            @PathVariable String jobName,
            @PathVariable long executionId) {
        try {
            Long newExecutionId = jobOperator.restart(executionId);
            return ResponseEntity.ok(Map.of(
                    "status", "restarted",
                    "originalExecutionId", executionId,
                    "newExecutionId", newExecutionId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{jobName}/schedule")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Update job schedule",
            description = "Updates the cron expression for a batch job.")
    public ResponseEntity<Map<String, Object>> updateSchedule(
            @PathVariable String jobName,
            @RequestBody Map<String, String> body) {
        String cron = body.get("cron");
        if (cron == null || cron.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "cron is required"));
        }
        try {
            batchJobScheduler.rescheduleJob(jobName, cron);
            return ResponseEntity.ok(Map.of("jobName", jobName, "cron", cron));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{jobName}/enable")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Enable or disable job",
            description = "Enables or disables a batch job's scheduled execution.")
    public ResponseEntity<Map<String, Object>> setEnabled(
            @PathVariable String jobName,
            @RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "enabled is required"));
        }
        try {
            batchJobScheduler.setEnabled(jobName, enabled);
            return ResponseEntity.ok(Map.of("jobName", jobName, "enabled", enabled));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
