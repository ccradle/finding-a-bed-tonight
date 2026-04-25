package org.fabt.auth.platform;

import java.time.Instant;
import java.util.UUID;

/**
 * Platform operator identity (Phase G-4 / issue #141).
 *
 * <p>Distinct from {@link org.fabt.auth.domain.User} — no {@code tenantId}
 * field, separate authentication surface. Backed by the {@code platform_user}
 * table created in V87, accessed exclusively via SECURITY DEFINER functions
 * because {@code fabt_app} has REVOKE ALL on the underlying table.
 *
 * <p>Plain POJO (no Spring Data JDBC {@code @Table} annotation) — the
 * SECURITY DEFINER access pattern means we never SELECT against the table
 * directly; {@link PlatformUserRepository} maps function results to this
 * type explicitly.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>The bootstrap row created in V87 has all credential fields NULL and
 * {@code accountLocked = true}. Operator activation (post-deploy) sets
 * {@code email}, {@code passwordHash}, and flips {@code accountLocked = false}.
 * First successful password authentication returns an MFA-setup-only token
 * (10 min expiry); operator scans QR + 10 backup codes; confirms TOTP code;
 * {@code mfaEnabled} flips to true. Subsequent logins require TOTP/backup-code
 * verification before issuing a full platform JWT.
 *
 * <h2>Anonymization (GDPR Art-17)</h2>
 *
 * <p>{@code anonymizedAt} non-null marks a row as logically deleted. Rows
 * are kept (audit FK constraints) but excluded from authentication lookups
 * by the SECURITY DEFINER functions' WHERE clauses. Tooling Phase H+.
 */
public class PlatformUser {

    private UUID id;
    private String email;
    private String passwordHash;
    private String mfaSecret;
    private boolean mfaEnabled;
    private boolean accountLocked;
    private Instant createdAt;
    private Instant lastLoginAt;
    private Instant anonymizedAt;

    public PlatformUser() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getMfaSecret() {
        return mfaSecret;
    }

    public void setMfaSecret(String mfaSecret) {
        this.mfaSecret = mfaSecret;
    }

    public boolean isMfaEnabled() {
        return mfaEnabled;
    }

    public void setMfaEnabled(boolean mfaEnabled) {
        this.mfaEnabled = mfaEnabled;
    }

    public boolean isAccountLocked() {
        return accountLocked;
    }

    public void setAccountLocked(boolean accountLocked) {
        this.accountLocked = accountLocked;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public Instant getAnonymizedAt() {
        return anonymizedAt;
    }

    public void setAnonymizedAt(Instant anonymizedAt) {
        this.anonymizedAt = anonymizedAt;
    }

    /**
     * Whether this row is in a state that permits login. Both flags must be
     * favorable: account unlocked AND password is set AND not anonymized.
     * MFA-enabled vs not is handled separately at the auth flow level
     * (mfaEnabled=false triggers forced-MFA-setup flow on next login).
     */
    public boolean isLoginAllowed() {
        return !accountLocked
                && passwordHash != null
                && !passwordHash.isBlank()
                && anonymizedAt == null;
    }
}
