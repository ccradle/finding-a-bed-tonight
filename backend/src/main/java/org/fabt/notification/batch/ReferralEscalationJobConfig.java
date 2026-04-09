package org.fabt.notification.batch;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import org.fabt.auth.domain.User;
import org.fabt.auth.service.UserService;
import org.fabt.notification.repository.NotificationRepository;
import org.fabt.notification.service.NotificationPersistenceService;
import org.fabt.referral.domain.ReferralToken;
import org.fabt.referral.service.ReferralTokenService;
import org.fabt.shared.web.TenantContext;
import org.fabt.analytics.config.BatchJobScheduler;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * <p>Scans PENDING referrals past escalation thresholds:</p>
 * <ul>
 *   <li>T+1h:   ACTION_REQUIRED to DV coordinators</li>
 *   <li>T+2h:   CRITICAL to CoC admins (escalation)</li>
 *   <li>T+3.5h: CRITICAL to coordinator + outreach worker (30-min expiry warning)</li>
 *   <li>T+4h:   ACTION_REQUIRED to outreach worker (expired, find another bed)</li>
 * </ul>
 *
 * <p>Each threshold fires only once per referral (T-27). Dedup via notification
 * table: check if notification with matching type + referralId already exists.</p>
 *
 * <p><b>Payload discipline (Casey/VAWA):</b> payloads contain ONLY referralId +
 * threshold label. NEVER household size, population type, callback number, or
 * any client-identifying data.</p>
 */
@Configuration
public class ReferralEscalationJobConfig {

    private static final Logger log = LoggerFactory.getLogger(ReferralEscalationJobConfig.class);

    static final Duration THRESHOLD_1H = Duration.ofHours(1);
    static final Duration THRESHOLD_2H = Duration.ofHours(2);
    static final Duration THRESHOLD_3_5H = Duration.ofMinutes(210);
    static final Duration THRESHOLD_4H = Duration.ofHours(4);

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final ReferralTokenService referralTokenService;
    private final NotificationPersistenceService notificationPersistenceService;
    private final NotificationRepository notificationRepository;
    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final BatchJobScheduler batchJobScheduler;
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public ReferralEscalationJobConfig(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ReferralTokenService referralTokenService,
            NotificationPersistenceService notificationPersistenceService,
            NotificationRepository notificationRepository,
            UserService userService,
            ObjectMapper objectMapper,
            BatchJobScheduler batchJobScheduler,
            JdbcTemplate jdbcTemplate,
            DataSource dataSource) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.referralTokenService = referralTokenService;
        this.notificationPersistenceService = notificationPersistenceService;
        this.notificationRepository = notificationRepository;
        this.userService = userService;
        this.objectMapper = objectMapper;
        this.batchJobScheduler = batchJobScheduler;
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
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
            // TenantContext with dvAccess=true is already bound by BatchJobScheduler.runJob()
            // (registered with dvAccess=true). All connections acquired by Spring's
            // TransactionManager have app.dv_access='true' set on them.
            //
            // Defense-in-depth: fail fast if context is missing (D14 pattern).
            // Without dvAccess, referral_token RLS hides DV referrals — escalations
            // would silently never fire, which is the exact safety gap this feature prevents.
            if (!TenantContext.getDvAccess()) {
                throw new IllegalStateException(
                        "Escalation tasklet requires dvAccess=true — "
                        + "register job with BatchJobScheduler.registerJob(name, job, cron, true)");
            }

            List<ReferralToken> pending = referralTokenService.findAllPending();
            int escalationsCreated = 0;

            for (ReferralToken token : pending) {
                Duration age = Duration.between(token.getCreatedAt(), Instant.now());
                if (age.compareTo(THRESHOLD_1H) < 0) continue;

                escalationsCreated += checkAndEscalate(token, age);
            }

