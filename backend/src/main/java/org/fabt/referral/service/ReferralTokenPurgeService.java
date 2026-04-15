package org.fabt.referral.service;

import java.time.Duration;
import java.time.Instant;

import org.fabt.referral.repository.ReferralTokenRepository;
import org.fabt.shared.web.TenantContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.fabt.shared.security.TenantUnscoped;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hard-deletes terminal DV referral tokens (ACCEPTED, REJECTED, EXPIRED) older than 24 hours.
 * No audit trail of individual referrals survives — VAWA compliance.
 * Aggregate Micrometer counters are incremented BEFORE purge (in ReferralTokenService).
 *
 * Runs as a @Scheduled system process — binds TenantContext with dvAccess=true
 * via runWithContext so RLS allows access to DV-shelter-linked referral tokens (D14).
 */
@Service
public class ReferralTokenPurgeService {

    private static final Logger log = LoggerFactory.getLogger(ReferralTokenPurgeService.class);
    private static final Duration PURGE_AGE = Duration.ofHours(24);

    private final ReferralTokenRepository repository;

    public ReferralTokenPurgeService(ReferralTokenRepository repository) {
        this.repository = repository;
    }

    /**
     * Runs every hour. Hard-deletes terminal tokens older than 24 hours.
     *
     * <p><b>No @Transactional.</b> Single atomic DELETE statement. See ReferralTokenService.expireTokens()
     * Javadoc for why @Transactional + runWithContext inside is incompatible with RLS.</p>
     */
    @TenantUnscoped("hourly retention purge — platform-wide by VAWA retention design")
    @Scheduled(fixedRate = 3_600_000)
    public void purgeTerminalTokens() {
        // System process needs dvAccess to see DV-shelter-linked tokens via RLS
        TenantContext.runWithContext(TenantContext.getTenantId(), true, () -> {
            if (!TenantContext.getDvAccess()) {
                throw new IllegalStateException(
                        "purgeTerminalTokens requires dvAccess=true — DV referral tokens are invisible without it");
            }
            Instant cutoff = Instant.now().minus(PURGE_AGE);
            int purged = repository.purgeTerminalTokens(cutoff);
            if (purged > 0) {
                log.info("purgeTerminalTokens: dvAccess={}, purged={}", TenantContext.getDvAccess(), purged);
            } else {
                log.debug("purgeTerminalTokens: dvAccess={}, purged=0", TenantContext.getDvAccess());
            }
        });
    }
}
