package org.fabt.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.fabt.shared.security.TenantUnscopedQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled cleanup of expired auth tokens (access codes + password reset tokens).
 * Runs hourly to prevent table bloat from accumulated expired tokens.
 */
@Component
public class AccessCodeCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccessCodeCleanupScheduler.class);

    private final JdbcTemplate jdbcTemplate;

    public AccessCodeCleanupScheduler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @TenantUnscopedQuery("hourly retention purge — runs across all tenants by design")
    @Scheduled(fixedRate = 3600_000) // Every hour
    public void purgeExpiredTokens() {
        int accessCodes = jdbcTemplate.update(
                "DELETE FROM one_time_access_code WHERE expires_at < NOW() OR used = true");
        int resetTokens = jdbcTemplate.update(
                "DELETE FROM password_reset_token WHERE expires_at < NOW() OR used = true");
        if (accessCodes + resetTokens > 0) {
            log.info("Purged {} expired/used access codes, {} password reset tokens",
                    accessCodes, resetTokens);
        }
    }
}
