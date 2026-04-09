package org.fabt.auth.service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.fabt.auth.api.CreateUserRequest;
import org.fabt.auth.api.UpdateUserRequest;
import org.fabt.auth.domain.User;
import org.fabt.auth.repository.UserRepository;
import org.fabt.notification.service.NotificationService;
import org.fabt.shared.audit.AuditDetails;
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.web.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User lifecycle management — create, edit, deactivate, reactivate.
 * Publishes audit events for role/dvAccess/status changes.
 * Increments tokenVersion to invalidate JWTs on security-relevant changes.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final PasswordService passwordService;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;

    public UserService(UserRepository userRepository, PasswordService passwordService,
                       NotificationService notificationService,
                       ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.passwordService = passwordService;
        this.notificationService = notificationService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public User createUser(CreateUserRequest request) {
        UUID tenantId = TenantContext.getTenantId();

        User user = new User();
        user.setTenantId(tenantId);
        user.setEmail(request.email());
        user.setDisplayName(request.displayName());
        user.setPasswordHash(request.password() != null ? passwordService.hash(request.password()) : null);
        user.setRoles(request.roles() != null ? request.roles() : new String[0]);
        user.setDvAccess(request.dvAccess() != null && request.dvAccess());
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());

        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public List<User> listUsers() {
        UUID tenantId = TenantContext.getTenantId();
        return userRepository.findByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    public User getUser(UUID id) {
        UUID tenantId = TenantContext.getTenantId();
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        if (!user.getTenantId().equals(tenantId)) {
            throw new NoSuchElementException("User not found: " + id);
        }
        return user;
    }

    @Transactional
    public User updateUser(UUID id, UpdateUserRequest request, UUID actorUserId, String ipAddress) {
        User user = getUser(id);
        boolean tokenInvalidated = false;

        // Track changes for audit
        String[] oldRoles = user.getRoles();
        boolean oldDvAccess = user.isDvAccess();

        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }
        if (request.email() != null) {
            user.setEmail(request.email());
        }
        if (request.roles() != null && !Arrays.equals(oldRoles, request.roles())) {
            user.setRoles(request.roles());
            user.setTokenVersion(user.getTokenVersion() + 1);
            tokenInvalidated = true;
            publishAuditEvent(actorUserId, id, "ROLE_CHANGED",
                    new AuditDetails(oldRoles, request.roles()), ipAddress);
            log.info("User {} roles changed from {} to {} by {}", id,
                    Arrays.toString(oldRoles), Arrays.toString(request.roles()), actorUserId);
        }
        if (request.dvAccess() != null && oldDvAccess != request.dvAccess()) {
            user.setDvAccess(request.dvAccess());
            user.setTokenVersion(user.getTokenVersion() + 1);
            tokenInvalidated = true;
            publishAuditEvent(actorUserId, id, "DV_ACCESS_CHANGED",
                    new AuditDetails(oldDvAccess, request.dvAccess()), ipAddress);
            log.info("User {} dvAccess changed from {} to {} by {}", id,
                    oldDvAccess, request.dvAccess(), actorUserId);
        }
        user.setUpdatedAt(Instant.now());

        User saved = userRepository.save(user);

        if (tokenInvalidated) {
            log.info("Token version incremented to {} for user {} — existing JWTs invalidated",
                    saved.getTokenVersion(), id);
        }

        return saved;
    }

    @Transactional
    public User deactivateUser(UUID id, UUID actorUserId, String ipAddress) {
        User user = getUser(id);

        if (!user.isActive()) {
            throw new IllegalStateException("User is already deactivated: " + id);
        }

        user.setStatus("DEACTIVATED");
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setUpdatedAt(Instant.now());

        User saved = userRepository.save(user);

        // Disconnect SSE stream if connected
        notificationService.completeEmitter(id);

        publishAuditEvent(actorUserId, id, "USER_DEACTIVATED", null, ipAddress);
        log.info("User {} deactivated by {}", id, actorUserId);

        return saved;
    }

    @Transactional
    public User reactivateUser(UUID id, UUID actorUserId, String ipAddress) {
        User user = getUser(id);

        if (user.isActive()) {
            throw new IllegalStateException("User is already active: " + id);
        }

        user.setStatus("ACTIVE");
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setUpdatedAt(Instant.now());

        User saved = userRepository.save(user);

        publishAuditEvent(actorUserId, id, "USER_REACTIVATED", null, ipAddress);
        log.info("User {} reactivated by {}", id, actorUserId);

        return saved;
    }

    /**
     * Find active coordinators with DV access in a tenant.
     * Used for DV referral notifications — only DV-authorized coordinators receive them.
     */
    @Transactional(readOnly = true)
    public List<User> findDvCoordinators(UUID tenantId) {
        return userRepository.findActiveByTenantIdAndDvAccessAndRole(tenantId, "COORDINATOR");
    }

    /**
     * Find active users with a specific role in a tenant (regardless of DV access).
     * Used for escalation notifications to CoC admins, platform-wide broadcasts, etc.
     */
    @Transactional(readOnly = true)
    public List<User> findActiveByRole(UUID tenantId, String role) {
        return userRepository.findActiveByTenantIdAndRole(tenantId, role);
    }

    private void publishAuditEvent(UUID actorUserId, UUID targetUserId, String action,
                                    Object details, String ipAddress) {
        eventPublisher.publishEvent(new AuditEventRecord(
                actorUserId, targetUserId, action, details, ipAddress));
    }

}
