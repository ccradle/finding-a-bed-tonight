package org.fabt.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled cleanup of expired one-time access codes.
 * Runs hourly to prevent table bloat from accumulated expired tokens.
 * Similar to DV referral token purge pattern.
 */
@Component
public class AccessCodeCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccessCodeCleanupScheduler.class);

    private final JdbcTemplate jdbcTemplate;

    public AccessCodeCleanupScheduler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(fixedRate = 3600_000) // Every hour
    public void purgeExpiredCodes() {
        int deleted = jdbcTemplate.update(
                "DELETE FROM one_time_access_code WHERE expires_at < NOW() OR used = true");
        if (deleted > 0) {
            log.info("Purged {} expired/used one-time access codes", deleted);
        }
    }
}
