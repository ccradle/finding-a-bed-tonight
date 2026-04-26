package org.fabt.referral.batch;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.fabt.analytics.config.BatchJobScheduler;
import org.fabt.referral.service.ReferralTokenService;
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.audit.AuditEventType;
import org.fabt.shared.security.TenantUnscoped;
import org.fabt.shared.web.TenantContext;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantService;
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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch job: prune stale PENDING DV referrals from demo tenants
 * (G-4.5 §6.10).
 *
 * <p>Runs every 6 hours via {@link BatchJobScheduler} with {@code dvAccess=true}
 * (referral_token RLS filters by {@code app.dv_access='true'}). For every
 * tenant whose slug starts with {@code dev-}, deletes PENDING referrals
 * whose {@code created_at} is older than 48 hours and emits one
 * {@link AuditEventType#DV_REFERRAL_DEMO_CLEANUP} row per affected tenant
 * carrying the deleted count and cutoff timestamp.</p>
 *
 * <p><b>Two-layer gate (Marcus Webb / Casey Drummond):</b></p>
 * <ol>
 *   <li><b>Spring profile</b> — the bean is annotated {@code @Profile("demo")}
 *       so the job does not even register in non-demo deployments. A real
 *       customer production environment never sees this code path.</li>
 *   <li><b>Slug filter</b> — even when active, the tasklet only touches
 *       tenants with slug starting in {@code dev-}. If a future demo
 *       deployment adds a non-dev slug, the cleanup is a no-op for it.
 *       Defense in depth against the unlikely case where a non-demo tenant
 *       lands in a demo environment.</li>
 * </ol>
 *
 * <p><b>Why the bare DELETE is OK (Riley Cho):</b> the DV referral domain
 * carries zero client PII (callback number is the worker's, not the
 * client's; the rest is operational fields like household size + urgency).
 * Deleting a PENDING row destroys no privacy-bearing record. The audit
 * row records the count + cutoff so the chain remains intact even though
 * the underlying rows are gone.</p>
 */
@Configuration
@Profile("demo")
public class DvReferralDemoCleanupJobConfig {

    private static final Logger log = LoggerFactory.getLogger(DvReferralDemoCleanupJobConfig.class);

    /**
     * PENDING referrals older than this are deleted on each run. Sized at
     * 48 hours so a demo visitor's referrals stay visible across same-day
     * sessions but the queue does not accumulate week-old PENDING noise.
     */
    private static final Duration STALE_AGE = Duration.ofHours(48);

    /**
     * Tenant slugs starting with this prefix are treated as demo tenants.
     * Matches the {@code dev-coc}, {@code dev-coc-west}, {@code dev-coc-east}
     * tenants seeded by {@code infra/scripts/seed-data.sql}.
     */
    private static final String DEMO_SLUG_PREFIX = "dev-";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final ReferralTokenService referralTokenService;
    private final TenantService tenantService;
    private final ApplicationEventPublisher eventPublisher;
    private final BatchJobScheduler batchJobScheduler;

    public DvReferralDemoCleanupJobConfig(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ReferralTokenService referralTokenService,
            TenantService tenantService,
            ApplicationEventPublisher eventPublisher,
            BatchJobScheduler batchJobScheduler) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.referralTokenService = referralTokenService;
        this.tenantService = tenantService;
        this.eventPublisher = eventPublisher;
        this.batchJobScheduler = batchJobScheduler;
    }

    @Bean
    public Job dvReferralDemoCleanupJob() {
        return new JobBuilder("dvReferralDemoCleanup", jobRepository)
                .start(dvReferralDemoCleanupStep())
                .build();
    }

    @Bean
    public Step dvReferralDemoCleanupStep() {
        return new StepBuilder("pruneStaleDemoReferrals", jobRepository)
                .tasklet(dvReferralDemoCleanupTasklet(), transactionManager)
                .build();
    }

    @Bean
    @TenantUnscoped("Iterates all demo tenants; per-tenant DELETE binds TenantContext per iteration")
    public Tasklet dvReferralDemoCleanupTasklet() {
        return (StepContribution contribution, ChunkContext chunkContext) -> {
            if (!TenantContext.getDvAccess()) {
                throw new IllegalStateException("Demo cleanup tasklet requires dvAccess=true");
            }

            Instant cutoff = Instant.now().minus(STALE_AGE);
            int totalDeleted = 0;

            for (Tenant tenant : tenantService.findAll()) {
                String slug = tenant.getSlug();
                if (slug == null || !slug.startsWith(DEMO_SLUG_PREFIX)) {
                    continue;
                }

                UUID tenantId = tenant.getId();
                // callWithContext is the return-value variant of runWithContext;
                // the lambda returns an int so we must use the call form.
                int deleted = TenantContext.<Integer, RuntimeException>callWithContext(
                        tenantId, true,
                        () -> referralTokenService.deleteStalePendingForTenant(tenantId, cutoff));

                if (deleted > 0) {
                    totalDeleted += deleted;
                    publishCleanupAudit(tenantId, slug, deleted, cutoff);
                    log.info("DV referral demo cleanup: deleted {} stale PENDING rows for tenant slug={}",
                            deleted, slug);
                }
            }

            if (contribution != null) {
                contribution.incrementWriteCount(totalDeleted);
            }
            return RepeatStatus.FINISHED;
        };
    }

    private void publishCleanupAudit(UUID tenantId, String slug, int deletedCount, Instant cutoff) {
        // LinkedHashMap to keep field order deterministic in the canonical
        // JSON the audit chain hashes — see Phase G-1 canonicalizer contract.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("deleted_count", deletedCount);
        details.put("cutoff_at", cutoff.toString());
        details.put("tenant_slug", slug);

        // Bind tenant scope so the audit_events row lands under this tenant's
        // chain (audit_events RLS filters by app.tenant_id). actorUserId is
        // null because the batch job is system-driven.
        TenantContext.runWithContext(tenantId, true, () ->
                eventPublisher.publishEvent(new AuditEventRecord(
                        null, null, AuditEventType.DV_REFERRAL_DEMO_CLEANUP,
                        details, null)));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerWithScheduler() {
        // Every 6 hours at the top of the hour. Cron field order is
        // sec min hour day month weekday.
        batchJobScheduler.registerJob("dvReferralDemoCleanup", dvReferralDemoCleanupJob(),
                "0 0 */6 * * *", true);
    }
}
