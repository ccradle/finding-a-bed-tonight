package org.fabt.auth.platform.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.fabt.auth.platform.PlatformUser;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Spring Data-style repository for {@link PlatformUser}, but implemented as
 * a regular {@code @Repository} bean (NOT a {@code CrudRepository}) because
 * the V87 migration REVOKEs direct table access from {@code fabt_app}. All
 * reads/writes go through SECURITY DEFINER functions which carry the
 * elevated privileges needed to read/write {@code platform_user}.
 *
 * <p>This pattern matches the Phase G-1 {@code tenant_audit_chain_head}
 * access path: REVOKE ALL on the table; SECURITY DEFINER function in V87
 * defines what {@code fabt_app} can do; service layer calls the function.
 *
 * <p>Backup codes are stored as {@code SHA-256(salt || code)} per design
 * Decision 12 — bcrypt's slow-compare provides no benefit for random
 * single-use codes and adds latency during recovery.
 */
@Repository
public class PlatformUserRepository {

    private static final RowMapper<PlatformUser> LOOKUP_MAPPER = (rs, rowNum) -> {
        PlatformUser pu = new PlatformUser();
        pu.setId((UUID) rs.getObject("id"));
        pu.setEmail(rs.getString("email"));
        pu.setPasswordHash(rs.getString("password_hash"));
        pu.setMfaSecret(rs.getString("mfa_secret"));
        pu.setMfaEnabled(rs.getBoolean("mfa_enabled"));
        pu.setAccountLocked(rs.getBoolean("account_locked"));
        return pu;
    };

    private final JdbcTemplate jdbc;

