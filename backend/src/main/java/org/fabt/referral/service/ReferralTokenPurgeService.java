package org.fabt.referral.service;

import java.time.Duration;
import java.time.Instant;

import org.fabt.referral.repository.ReferralTokenRepository;
import org.fabt.reservation.service.ReservationService;
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
    private final ReservationService reservationService;

    public ReferralTokenPurgeService(ReferralTokenRepository repository,
                                      ReservationService reservationService) {
        this.repository = repository;
        this.reservationService = reservationService;
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

    /**
     * 15-minute purge of resolved-and-aged hold-attribution PII (transitional-
     * reentry-support task 4.6, slice 2C; v0.55 §13.C.1 cadence-tightening).
     * Nulls the V93 {@code held_for_client_*_encrypted} columns 24h after a
     * reservation resolves (CANCELLED / CONFIRMED / EXPIRED /
     * CANCELLED_SHELTER_DEACTIVATED) OR 24h past {@code expires_at}. Two-
     * layer PII posture per design D4: tenant_dek crypto-shred is the
     * at-rest defense; this purge is the 24h-post-resolution defense.
     *
     * <p><b>v0.55 §13.C.1 cadence change:</b> bumped from {@code @Scheduled(fixedRate=3_600_000)}
     * (1 hour) to {@code @Scheduled(fixedDelay=900_000)} (15 minutes). Two
     * decisions in one annotation flip:
     * <ol>
     *   <li><b>15 min instead of 60 min:</b> the platform's privacy-posture
     *       claim is "no later than 25 hours" (design D10 + Round 4 C-RR-2).
     *       60 min cadence + 24h aging gives a worst-case 25h lifetime in
     *       the average case but 25h59m in the tail; 15 min cadence puts
     *       the worst-case at 24h15m, well under the public claim.</li>
     *   <li><b>fixedDelay instead of fixedRate:</b> per Round 4 Sam S-RR-2,
     *       {@code fixedRate} does NOT prevent overlap if a prior invocation
     *       runs longer than the period — a 50K-row backlog could keep one
     *       invocation running 10-25 seconds, and {@code fixedRate} would
     *       launch a second invocation while the first is still updating
     *       rows. Both would contend on the same {@code UPDATE ... LIMIT n}
     *       batches and lock-acquire serially, no parallelism gain plus
     *       wasted CPU on the loser. {@code fixedDelay} measures the
     *       interval from END-of-prior to START-of-next, eliminating
     *       overlap by construction.</li>
     * </ol>
     *
     * <p>Same TenantContext binding pattern as the DV referral purge above
     * (system context + dvAccess=true) — the reservation table has FORCE
     * RLS that joins through shelter, so dvAccess is required to cover
     * both DV and non-DV reservations cross-tenant. The repository's SQL
     * is no-op on rows whose ciphertext is already null, so re-runs cost
     * nothing on already-purged rows.
     *
     * <p>Null-safe on pre-V93 databases: the SQL references the new
     * {@code _encrypted} columns by name, so a pre-V93 DB will fail with
     * a column-not-found error rather than silently no-op. In practice
     * this is fine — V91-V94 ship together in slice-1 of reentry-spec,
     * so any prod DB running v0.55+ has the columns.
     */
    @TenantUnscoped("15-minute retention purge — platform-wide by hold-attribution PII retention design (v0.55 §13.C.1)")
    @Scheduled(fixedDelay = 900_000)
    public void purgeExpiredHoldAttribution() {
        TenantContext.runWithContext(TenantContext.getTenantId(), true, () -> {
            Instant cutoff = Instant.now().minus(PURGE_AGE);
            // v0.55 §13.A.4 — service-layer call now emits the
            // RESERVATION_PII_PURGED audit row internally regardless of
            // count, so this scheduler wrapper does NOT need to emit; it
            // just needs the count for the operator-facing log line.
            int purged = reservationService.purgeExpiredHoldAttribution(cutoff);
            if (purged > 0) {
                log.info("purgeExpiredHoldAttribution: purged={}", purged);
            } else {
                log.debug("purgeExpiredHoldAttribution: purged=0");
            }
        });
    }
}
