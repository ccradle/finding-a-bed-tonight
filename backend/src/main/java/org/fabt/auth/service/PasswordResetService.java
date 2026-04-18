package org.fabt.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.fabt.auth.domain.User;
import org.fabt.auth.repository.UserRepository;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.fabt.shared.security.TenantUnscopedQuery;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service password reset via email token.
 *
 * <p>Tokens are 256-bit (SecureRandom 32 bytes), stored as SHA-256 hex digest
 * for O(1) database lookup. BCrypt is NOT used — tokens are high-entropy,
 * brute force is infeasible regardless of hash speed. See design D1.</p>
 *
 * <p>DV users (dvAccess=true) are silently rejected — no email sent, no token
 * created, same response as non-existent email. See design D3, NNEDV Safety Net.</p>
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final int TOKEN_BYTES = 32; // 256 bits
    private static final int EXPIRY_MINUTES = 30;
    private static final long MIN_RESPONSE_NANOS = 250_000_000L; // 250ms floor (D8)

    private final UserRepository userRepository;
    private final TenantService tenantService;
    private final PasswordService passwordService;
    private final EmailService emailService; // null when SMTP not configured
    private final JdbcTemplate jdbcTemplate;
    private final PasswordResetTokenPersister tokenPersister;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasswordResetService(UserRepository userRepository,
                                TenantService tenantService,
                                PasswordService passwordService,
                                @org.springframework.lang.Nullable EmailService emailService,
                                JdbcTemplate jdbcTemplate,
                                PasswordResetTokenPersister tokenPersister) {
        this.userRepository = userRepository;
        this.tenantService = tenantService;
        this.passwordService = passwordService;
        this.emailService = emailService;
        this.jdbcTemplate = jdbcTemplate;
        this.tokenPersister = tokenPersister;
    }

    /**
     * Request a password reset. Generates token, stores SHA-256 hash, sends email.
     * Returns silently if email/tenant doesn't exist or user has dvAccess=true.
     * Timing is padded to 250ms floor to prevent enumeration (D8).
     *
     * <p>This method is deliberately NOT {@code @Transactional} so the
     * timing-pad {@code Thread.sleep} in the {@code finally} block does
     * not hold a pooled JDBC connection open during the 250ms pad (Marcus
     * checkpoint: Charlotte-scale connection-holding concern).</p>
     *
     * <p>Regulated-table writes on {@code password_reset_token} are routed
     * through {@link PasswordResetTokenPersister} — a separate Spring bean
     * so the {@code @Transactional} proxy engages AND the tenant-GUC
     * binding ({@code set_config('app.tenant_id', tenantId, true)})
     * happens as the first statement inside the writer's transaction.
     * V68's {@code prt_insert_restrictive} policy checks
     * {@code tenant_id = fabt_current_tenant_id()} on every INSERT; the
     * pre-auth {@link org.fabt.shared.web.TenantContext} is null at this
     * method's entry so the binding must be explicit.</p>
     */
    @TenantUnscopedQuery("password_reset_token rows are tenant-scoped via app_user FK; user looked up by (tenant_id, email) before any token operation. Write path through PasswordResetTokenPersister binds app.tenant_id via set_config(is_local=true) under @Transactional (Phase B D46 pattern).")
    public void requestReset(String email, String tenantSlug) {
        long startNanos = System.nanoTime();
        try {
            // No SMTP = no point creating tokens (they can't be delivered)
            if (emailService == null) {
                log.debug("Password reset: SMTP not configured, skipping");
                return;
            }

            Tenant tenant = tenantService.findBySlug(tenantSlug).orElse(null);
            if (tenant == null) {
                log.debug("Password reset: unknown tenant slug {}", tenantSlug);
                return;
            }

            User user = userRepository.findByTenantIdAndEmail(tenant.getId(), email).orElse(null);
            if (user == null || !user.isActive()) {
                log.debug("Password reset: unknown or inactive email {}", email);
                return;
            }

            // D3: DV users blocked — email to compromised inbox reveals platform membership
            if (user.isDvAccess()) {
                log.debug("Password reset: blocked for dvAccess user {}", user.getId());
                return;
            }

            // Generate 256-bit token
            byte[] tokenBytes = new byte[TOKEN_BYTES];
            secureRandom.nextBytes(tokenBytes);
            String plainToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
            String tokenHash = sha256Hex(plainToken);

            Instant expiresAt = Instant.now().plusSeconds(EXPIRY_MINUTES * 60L);

            // UPDATE (invalidate prior unused tokens) + INSERT (new token) in one
            // tenant-GUC-bound @Transactional unit inside the persister.
            tokenPersister.writeToken(user.getId(), tenant.getId(), tokenHash, expiresAt);

            // Send email OUTSIDE the tx so SMTP round-trip does not hold the
            // DB connection. On failure, delete the orphaned token via a
            // second transactional call (re-binds tenant GUC for the DELETE).
            try {
                emailService.sendPasswordResetEmail(email, plainToken);
                log.info("Password reset token created for user {} (expires {})", user.getId(), expiresAt);
            } catch (Exception e) {
                log.error("Failed to send reset email to {}, deleting token: {}", email, e.getMessage());
                tokenPersister.deleteToken(tenant.getId(), tokenHash);
            }
        } finally {
            // D8: Constant 250ms response floor — prevents timing-based enumeration.
            // Outside any transaction — does NOT hold a DB connection during sleep.
            padTiming(startNanos);
        }
    }

    /**
     * Validate a reset token and set new password.
     * Increments tokenVersion to invalidate all existing JWTs (D5).
     *
     * @return true if reset succeeded, false if token is invalid/expired/used
     */
    @TenantUnscopedQuery("token_hash is SHA-256 — globally unique by birthday-bound argument. Resolved row's user_id then dictates tenant. Phase B: mark-used UPDATE routes through PasswordResetTokenPersister.markTokenUsed which binds app.tenant_id from the user's tenantId before the prt_update_restrictive policy check.")
    @Transactional
    public boolean resetPassword(String token, String newPassword) {
        String tokenHash = sha256Hex(token);

        // O(1) lookup by SHA-256 hash — permissive SELECT policy (prt_select_all)
        // accepts the read without tenant binding.
        var rows = jdbcTemplate.queryForList(
                "SELECT id, user_id FROM password_reset_token WHERE token_hash = ? AND used = false AND expires_at > NOW()",
                tokenHash);

        if (rows.isEmpty()) {
            log.warn("Password reset attempted with invalid or expired token");
            return false;
        }

        UUID tokenId = (UUID) rows.get(0).get("id");
        UUID userId = (UUID) rows.get(0).get("user_id");

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return false;

        // Mark token as used (single-use) — routed through persister so the
        // prt_update_restrictive policy sees app.tenant_id bound to the
        // user's tenantId. Phase B: pre-auth callers have no TenantContext.
        tokenPersister.markTokenUsed(user.getTenantId(), tokenId);

        // Update password, increment tokenVersion (D5), set passwordChangedAt.
        // Wrapped in @Transactional at the persister layer but the user update
        // itself runs in the persister-scoped tx (see markTokenUsed). The
        // userRepository.save is on a non-regulated table, so it runs in the
        // ambient tx opened by the persister call (REQUIRED propagation).
        user.setPasswordHash(passwordService.hash(newPassword));
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setPasswordChangedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        log.info("Password reset completed for user {} via email token (tokenVersion now {})",
                userId, user.getTokenVersion());
        return true;
    }

    /**
     * SHA-256 hex digest of a string. Used for token hashing.
     * SHA-256 (not BCrypt) because tokens are 256-bit high-entropy — brute force
     * is infeasible regardless of hash speed. Enables O(1) DB lookup.
     */
    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Pad method execution to a constant floor to prevent timing-based enumeration.
     * The caller always takes >= MIN_RESPONSE_NANOS regardless of code path.
     */
    private void padTiming(long startNanos) {
        long elapsed = System.nanoTime() - startNanos;
        long remaining = MIN_RESPONSE_NANOS - elapsed;
        if (remaining > 0) {
            try {
                Thread.sleep(remaining / 1_000_000, (int) (remaining % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
