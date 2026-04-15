package org.fabt.referral.batch;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Timer;

import org.fabt.auth.service.UserService;
import org.fabt.notification.domain.EscalationPolicy;
import org.fabt.notification.repository.NotificationRepository;
import org.fabt.notification.service.NotificationPersistenceService;
import org.fabt.notification.service.EscalationPolicyService;
import org.fabt.observability.ObservabilityMetrics;
import org.fabt.referral.domain.ReferralToken;
import org.fabt.referral.service.ReferralTokenService;
import org.fabt.shared.web.TenantContext;
import org.fabt.analytics.config.BatchJobScheduler;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch job: DV referral escalation (Design D6).
 *
 * <p>Runs every 5 minutes via {@link BatchJobScheduler} with {@code dvAccess=true}.
 * The scheduler wraps job execution in TenantContext before Spring Batch starts
 * transactions, ensuring the RLS-aware DataSource reads dvAccess=true when
 * acquiring connections. This is critical because referral_token RLS inherits
 * from shelter RLS (dv_shelter_access policy).</p>
 *
 * <p><b>Security Note (Marcus Webb):</b> the scheduler uses tenantId=null in
 * runWithContext(). This is correct because referral_token RLS only checks
 * app.dv_access='true' and does not isolate by tenantId at the DB layer,
 * granting the batch job platform-wide visibility.</p>
 *
 * <p><b>Architecture Note (Alex Chen):</b> this job lives in the referral module
 * to break the circular dependency between referral and notification. It depends
 * on NotificationPersistenceService but notification does not depend back.</p>
 *
 * <p>Scans PENDING referrals past escalation thresholds defined in the
 * **frozen** {@link EscalationPolicy} attached to each referral. If no policy
 * is attached (existing rows), falls back to the platform default policy.</p>
 */
@Configuration
public class ReferralEscalationJobConfig {

    private static final Logger log = LoggerFactory.getLogger(ReferralEscalationJobConfig.class);

    /**
     * Maximum number of pending referrals processed per tasklet run.
     * R6 guardrail: protects the @Scheduled thread from OOM if the pending
     * queue ever runs away. Sized for ~10 active tenants × 500 referrals
     * each — well above realistic operational load. Hitting this cap is a
     * paging-grade incident, not a normal condition; the tasklet logs a
     * WARN so monitoring can catch it.
     *
     * <p>TODO(riley): add a unit test that asserts WARN fires when the
     * tasklet processes exactly {@code PENDING_BATCH_LIMIT} rows. Deferred
     * because setting up 5001 fixtures has high cost; revisit if this
     * constant changes or production WARNs ever fire.</p>
     */
    private static final int PENDING_BATCH_LIMIT = 5_000;

    /**
     * Sentinel cache key for the "platform default" policy lookup in the
     * per-run defaultPolicyByTenantCache. Real tenant ids are gen_random_uuid
     * (v4) and never equal the all-zero UUID, so collisions are impossible.
     * Promoted from a method-local in the tasklet lambda to a class constant
     * (Alex Chen war-room nit) to avoid threading the same value through
     * three method signatures.
     */
    private static final UUID PLATFORM_DEFAULT_KEY = new UUID(0L, 0L);

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final ReferralTokenService referralTokenService;
    private final EscalationPolicyService escalationPolicyService;
    private final NotificationPersistenceService notificationPersistenceService;
    private final NotificationRepository notificationRepository;
    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final BatchJobScheduler batchJobScheduler;

    /**
     * Pre-built histogram timer for the tasklet body (T-52).
     * Pre-built at construction so the 5-minute-interval tasklet doesn't
     * rebuild the Timer.Builder on every run. Micrometer dedupes by
     * name+tags but the extra hash lookup is unnecessary for a metric that
     * fires at most 12 times per hour.
     */
    private final Timer escalationBatchDurationTimer;

