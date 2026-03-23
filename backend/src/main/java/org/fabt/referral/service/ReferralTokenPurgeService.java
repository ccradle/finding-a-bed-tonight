package org.fabt.referral.service;

import java.time.Duration;
import java.time.Instant;

import org.fabt.referral.repository.ReferralTokenRepository;
import org.fabt.shared.web.TenantContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hard-deletes terminal DV referral tokens (ACCEPTED, REJECTED, EXPIRED) older than 24 hours.
 * No audit trail of individual referrals survives — VAWA compliance.
 * Aggregate Micrometer counters are incremented BEFORE purge (in ReferralTokenService).
 *
 * Runs as a @Scheduled system process — sets TenantContext.dvAccess=true so RLS
 * allows access to DV-shelter-linked referral tokens (D14).
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
     */
    @Scheduled(fixedRate = 3_600_000)
    @Transactional
    public void purgeTerminalTokens() {
        try {
            // System process needs dvAccess to see DV-shelter-linked tokens via RLS
            TenantContext.setDvAccess(true);
            Instant cutoff = Instant.now().minus(PURGE_AGE);
            int purged = repository.purgeTerminalTokens(cutoff);
            if (purged > 0) {
                log.info("Purged {} DV referral tokens (terminal state older than 24h)", purged);
            }
        } finally {
            TenantContext.clear();
        }
    }
}
