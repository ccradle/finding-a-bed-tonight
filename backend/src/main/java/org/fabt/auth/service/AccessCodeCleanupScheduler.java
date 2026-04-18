package org.fabt.auth.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.fabt.shared.security.TenantUnscopedQuery;
import org.fabt.shared.web.TenantContext;
import org.fabt.tenant.service.TenantService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled cleanup of expired auth tokens (access codes + password reset tokens).
 * Runs hourly to prevent table bloat from accumulated expired tokens.
 *
 * <p>Phase B task 3.27 note: {@code one_time_access_code} and
 * {@code password_reset_token} both have FORCE RLS with RESTRICTIVE DELETE
 * policies gated on {@code tenant_id = fabt_current_tenant_id()}. A single
 * tenant-unbound DELETE would silently match zero rows (policy NULL-evaluates).
 * Iterate tenants + bind each before its DELETE. At pilot scale (1-10 tenants)
 * this is N trivial DELETEs; at 100+ tenants consider a dedicated retention
 * partition strategy.
 */
@Component
public class AccessCodeCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(AccessCodeCleanupScheduler.class);

    private final JdbcTemplate jdbcTemplate;
    private final TenantService tenantService;

    public AccessCodeCleanupScheduler(JdbcTemplate jdbcTemplate, TenantService tenantService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantService = tenantService;
    }

    @TenantUnscopedQuery("hourly retention purge — enumerates tenants + binds each for RLS-compat DELETE")
    @Scheduled(fixedRate = 3600_000) // Every hour
    public void purgeExpiredTokens() {
        int totalAccessCodes = 0;
        int totalResetTokens = 0;

        for (var tenant : tenantService.findAll()) {
            UUID tenantId = tenant.getId();
            int[] counts = purgeForTenant(tenantId);
            totalAccessCodes += counts[0];
            totalResetTokens += counts[1];
        }

        if (totalAccessCodes + totalResetTokens > 0) {
            log.info("Purged {} expired/used access codes, {} password reset tokens (across all tenants)",
                    totalAccessCodes, totalResetTokens);
        }
    }

    /**
     * Runs the DELETE under a bound tenant context so Phase B's
     * RESTRICTIVE DELETE policies on {@code one_time_access_code} and
     * {@code password_reset_token} match the rows for this tenant.
     * Returns {@code [accessCodesDeleted, resetTokensDeleted]}.
     */
    private int[] purgeForTenant(UUID tenantId) {
        return TenantContext.callWithContext(tenantId, false, () -> {
            int accessCodes = jdbcTemplate.update(
                    "DELETE FROM one_time_access_code "
                    + "WHERE tenant_id = ? AND (expires_at < NOW() OR used = true)",
                    tenantId);
            int resetTokens = jdbcTemplate.update(
                    "DELETE FROM password_reset_token "
                    + "WHERE tenant_id = ? AND (expires_at < NOW() OR used = true)",
                    tenantId);
            return new int[]{accessCodes, resetTokens};
        });
    }
}