            if (escalationsCreated > 0) {
                log.info("Referral escalation: created {} notifications across {} eligible referrals",
                        escalationsCreated, pending.stream()
                                .filter(t -> Duration.between(t.getCreatedAt(), Instant.now()).compareTo(THRESHOLD_1H) >= 0)
                                .count());
            }
            if (contribution != null) {
                contribution.incrementWriteCount(escalationsCreated);
            }
            return RepeatStatus.FINISHED;
        };
    }

    private int checkAndEscalate(ReferralToken token, Duration age) {
        String referralId = token.getId().toString();
        UUID tenantId = token.getTenantId();
        int created = 0;

        // T+4h: ACTION_REQUIRED to outreach worker — referral expired, find another bed
        if (age.compareTo(THRESHOLD_4H) >= 0 && isNew("escalation.4h", referralId)) {
            String payload = toJson(Map.of("referralId", referralId, "threshold", "4h"));
            notificationPersistenceService.send(tenantId, token.getReferringUserId(),
                    "escalation.4h", "ACTION_REQUIRED", payload);
            created++;
        }

        // T+3.5h: CRITICAL to coordinator + outreach worker — expires in 30 minutes
        if (age.compareTo(THRESHOLD_3_5H) >= 0 && isNew("escalation.3_5h", referralId)) {
            String payload = toJson(Map.of("referralId", referralId, "threshold", "3_5h"));
            List<UUID> coordIds = userService.findDvCoordinators(tenantId)
                    .stream().map(User::getId).toList();
            List<UUID> allRecipients = new ArrayList<>(coordIds);
            allRecipients.add(token.getReferringUserId());
            if (!allRecipients.isEmpty()) {
                notificationPersistenceService.sendToAll(tenantId, allRecipients,
                        "escalation.3_5h", "CRITICAL", payload);
            }
            created++;
        }

        // T+2h: CRITICAL to CoC admin — escalation
        if (age.compareTo(THRESHOLD_2H) >= 0 && isNew("escalation.2h", referralId)) {
            String payload = toJson(Map.of("referralId", referralId, "threshold", "2h"));
            List<UUID> adminIds = userService.findActiveByRole(tenantId, "COC_ADMIN")
                    .stream().map(User::getId).toList();
            if (!adminIds.isEmpty()) {
                notificationPersistenceService.sendToAll(tenantId, adminIds,
                        "escalation.2h", "CRITICAL", payload);
            }
            created++;
        }

        // T+1h: ACTION_REQUIRED to DV coordinators — referral waiting
        if (age.compareTo(THRESHOLD_1H) >= 0 && isNew("escalation.1h", referralId)) {
            String payload = toJson(Map.of("referralId", referralId, "threshold", "1h"));
            List<UUID> coordIds = userService.findDvCoordinators(tenantId)
                    .stream().map(User::getId).toList();
            if (!coordIds.isEmpty()) {
                notificationPersistenceService.sendToAll(tenantId, coordIds,
                        "escalation.1h", "ACTION_REQUIRED", payload);
            }
            created++;
        }

        return created;
    }

    /**
     * Dedup: check if this escalation threshold has already been notified for this referral (T-27).
     *
     * <p>Uses a RAW connection from the DataSource (not DataSourceUtils) with RESET ROLE
     * to bypass SELECT RLS policy. The raw connection guarantees RESET ROLE + SELECT happen
     * on the same connection, regardless of Spring transaction binding.</p>
     *
     * <p>LESSON LEARNED: DataSourceUtils.getConnection() may not return the step's
     * transaction-bound connection in production (BatchJobScheduler thread vs Spring Batch
     * step thread). This caused the dedup to silently fail — 46+ duplicate escalation.1h
     * notifications per referral in production. See Lesson #83 in CLAUDE-CODE-BRIEF.md.</p>
     */
    private boolean isNew(String type, String referralId) {
        // Raw connection from pool — RESET ROLE + SELECT on the SAME connection.
        try (java.sql.Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("RESET ROLE");
            String currentRole = conn.createStatement().executeQuery("SELECT current_user")
                    .next() ? conn.createStatement().executeQuery("SELECT current_user")
                    .getString(1) : "unknown";
            try (var ps = conn.prepareStatement(
                    "SELECT COUNT(*) > 0 FROM notification WHERE type = ? AND payload ->> 'referralId' = ?")) {
                ps.setString(1, type);
                ps.setString(2, referralId);
                var rs = ps.executeQuery();
                rs.next();
                boolean exists = rs.getBoolean(1);
                log.info("isNew dedup: type={}, referralId={}, role={}, exists={}, result={}",
                        type, referralId.substring(0, 8), currentRole, exists, !exists);
                return !exists;
            }
        } catch (java.sql.SQLException e) {
            log.error("Dedup check failed for type={}, referralId={}: {}", type, referralId, e.getMessage());
            return true; // Fail open — create the notification rather than silently skip
        }
        // Connection auto-closed by try-with-resources. RlsDataSourceConfig re-applies
        // SET ROLE fabt_app on next checkout from the pool.
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
        // dvAccess=true: referral_token RLS inherits from shelter RLS (dv_shelter_access).
        // BatchJobScheduler wraps jobLauncher.run() in TenantContext BEFORE Spring Batch
        // starts transactions, so all connections have app.dv_access='true'.
        batchJobScheduler.registerJob("referralEscalation", referralEscalationJob(),
                "0 */5 * * * *", true);
    }
}
