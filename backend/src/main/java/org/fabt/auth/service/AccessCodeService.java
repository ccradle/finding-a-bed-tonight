package org.fabt.auth.service;

import java.time.Instant;
import java.util.UUID;

import org.fabt.auth.domain.User;
import org.fabt.auth.repository.UserRepository;
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.tenant.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time access code service for password recovery.
 *
 * Admin generates a code for a locked-out worker. Code is stored bcrypt-hashed
 * with 15-minute expiry. Worker enters code on login screen, then must set
 * a new password before accessing any other functionality.
 */
@Service
public class AccessCodeService {

    private static final Logger log = LoggerFactory.getLogger(AccessCodeService.class);
    private static final int CODE_EXPIRY_MINUTES = 15;

    private final JdbcTemplate jdbcTemplate;
    private final PasswordService passwordService;
    private final UserRepository userRepository;
    private final UserService userService;
    private final TenantService tenantService;
    private final ApplicationEventPublisher eventPublisher;

    public AccessCodeService(JdbcTemplate jdbcTemplate, PasswordService passwordService,
                             UserRepository userRepository, UserService userService,
                             TenantService tenantService,
                             ApplicationEventPublisher eventPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordService = passwordService;
        this.userRepository = userRepository;
        this.userService = userService;
        this.tenantService = tenantService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Generate a one-time access code for a user.
     * Returns the plaintext code (displayed once to admin).
     * Code is stored bcrypt-hashed with 15-minute expiry.
     */
    @Transactional
    public String generateCode(UUID targetUserId, UUID adminUserId, UUID tenantId) {
        // Task 2.5.1 defense-in-depth: even though the controller already
        // validates via userService.getUser, the service re-validates
        // through the tenant-scoped lookup. Ensures AccessCodeService
        // cannot be called from a future non-controller caller with an
        // attacker-influenced targetUserId.
        User target = userService.getUser(targetUserId);

        if (!target.isActive()) {
            throw new IllegalStateException("Cannot generate access code for deactivated user");
        }

        // Generate a readable code (UUID without dashes, first 12 chars)
        String plainCode = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String codeHash = passwordService.hash(plainCode);
        Instant expiresAt = Instant.now().plusSeconds(CODE_EXPIRY_MINUTES * 60L);

        jdbcTemplate.update(
                "INSERT INTO one_time_access_code (user_id, tenant_id, code_hash, expires_at, created_by) VALUES (?, ?, ?, ?, ?)",
                targetUserId, tenantId, codeHash, java.sql.Timestamp.from(expiresAt), adminUserId
        );

        log.warn("Access code generated for user {} by admin {} (expires {})", targetUserId, adminUserId, expiresAt);
        eventPublisher.publishEvent(new AuditEventRecord(adminUserId, targetUserId, "ACCESS_CODE_GENERATED", null, null));

        return plainCode;
    }

    /**
     * Validate an access code for a user identified by email + tenant.
     * Returns the user if code is valid, null otherwise.
     * Marks the code as used on success.
     * Audit event published by caller AFTER transaction commits.
     */
    @Transactional
    public User validateCode(String email, String tenantSlug, String code) {
        var tenant = tenantService.findBySlug(tenantSlug).orElse(null);
        if (tenant == null) return null;

        User user = userRepository.findByTenantIdAndEmail(tenant.getId(), email).orElse(null);
        if (user == null || !user.isActive()) return null;

        // Find valid (unused, non-expired) codes for this user
        var codes = jdbcTemplate.queryForList(
                "SELECT id, code_hash FROM one_time_access_code WHERE user_id = ? AND used = false AND expires_at > NOW()",
                user.getId()
        );

        for (var row : codes) {
            String storedHash = (String) row.get("code_hash");
            if (passwordService.matches(code, storedHash)) {
                // Mark as used — atomic within this transaction
                UUID codeId = (UUID) row.get("id");
                jdbcTemplate.update("UPDATE one_time_access_code SET used = true WHERE id = ?", codeId);

                log.info("Access code validated for user {} (code {})", user.getId(), codeId);
                return user;
            }
        }

        return null;
    }
}