    public PlatformUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Look up a platform_user by email. Returns empty if not found OR if the
     * row is anonymized (the SECURITY DEFINER function filters out
     * {@code anonymized_at IS NOT NULL} rows server-side).
     */
    public Optional<PlatformUser> findByEmail(String email) {
        try {
            PlatformUser pu = jdbc.queryForObject(
                    "SELECT * FROM platform_user_lookup_by_email(?)",
                    LOOKUP_MAPPER, email);
            return Optional.ofNullable(pu);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Look up a platform_user by id. Returns empty if not found or anonymized.
     */
    public Optional<PlatformUser> findById(UUID id) {
        try {
            PlatformUser pu = jdbc.queryForObject(
                    "SELECT * FROM platform_user_lookup_by_id(?)",
                    LOOKUP_MAPPER, id);
            return Optional.ofNullable(pu);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Update credential-related fields on a platform_user row. Each parameter
     * is independently nullable — passing {@code null} for a field leaves
     * the existing value unchanged (handled by the SECURITY DEFINER function
     * via COALESCE). Returns true if a row was updated, false otherwise
     * (e.g., id not found, or row anonymized).
     */
    public boolean updateCredentials(UUID id,
                                     String passwordHash,
                                     String mfaSecret,
                                     Boolean mfaEnabled,
                                     Boolean accountLocked) {
        Boolean result = jdbc.queryForObject(
                "SELECT platform_user_update_credentials(?, ?, ?, ?, ?)",
                Boolean.class,
                id, passwordHash, mfaSecret, mfaEnabled, accountLocked);
        return Boolean.TRUE.equals(result);
    }

    /**
     * Record a successful login on the platform_user row by setting
     * {@code last_login_at = NOW()}.
     *
     * <p>Implementation note: {@code SELECT void_func(?)} returns an empty
     * result set in PG-JDBC, which {@code jdbc.update} rejects with
     * "A result was returned when none was expected." We use
     * {@code queryForObject} and discard the (always-NULL) value instead.
     */
    public void recordLogin(UUID id) {
        jdbc.queryForObject("SELECT platform_user_record_login(?)", Object.class, id);
    }

    /**
     * Return all unused (used_at IS NULL) backup codes for a platform user.
     * The function-side filter on {@code used_at IS NULL} means callers
     * never see consumed codes.
     */
    public List<BackupCodeRow> findUnusedBackupCodes(UUID userId) {
        List<BackupCodeRow> rows = new ArrayList<>();
        jdbc.query(
                "SELECT * FROM platform_user_backup_codes_for(?)",
                rs -> {
                    BackupCodeRow row = new BackupCodeRow(
                            (UUID) rs.getObject("id"),
                            rs.getString("code_hash"),
                            rs.getBytes("code_salt"));
                    rows.add(row);
                },
                userId);
        return rows;
    }

    /**
     * Mark a backup code as used. Returns true if the row existed and was
     * not already consumed; false otherwise.
     */
    public boolean markBackupCodeUsed(UUID codeId) {
        Boolean result = jdbc.queryForObject(
                "SELECT platform_user_mark_backup_code_used(?)",
                Boolean.class, codeId);
        return Boolean.TRUE.equals(result);
    }

    /**
     * Replace the user's backup-code set with the supplied 10 codes. The
     * SECURITY DEFINER function DELETEs any existing rows for this user
     * before inserting, so this is the canonical "regenerate codes"
     * primitive too.
     */
    public int replaceBackupCodes(UUID userId,
                                  UUID[] ids,
                                  String[] hashes,
                                  byte[][] salts) {
        Integer result = jdbc.queryForObject(
                "SELECT platform_user_insert_backup_codes(?, ?, ?, ?)",
                Integer.class,
                userId, ids, hashes, salts);
        return result != null ? result : 0;
    }

    /**
     * Atomic MFA enrollment in a single SECURITY DEFINER call (warroom A1).
     * Sets {@code mfa_secret} and replaces backup codes in one server-side
     * transaction so a partial failure cannot leave a TOTP secret without
     * recovery codes. Returns false if the user is already enrolled
     * ({@code mfa_enabled = true}) — the caller should treat that as a
     * stale-token-replay attempt and refuse the operation.
     */
    public boolean setupMfaAtomic(UUID userId,
                                   String secret,
                                   UUID[] ids,
                                   String[] hashes,
                                   byte[][] salts) {
        Boolean result = jdbc.queryForObject(
                "SELECT platform_user_setup_mfa(?, ?, ?, ?, ?)",
                Boolean.class,
                userId, secret, ids, hashes, salts);
        return Boolean.TRUE.equals(result);
    }

    /**
     * Records a failed login/MFA attempt and auto-locks the account if the
     * threshold is met within the rolling window. Returns true iff THIS
     * call triggered the lockout transition (so the service can emit a
     * single PLATFORM_USER_LOCKED_OUT log line under concurrent failures).
     */
    public boolean recordFailure(UUID userId, int windowMin, int threshold) {
        Boolean result = jdbc.queryForObject(
                "SELECT platform_user_record_failure(?, ?, ?)",
                Boolean.class,
                userId, windowMin, threshold);
        return Boolean.TRUE.equals(result);
    }

    /** Clears the failure window on a successful authentication. */
    public void clearFailures(UUID userId) {
        // queryForObject (not update) for void-returning SELECT — see recordLogin.
        jdbc.queryForObject("SELECT platform_user_clear_failures(?)", Object.class, userId);
    }

    /**
     * Cron-callable: unlocks every row whose {@code locked_out_at} has
     * aged past the supplied window. Returns the count of rows unlocked
     * (for ops visibility / metrics).
     */
    public int unlockExpired(int windowMin) {
        Integer result = jdbc.queryForObject(
                "SELECT platform_user_unlock_expired(?)",
                Integer.class, windowMin);
        return result != null ? result : 0;
    }

    /**
     * Records that a TOTP code was just accepted (warroom M1). The caller
     * uses {@link #wasTotpRecentlyUsed(UUID, String, int)} on the next
     * verify to reject replay within the 89-second RFC 6238 window.
     */
    public void recordTotpUse(UUID userId, String code) {
        // queryForObject (not update) for void-returning SELECT — see recordLogin.
        jdbc.queryForObject(
                "SELECT platform_user_record_totp_use(?, ?)",
                Object.class, userId, code);
    }

    /**
     * Returns true if the presented code matches the most-recently-used
     * code AND was used within {@code windowSeconds}. Replay-protection
     * gate per warroom M1.
     */
    public boolean wasTotpRecentlyUsed(UUID userId, String code, int windowSeconds) {
        Boolean result = jdbc.queryForObject(
                "SELECT platform_user_was_totp_recently_used(?, ?, ?)",
                Boolean.class,
                userId, code, windowSeconds);
        return Boolean.TRUE.equals(result);
    }

    /**
     * Backup code row as returned by {@code platform_user_backup_codes_for}.
     * Used by the auth flow to verify a presented code: hash the candidate
     * with the row's salt, compare to {@code codeHash}.
     */
    public record BackupCodeRow(UUID id, String codeHash, byte[] codeSalt) {
    }
}