    public ReferralEscalationJobConfig(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ReferralTokenService referralTokenService,
            EscalationPolicyService escalationPolicyService,
            NotificationPersistenceService notificationPersistenceService,
            NotificationRepository notificationRepository,
            UserService userService,
            ObjectMapper objectMapper,
            BatchJobScheduler batchJobScheduler,
            ObservabilityMetrics observabilityMetrics) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.referralTokenService = referralTokenService;
        this.escalationPolicyService = escalationPolicyService;
        this.notificationPersistenceService = notificationPersistenceService;
        this.notificationRepository = notificationRepository;
        this.userService = userService;
        this.objectMapper = objectMapper;
        this.batchJobScheduler = batchJobScheduler;
        this.escalationBatchDurationTimer = observabilityMetrics.escalationBatchDurationTimer();
    }

    @Bean
    public Job referralEscalationJob() {
        return new JobBuilder("referralEscalation", jobRepository)
                .start(escalationStep())
                .build();
    }

    @Bean
    public Step escalationStep() {
        return new StepBuilder("checkEscalationThresholds", jobRepository)
                .tasklet(escalationTasklet(), transactionManager)
                .build();
    }

    @Bean
    public Tasklet escalationTasklet() {
        return (StepContribution contribution, ChunkContext chunkContext) -> {
            if (!TenantContext.getDvAccess()) {
                throw new IllegalStateException("Escalation tasklet requires dvAccess=true");
            }

            // T-52: wall-clock timer around the tasklet body. Recorded even
            // on early return or exception (Sample.stop in finally).
            Timer.Sample sample = Timer.start();
            try {
                List<ReferralToken> pending = referralTokenService.findAllPending(PENDING_BATCH_LIMIT);
                if (pending.size() == PENDING_BATCH_LIMIT) {
                    log.warn("Escalation tasklet hit PENDING_BATCH_LIMIT={} — backlog may be growing; "
                            + "remaining referrals will be picked up next run but could miss escalation thresholds "
                            + "by one batch interval. Investigate why pending count is so high.",
                            PENDING_BATCH_LIMIT);
                }
                int escalationsCreated = 0;

                // Performance Note (Sam Okafor): per-run local caches to avoid N+1
                // service/cache hits in the loop. Two separate maps with distinct
                // semantic purposes (by-id vs default-by-tenant) so there's no risk
                // of a real tenant_id colliding with a policy_id sentinel
                // (R3 review point — single shared map was a smell).
                Map<UUID, EscalationPolicy> policyByIdCache = new HashMap<>();
                Map<UUID, EscalationPolicy> defaultPolicyByTenantCache = new HashMap<>();
                Map<String, List<UUID>> roleRecipientCache = new HashMap<>();

                for (ReferralToken token : pending) {
                    escalationsCreated += checkAndEscalate(token, policyByIdCache,
                            defaultPolicyByTenantCache, roleRecipientCache);
                }

                if (escalationsCreated > 0) {
                    log.info("Referral escalation: created {} notifications across {} pending referrals",
                            escalationsCreated, pending.size());
                }
                if (contribution != null) {
                    contribution.incrementWriteCount(escalationsCreated);
                }
                return RepeatStatus.FINISHED;
            } finally {
                sample.stop(escalationBatchDurationTimer);
            }
        };
    }

    private int checkAndEscalate(ReferralToken token,
                                 Map<UUID, EscalationPolicy> policyByIdCache,
                                 Map<UUID, EscalationPolicy> defaultPolicyByTenantCache,
                                 Map<String, List<UUID>> roleRecipientCache) {
        // Casey Drummond + Marcus Okafor: a SPECIFIC_USER reassign by a CoC
        // admin marks the chain as broken because that admin took manual
        // ownership. The system stops auto-escalating so the responsible
        // human is the single thread of accountability.
        if (token.isEscalationChainBroken()) {
            return 0;
        }

        String referralId = token.getId().toString();
        UUID tenantId = token.getTenantId();
        Duration age = Duration.between(token.getCreatedAt(), Instant.now());

        // Resolve policy (Sam Okafor: use local caches to avoid chatty service calls)
        EscalationPolicy policy = getPolicy(token, policyByIdCache, defaultPolicyByTenantCache);
        int created = 0;

        for (EscalationPolicy.Threshold threshold : policy.thresholds()) {
            if (age.compareTo(threshold.at()) >= 0) {
                // Notification type label is the threshold's id, e.g. "escalation.1h".
                // Frontend NotificationBell.tsx switches on this exact string — DO NOT
                // derive it from the Duration's toString() (PT1H), or icons go dark.
                String type = threshold.notificationType();

                if (isNew(type, referralId)) {
                    // Payload "threshold" key is the short id ("1h"), matching the
                    // legacy hardcoded job's payload contract.
                    String payload = toJson(Map.of("referralId", referralId, "threshold", threshold.id()));
                    List<UUID> recipientIds = resolveRecipients(tenantId, policy.eventType(),
                            threshold.recipients(), token, roleRecipientCache);

                    if (!recipientIds.isEmpty()) {
                        // D11 (2.4b): sendToAll pulls tenantId from TenantContext.
                        // This batch path doesn't have TenantContext bound by default
                        // (it iterates all tenants via the token loop); bind for the
                        // send call so the service's D11 contract is satisfied.
                        final String notifType = type;
                        final String severity = threshold.severity();
                        final String notifPayload = payload;
                        final List<UUID> finalRecipients = recipientIds;
                        org.fabt.shared.web.TenantContext.runWithContext(tenantId, false, () ->
                                notificationPersistenceService.sendToAll(finalRecipients,
                                        notifType, severity, notifPayload));
                        created++;
                    }
                }
            }
        }

        return created;
    }

    private EscalationPolicy getPolicy(ReferralToken token,
                                        Map<UUID, EscalationPolicy> policyByIdCache,
                                        Map<UUID, EscalationPolicy> defaultPolicyByTenantCache) {
        UUID policyId = token.getEscalationPolicyId();
        if (policyId != null) {
            return policyByIdCache.computeIfAbsent(policyId, id -> escalationPolicyService.findById(id)
                    .orElseGet(() -> getDefaultPolicy(token.getTenantId(), defaultPolicyByTenantCache)));
        }
        return getDefaultPolicy(token.getTenantId(), defaultPolicyByTenantCache);
    }

    private EscalationPolicy getDefaultPolicy(UUID tenantId,
                                               Map<UUID, EscalationPolicy> defaultPolicyByTenantCache) {
        // tenantId may be null when a referral has no escalation_policy_id and
        // also no tenant context (legacy rows). EscalationPolicyService.
        // getCurrentForTenant handles the platform-default fallback.
        UUID cacheKey = tenantId != null ? tenantId : PLATFORM_DEFAULT_KEY;
        return defaultPolicyByTenantCache.computeIfAbsent(cacheKey, k ->
                escalationPolicyService.getCurrentForTenant(tenantId, "dv-referral")
                        .orElseThrow(() -> new IllegalStateException("No default escalation policy found")));
    }

    private List<UUID> resolveRecipients(UUID tenantId, String eventType, List<String> roles,
                                          ReferralToken token, Map<String, List<UUID>> localCache) {
        // Behavior parity with the pre-refactor hardcoded job: for DV referral
        // events, the COORDINATOR role resolves to **DV-flagged** coordinators
        // only (UserService.findDvCoordinators), not all coordinators in the
        // tenant. The legacy job called findDvCoordinators() directly; the new
        // policy-driven job has to special-case the DV event types here, or
        // non-DV coordinators get DV escalation notifications they should
        // never see (Marcus Webb / Casey Drummond regression).
        boolean dvEvent = "dv-referral".equals(eventType);

        List<UUID> recipientIds = new ArrayList<>();
        for (String role : roles) {
            if ("OUTREACH_WORKER".equals(role)) {
                // The "outreach worker" recipient for a DV referral is the
                // referring user, NOT every outreach worker in the tenant.
                // This matches the legacy job's behavior: notifications follow
                // the referral, not the role list.
                recipientIds.add(token.getReferringUserId());
            } else if ("COORDINATOR".equals(role) && dvEvent) {
                // DV-only coordinator filter — see comment above.
                String cacheKey = tenantId + ":COORDINATOR_DV";
                recipientIds.addAll(localCache.computeIfAbsent(cacheKey, key ->
                        userService.findDvCoordinatorIds(tenantId)));
            } else {
                // Sam Okafor: Cache by role+tenant to avoid chatty DB calls for admin/coordinator lists.
                String cacheKey = tenantId + ":" + role;
                recipientIds.addAll(localCache.computeIfAbsent(cacheKey, key ->
                        userService.findActiveUserIdsByRole(tenantId, role)));
            }
        }
        return recipientIds.stream().distinct().toList();
    }

    private boolean isNew(String type, String referralId) {
        return !notificationRepository.existsByTypeAndReferralId(type, referralId);
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.error("Failed to serialize escalation payload: {}", e.getMessage());
            return "{}";
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerWithScheduler() {
        batchJobScheduler.registerJob("referralEscalation", referralEscalationJob(),
                "0 */5 * * * *", true);
    }
}
