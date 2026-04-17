package org.fabt.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.fabt.shared.security.TenantUnscopedQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily prune of expired entries from {@code jwt_revocations} (V61, Phase A
 * task 2.4). The table tracks revoked JWT kids — a row per kid that was
 * invalidated before its natural expiry (operator rotation, tenant suspend).
 * Once {@code expires_at} passes, the kid's underlying JWT is already expired
 * by exp-claim, so the revocation row is no longer needed for the
 * fast-path validate check.
 *
 * <p>Without pruning the table grows linearly with rotation cadence. At a
 * 90-day rotation baseline + 7-day JWT lifetime, the table size stabilizes
 * around (number of issued kids per generation) rows; pruning cuts that
 * back to (kids issued in the trailing 7-day window).
 */
@Component
public class JwtRevocationPruneScheduler {

    private static final Logger log = LoggerFactory.getLogger(JwtRevocationPruneScheduler.class);

    private final JdbcTemplate jdbcTemplate;

    public JwtRevocationPruneScheduler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @TenantUnscopedQuery("daily JWT revocation purge — runs across all tenants by design (revocations are platform-scoped)")
    @Scheduled(fixedRate = 86_400_000L) // every 24h
    public void pruneExpiredRevocations() {
        int pruned = jdbcTemplate.update(
                "DELETE FROM jwt_revocations WHERE expires_at < NOW()");
        if (pruned > 0) {
            log.info("Pruned {} expired JWT revocation entries", pruned);
        }
    }
}
