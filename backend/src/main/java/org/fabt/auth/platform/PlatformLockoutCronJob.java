package org.fabt.auth.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.fabt.auth.platform.repository.PlatformUserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically clears expired auto-lockouts on {@code platform_user} rows
 * whose {@code locked_out_at} has aged past the lockout window.
 *
 * <p>Without this cron, an operator who triggers the 5-fail/15-min lockout
 * stays locked forever — bug, not feature (warroom C2). Runs every 60s,
 * which is fine-grained enough that a recovering operator's wait is bounded
 * by ~16 min worst case (15 min lockout window + up to 60s for the next
 * cron tick).
 *
 * <p>Skipped under the {@code test} profile to keep
 * {@code SpringBootTest} integration runs deterministic (cron interference
 * with explicit lockout assertions in {@code PlatformAuthIntegrationTest}
 * would produce flakes). Tests that need to exercise the unlock path call
 * {@link PlatformUserRepository#unlockExpired(int)} directly.
 *
 * <p>Calls {@code platform_user_unlock_expired(15)} which UPDATEs
 * matching rows in one statement (partial-indexed scan). At platform-user
 * scale (1-100 rows ever) this is sub-millisecond; the hot-path concern
 * here is correctness of the predicate, not throughput.
 */
@Component
@ConditionalOnProperty(name = "fabt.platform.lockout-cron.enabled", havingValue = "true",
        matchIfMissing = true)
public class PlatformLockoutCronJob {

    private static final Logger log = LoggerFactory.getLogger(PlatformLockoutCronJob.class);

    /** Mirrors {@link PlatformAuthService#LOCKOUT_WINDOW_MIN}. */
    private static final int LOCKOUT_WINDOW_MIN = 15;

    private final PlatformUserRepository userRepository;

    public PlatformLockoutCronJob(PlatformUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Scheduled(fixedRate = 60_000)
    public void unlockExpired() {
        int unlocked = userRepository.unlockExpired(LOCKOUT_WINDOW_MIN);
        if (unlocked > 0) {
            log.info("Platform lockout cron: unlocked {} expired row(s)", unlocked);
        }
    }
}
