package org.fabt.auth.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package-private Spring bean that owns every regulated-table write against
 * {@code password_reset_token}. Isolated from {@link PasswordResetService}
 * so Spring's {@code @Transactional} proxy engages: self-invocation from
 * the outer service into a same-class {@code @Transactional} method would
 * bypass the proxy (same Bug A+D root cause that prompted extracting
 * {@code AuditEventPersister} during Phase B audit-path debugging).
 *
 * <h2>Phase B tenant-GUC binding contract</h2>
 * Every method here is {@code @Transactional} AND binds
 * {@code app.tenant_id} via {@code set_config(..., is_local=true)} before
 * any regulated-table write. The V68 {@code prt_insert_restrictive} /
 * {@code prt_update_restrictive} / {@code prt_delete_restrictive}
 * policies check {@code tenant_id = fabt_current_tenant_id()} on every
 * write; {@code requestReset} and {@code resetPassword} run on the
 * pre-auth path where {@link org.fabt.shared.web.TenantContext} is not
 * yet bound, so the GUC is set explicitly here before the first write
 * statement.
 *
 * <p>{@code is_local=true} binds only for the current transaction —
 * matches the scope of the {@code @Transactional} method and avoids
 * leaking the binding back into the HikariCP connection pool. The
 * transaction boundary reverts the GUC at commit/rollback; the next
 * borrow of this connection will be re-bound by
 * {@code RlsDataSourceConfig.applyRlsContext}.
 */
@Service
class PasswordResetTokenPersister {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetTokenPersister.class);

    private final JdbcTemplate jdbcTemplate;

    PasswordResetTokenPersister(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Invalidate any unused tokens for {@code userId} then insert a new
     * token row. Both writes run under the same tenant GUC binding and the
     * same tx — if the INSERT fails, the UPDATE rolls back (prior tokens
     * remain valid), which is the correct semantic for a failed reset
     * request.
     */
    @Transactional
    public void writeToken(UUID userId, UUID tenantId, String tokenHash, Instant expiresAt) {
        bindTenantGuc(tenantId);

        // All WHERE clauses include tenant_id as defense-in-depth per D15
        // (TenantPredicateCoverageTest) — even though FORCE RLS also filters,
        // the explicit predicate documents the per-tenant intent at the SQL
        // layer and prevents a degraded-RLS deployment from silently matching
        // cross-tenant rows.
        jdbcTemplate.update(
                "UPDATE password_reset_token SET used = true "
                        + "WHERE user_id = ? AND tenant_id = ? AND used = false",
                userId, tenantId);

        jdbcTemplate.update(
                "INSERT INTO password_reset_token (user_id, tenant_id, token_hash, expires_at) "
                        + "VALUES (?, ?, ?, ?)",
                userId, tenantId, tokenHash, Timestamp.from(expiresAt));
    }

    /**
     * Delete a token by hash. Used only by the email-delivery-failure
     * recovery path in {@link PasswordResetService#requestReset} to avoid
     * orphaned tokens whose plaintext form never reached the user.
     */
    @Transactional
    public void deleteToken(UUID tenantId, String tokenHash) {
        bindTenantGuc(tenantId);
        // tenant_id predicate in addition to RLS — defense-in-depth per D15.
        int deleted = jdbcTemplate.update(
                "DELETE FROM password_reset_token WHERE token_hash = ? AND tenant_id = ?",
                tokenHash, tenantId);
        if (deleted == 0) {
            log.warn("deleteToken: no rows deleted for tokenHash hint — race with another path?");
        }
    }

    /**
     * Mark a single token row used. Runs inside the caller's {@code resetPassword}
     * transaction — the caller has already resolved user → tenant via the token
     * lookup and passed the tenantId here so we can bind the GUC before the
     * RESTRICTIVE-WRITE policy blocks the UPDATE.
     */
    @Transactional
    public void markTokenUsed(UUID tenantId, UUID tokenId) {
        bindTenantGuc(tenantId);
        // tenant_id predicate in addition to RLS — defense-in-depth per D15.
        jdbcTemplate.update(
                "UPDATE password_reset_token SET used = true WHERE id = ? AND tenant_id = ?",
                tokenId, tenantId);
    }

    private void bindTenantGuc(UUID tenantId) {
        // SELECT set_config returns the set value; we discard it. UUIDs are
        // strictly hex + hyphens so the toString() cannot carry SQL-injection
        // payloads, but we still parameterize for consistency with the rest
        // of the codebase.
        jdbcTemplate.queryForObject(
                "SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantId.toString());
    }
}
