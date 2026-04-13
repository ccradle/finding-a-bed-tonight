package org.fabt.auth.service;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
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

    /**
     * Lookup variant of {@link #getUser} that returns {@link Optional#empty}
     * instead of throwing when the user does not exist or belongs to a
     * different tenant. Used by the admin escalated-queue / claim endpoints
     * which need to render an admin display name without crashing if the
     * row was deleted between the queue refresh and the claim action.
     */
    @Transactional(readOnly = true)
    public Optional<User> findById(UUID id) {
        UUID tenantId = TenantContext.getTenantId();
        return userRepository.findById(id)
                .filter(u -> u.getTenantId().equals(tenantId));
    }

    /**
     * Display-name lookup that exposes only primitives across module
     * boundaries. The referral module's escalated-queue controller calls
     * this instead of {@link #findAllById} so it never imports
     * {@code org.fabt.auth.domain.User} (ArchUnit boundary).
     */
    @Transactional(readOnly = true)
    public java.util.Map<UUID, String> findDisplayNamesByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return java.util.Map.of();
        UUID tenantId = TenantContext.getTenantId();
        java.util.Map<UUID, String> out = new java.util.HashMap<>();
        for (User u : (List<User>) userRepository.findAllById(ids)) {
            if (u.getTenantId().equals(tenantId)) {
                out.put(u.getId(), u.getDisplayName());
            }
        }
        return out;
    }

    /**
     * Return the IDs of all active users matching {@code role} in the given
     * tenant. The escalation batch job calls this via the referral module,
     * which is forbidden from importing {@link User} directly.
     */
    @Transactional(readOnly = true)
    public List<UUID> findActiveUserIdsByRole(UUID tenantId, String role) {
        return userRepository.findActiveByTenantIdAndRole(tenantId, role)
                .stream().map(User::getId).toList();
    }

    /**
     * Return the IDs of all active DV-flagged coordinators in the given
     * tenant. Same boundary reasoning as {@link #findActiveUserIdsByRole}.
     */
    @Transactional(readOnly = true)
    public List<UUID> findDvCoordinatorIds(UUID tenantId) {
        return userRepository.findActiveByTenantIdAndDvAccessAndRole(tenantId, "COORDINATOR")
                .stream().map(User::getId).toList();
    }

    /**
     * All active user IDs with dvAccess=true in a tenant (any role).
     * Used by shelter deactivation to restrict DV event broadcasts (Issue #108).
     */
    @Transactional(readOnly = true)
    public List<UUID> findDvAccessUserIds(UUID tenantId) {
        return userRepository.findDvAccessUserIds(tenantId);
    }

    /**
     * Whether {@code userId} is acting in a CoC-admin or platform-admin
     * capacity. Pushes Casey Drummond's chain-of-custody role check into the
     * auth module so the referral module's audit-type selection doesn't have
     * to import {@link User}.
     */
    @Transactional(readOnly = true)
    public boolean isAdminActor(UUID userId) {
        if (userId == null) return false;
        return findById(userId)
                .map(User::getRoles)
                .map(roles -> {
                    for (String r : roles) {
                        if ("COC_ADMIN".equals(r) || "PLATFORM_ADMIN".equals(r)) return true;
                    }
                    return false;
                })
                .orElse(false);
    }

    /**
     * Whether {@code userId} exists and belongs to the caller's tenant.
     * Boundary-clean primitive: returns a boolean so the referral module
     * doesn't need to import {@link User} (Marcus Webb #2 + Alex Chen, war
     * room round 3).
     */
    @Transactional(readOnly = true)
    public boolean existsByIdInCurrentTenant(UUID userId) {
        if (userId == null) return false;
        return findById(userId).isPresent();
    }

    /**
     * Returns the role list for a user as a {@code List<String>}, or an
     * empty list if the user doesn't exist or belongs to a different tenant.
     * Used by audit-trail enrichment so the referral module can record the
     * actor's roles at action time without importing {@link User} (Casey
     * Drummond #2, frozen-at-action-time chain of custody).
     */
    @Transactional(readOnly = true)
    public List<String> getRolesByUserId(UUID userId) {
        if (userId == null) return List.of();
        return findById(userId)
                .map(User::getRoles)
                .map(java.util.Arrays::asList)
                .orElse(List.of());
    }

    /**
     * Batch-fetch users by ID, restricted to current tenant (Sam Okafor optimization).
     */
    @Transactional(readOnly = true)
    public List<User> findAllById(Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        UUID tenantId = TenantContext.getTenantId();
        return ((List<User>) userRepository.findAllById(ids)).stream()
                .filter(u -> u.getTenantId().equals(tenantId))
                .toList();
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
