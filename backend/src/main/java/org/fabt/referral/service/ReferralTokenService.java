package org.fabt.referral.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.fabt.auth.service.UserService;
import org.fabt.referral.api.ReassignReferralRequest;
import org.fabt.referral.domain.ReferralToken;
import org.fabt.referral.repository.ReferralTokenRepository;
import org.fabt.notification.service.EscalationPolicyService;
import org.fabt.notification.service.NotificationPersistenceService;
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.audit.AuditEventTypes;
import org.fabt.shared.event.DomainEvent;
import org.fabt.shared.event.EventBus;
import org.fabt.shared.web.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.fabt.shelter.domain.Shelter;
import org.fabt.shelter.repository.CoordinatorAssignmentRepository;
import org.fabt.shelter.service.ShelterService;
import org.fabt.tenant.service.TenantService;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DV opaque referral token lifecycle.
 * Zero client PII — tokens contain only operational data.
 * Terminal tokens are hard-deleted within 24 hours.
 */
@Service
public class ReferralTokenService {

    private static final Logger log = LoggerFactory.getLogger(ReferralTokenService.class);
    private static final int DEFAULT_EXPIRY_MINUTES = 240;

    private final ReferralTokenRepository repository;
    private final ShelterService shelterService;
    private final TenantService tenantService;
    private final UserService userService;
    private final EscalationPolicyService escalationPolicyService;
    private final NotificationPersistenceService notificationPersistenceService;
    private final CoordinatorAssignmentRepository coordinatorAssignmentRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final EventBus eventBus;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    /**
     * How long a CoC admin's soft-lock claim lasts before auto-release.
     * Configurable so on-call rotations with longer triage windows can
     * lengthen it without a code change. Default 10 minutes matches the
     * spec (V41 SQL comment) and the PagerDuty acknowledge convention.
     */
    private final int claimDurationMinutes;

    public ReferralTokenService(ReferralTokenRepository repository,
                                ShelterService shelterService,
                                TenantService tenantService,
                                UserService userService,
                                EscalationPolicyService escalationPolicyService,
                                NotificationPersistenceService notificationPersistenceService,
                                CoordinatorAssignmentRepository coordinatorAssignmentRepository,
                                ApplicationEventPublisher eventPublisher,
                                EventBus eventBus,
                                MeterRegistry meterRegistry,
                                ObjectMapper objectMapper,
                                @Value("${fabt.dv-referral.claim-duration-minutes:10}")
                                int claimDurationMinutes) {
        this.repository = repository;
        this.shelterService = shelterService;
        this.tenantService = tenantService;
        this.userService = userService;
        this.escalationPolicyService = escalationPolicyService;
        this.notificationPersistenceService = notificationPersistenceService;
        this.coordinatorAssignmentRepository = coordinatorAssignmentRepository;
        this.eventPublisher = eventPublisher;
        this.eventBus = eventBus;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
        this.claimDurationMinutes = claimDurationMinutes;

        // Gauge for current pending referral count — queried on each Prometheus scrape
        Gauge.builder("fabt.dv.referral.pending", repository, r -> {
            double[] result = {0.0};
            TenantContext.runWithContext(TenantContext.getTenantId(), true, () -> {
                Integer count = r.countAllPending();
                result[0] = count != null ? count.doubleValue() : 0.0;
            });
            return result[0];
        }).description("Current count of pending DV referral tokens")
          .register(meterRegistry);
    }

    /**
     * Create a referral token for a DV shelter. Validates dvShelter=true and no existing PENDING token.
     */
    @Transactional
    public ReferralToken createToken(UUID shelterId, UUID userId, int householdSize,
                                     String populationType, String urgency,
                                     String specialNeeds, String callbackNumber) {
        UUID tenantId = TenantContext.getTenantId();

        if (!TenantContext.getDvAccess()) {
            throw new AccessDeniedException("DV access required for referral operations");
        }

        Shelter shelter = shelterService.findById(shelterId)
                .orElseThrow(() -> new NoSuchElementException("Shelter not found: " + shelterId));

        if (!shelter.isDvShelter()) {
            throw new IllegalArgumentException("Referral tokens are only for DV shelters");
        }

        List<ReferralToken> existing = repository.findPendingByShelterId(shelterId);
        if (existing.stream().anyMatch(t -> t.getReferringUserId().equals(userId))) {
            throw new IllegalStateException(
                    "You already have a pending referral for this shelter. " +
                    "Please wait for a response or let it expire before submitting another.");
        }

        int expiryMinutes = getDvReferralExpiryMinutes(tenantId);
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(expiryMinutes));

        ReferralToken token = new ReferralToken(
                shelterId, tenantId, userId,
                householdSize, populationType, urgency,
                specialNeeds, callbackNumber, expiresAt);

        escalationPolicyService.getCurrentForTenant(tenantId, "dv-referral")
                .ifPresent(policy -> token.setEscalationPolicyId(policy.id()));

        ReferralToken saved = repository.insert(token);

        Map<String, Object> payload = new HashMap<>();
        payload.put("token_id", saved.getId().toString());
        payload.put("shelter_id", shelterId.toString());
        payload.put("tenant_id", tenantId.toString());
        payload.put("referring_user_id", userId.toString());
        payload.put("urgency", urgency);
        eventBus.publish(new DomainEvent("dv-referral.requested", tenantId, payload));

        dvReferralCounter("requested").increment();

        log.info("DV referral token created: tokenId={}, shelterId={}, urgency={}",
                saved.getId(), shelterId, urgency);

        return saved;
    }

    /**
     * Accept a pending referral token. Returns the token with shelter phone for warm handoff.
     */
    @Transactional
    public ReferralToken acceptToken(UUID tokenId, UUID respondedBy) {
        ReferralToken token = repository.findById(tokenId)
                .orElseThrow(() -> new NoSuchElementException("Referral token not found: " + tokenId));

        if (!token.isPending()) {
            throw new IllegalStateException("Token is not pending: " + token.getStatus());
        }
        if (token.isExpired()) {
            throw new IllegalStateException("Token has expired");
        }

        repository.updateStatus(tokenId, "ACCEPTED", respondedBy, null);
        token.setStatus("ACCEPTED");
        token.setRespondedAt(Instant.now());
        token.setRespondedBy(respondedBy);

        // Casey Drummond — chain of custody: the audit type differs by actor role.
        // CoC admins acting on a referral are recorded as ADMIN_ACCEPTED so the
        // audit trail distinguishes "coordinator screened normally" from "admin
        // intervened on the queue."
        String auditAction = isAdminActor(respondedBy)
                ? AuditEventTypes.DV_REFERRAL_ADMIN_ACCEPTED
                : "DV_REFERRAL_ACCEPTED";
        publishAudit(respondedBy, tokenId, auditAction,
                Map.of("shelter_id", token.getShelterId().toString()));

        // Restore referring_user_id in the payload so downstream listeners
        // (notification fan-out to the outreach worker) can target the right
        // user. Removed accidentally during Session 3 refactor.
        Map<String, Object> payload = new HashMap<>();
        payload.put("token_id", tokenId.toString());
        payload.put("shelter_id", token.getShelterId().toString());
        payload.put("referring_user_id", token.getReferringUserId().toString());
        payload.put("status", "ACCEPTED");
        eventBus.publish(new DomainEvent("dv-referral.responded", token.getTenantId(), payload));
        eventBus.publish(new DomainEvent("referral.queue-changed", token.getTenantId(), payload));

        dvReferralCounter("accepted").increment();
        recordResponseTime(token);

        log.info("DV referral accepted: tokenId={}, shelterId={}, audit={}",
                tokenId, token.getShelterId(), auditAction);

        return token;
    }

    /**
     * Reject a pending referral token with a reason.
     */
    @Transactional
    public ReferralToken rejectToken(UUID tokenId, UUID respondedBy, String rejectionReason) {
        if (rejectionReason == null || rejectionReason.isBlank()) {
            throw new IllegalArgumentException("Rejection reason is required");
        }

        ReferralToken token = repository.findById(tokenId)
                .orElseThrow(() -> new NoSuchElementException("Referral token not found: " + tokenId));

        if (!token.isPending()) {
            throw new IllegalStateException("Token is not pending: " + token.getStatus());
        }
        if (token.isExpired()) {
            throw new IllegalStateException("Token has expired");
        }

        repository.updateStatus(tokenId, "REJECTED", respondedBy, rejectionReason);
        token.setStatus("REJECTED");
        token.setRespondedAt(Instant.now());
        token.setRespondedBy(respondedBy);
        token.setRejectionReason(rejectionReason);

        String auditAction = isAdminActor(respondedBy)
                ? AuditEventTypes.DV_REFERRAL_ADMIN_REJECTED
                : "DV_REFERRAL_REJECTED";
        publishAudit(respondedBy, tokenId, auditAction,
                Map.of("shelter_id", token.getShelterId().toString(), "reason", rejectionReason));

        Map<String, Object> payload = new HashMap<>();
        payload.put("token_id", tokenId.toString());
        payload.put("shelter_id", token.getShelterId().toString());
        payload.put("referring_user_id", token.getReferringUserId().toString());
        payload.put("status", "REJECTED");
        eventBus.publish(new DomainEvent("dv-referral.responded", token.getTenantId(), payload));
        eventBus.publish(new DomainEvent("referral.queue-changed", token.getTenantId(), payload));

        dvReferralCounter("rejected").increment();
        recordResponseTime(token);

        log.info("DV referral rejected: tokenId={}, shelterId={}, reason={}, audit={}",
                tokenId, token.getShelterId(), rejectionReason, auditAction);

        return token;
    }

    /**
     * Expire pending tokens past their expiry time. Called by @Scheduled every 60 seconds.
     */
    @Scheduled(fixedRate = 60_000)
    public void expireTokens() {
        TenantContext.runWithContext(TenantContext.getTenantId(), true, () -> {
            if (!TenantContext.getDvAccess()) {
                throw new IllegalStateException("expireTokens requires dvAccess=true");
            }
            List<UUID> expiredIds = repository.expirePendingTokensReturningIds();
            if (!expiredIds.isEmpty()) {
                expiredIds.forEach(id -> {
                    dvReferralCounter("expired").increment();
                    eventBus.publish(new DomainEvent("referral.queue-changed", 
                            TenantContext.getTenantId(), Map.of("token_id", id.toString(), "status", "EXPIRED")));
                });

                Map<String, Object> payload = new HashMap<>();
                payload.put("token_ids", expiredIds.stream().map(UUID::toString).toList());
                eventBus.publish(new DomainEvent("dv-referral.expired",
                        TenantContext.getTenantId(), payload));

                log.info("Expired {} DV referral tokens: {}", expiredIds.size(), expiredIds);
            }
        });
    }

    @Transactional(readOnly = true)
    public List<ReferralToken> getPendingByShelterId(UUID shelterId) {
        return repository.findPendingByShelterId(shelterId);
    }

    @Transactional(readOnly = true)
    public List<ReferralToken> getByUserId(UUID userId) {
        return repository.findByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<ReferralToken> findAllPending() {
        return repository.findAllPending();
    }

    /**
     * Bounded pending lookup for the escalation tasklet (R6 guardrail).
     * Caps the result set so a runaway pending queue cannot OOM the
     * scheduled thread. The caller logs if the cap is hit.
     */
    @Transactional(readOnly = true)
    public List<ReferralToken> findAllPending(int limit) {
        return repository.findAllPending(limit);
    }

    @Transactional(readOnly = true)
    public int countPendingByShelterIds(List<UUID> shelterIds) {
        return repository.countPendingByShelterIds(shelterIds);
    }

    @Transactional(readOnly = true)
    public int countPendingByShelterId(UUID shelterId) {
        return repository.countPendingByShelterId(shelterId);
    }

    /**
     * Get the escalated queue for CoC admins (T-13, T-17), scoped to the
     * caller's tenant. Cross-tenant isolation is enforced at the SQL layer
     * because referral_token RLS only checks dvAccess, not tenant.
     */
    @Transactional(readOnly = true)
    public List<ReferralToken> getEscalatedQueue() {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new AccessDeniedException("Tenant context required to view escalated queue");
        }
        return repository.findEscalatedQueueByTenant(tenantId);
    }

    /**
     * Claim a referral (T-14). Soft-lock for {@code claimDurationMinutes}
     * (default 10). Single atomic {@code UPDATE ... RETURNING *} — concurrent
     * claim attempts cannot both succeed without {@code override=true}, and
     * the winning row comes back in the same DB round-trip (no follow-up
     * SELECT — R1 fix from the Session 3 round-table review).
     */
    @Transactional
    public ReferralToken claimToken(UUID tokenId, UUID adminId, boolean override) {
        Instant claimExpires = Instant.now().plus(Duration.ofMinutes(claimDurationMinutes));

        Optional<ReferralToken> claimed = repository.tryClaim(tokenId, adminId, claimExpires, override);
        if (claimed.isEmpty()) {
            // Diagnose why for the right error code (404 vs 409). This second
            // read is OFF the hot path — only triggered on contention.
            ReferralToken existing = repository.findById(tokenId).orElse(null);
            if (existing == null) {
                throw new NoSuchElementException("Referral token not found: " + tokenId);
            }
            if (!existing.isPending()) {
                throw new IllegalStateException("Only pending referrals can be claimed");
            }
            throw new ClaimConflictException(
                    "Referral is already claimed by another admin until " + existing.getClaimExpiresAt());
        }

        ReferralToken token = claimed.get();

        publishAudit(adminId, tokenId, AuditEventTypes.DV_REFERRAL_CLAIMED,
                Map.of("claimed_until", claimExpires.toString(),
                       "override", String.valueOf(override)));

        eventBus.publish(new DomainEvent("referral.claimed", token.getTenantId(),
                Map.of("referralId", tokenId.toString(),
                       "claimedByUserId", adminId.toString(),
                       "claimedUntil", claimExpires.toString())));

        log.info("DV referral claimed by admin: tokenId={}, adminId={}, expires={}, override={}",
                tokenId, adminId, claimExpires, override);

        return token;
    }

    /**
     * Release a claim manually (T-15). Only the holding admin may release;
     * other admins must pass {@code override=true} (PagerDuty steal pattern).
     *
     * <p>Note on race semantics: between {@code tryRelease} returning 0 and
     * the diagnostic re-read, another admin can newly claim the row. In that
     * case the diagnostic sees a different holder and we throw
     * {@link AccessDeniedException} — which is technically correct ("you do
     * not hold this claim NOW"), but the proximate cause was actually
     * "your release attempt lost the race." The thrown message is
     * deliberately ambiguous so we don't promise diagnostic precision the
     * implementation can't deliver without table-level locking.</p>
     */
    @Transactional
    public void releaseToken(UUID tokenId, UUID adminId, boolean override) {
        int updated = repository.tryRelease(tokenId, adminId, override);
        if (updated == 0) {
            ReferralToken existing = repository.findById(tokenId).orElse(null);
            if (existing == null) {
                throw new NoSuchElementException("Referral token not found: " + tokenId);
            }
            if (existing.getClaimedByAdminId() == null) {
                // Idempotent release: nothing to do, not an error. (This also
                // covers "you held the claim, it auto-released between your
                // page load and your click" — friendlier than a 4xx.)
                return;
            }
            // Either the caller never held the claim, or they did and another
            // admin stole it via override after our tryRelease returned 0. The
            // outcome ("you don't hold this claim now") is the same.
            throw new AccessDeniedException("You do not hold the claim on this referral");
        }

        ReferralToken token = repository.findById(tokenId).orElse(null);
        UUID tenantId = token != null ? token.getTenantId() : TenantContext.getTenantId();

        publishAudit(adminId, tokenId, AuditEventTypes.DV_REFERRAL_RELEASED,
                Map.of("reason", "manual", "override", String.valueOf(override)));

        eventBus.publish(new DomainEvent("referral.released", tenantId,
                Map.of("referralId", tokenId.toString(), "reason", "manual")));

        log.info("DV referral claim released: tokenId={}, adminId={}, override={}",
                tokenId, adminId, override);
    }

    /**
     * Reassign a pending DV referral to a different recipient set (T-17, D5).
     * Three target types:
     *
     * <ul>
     *   <li>{@code COORDINATOR_GROUP} — fans out a {@code referral.reassigned}
     *       notification (severity ACTION_REQUIRED) to every coordinator
     *       assigned to this referral's shelter via
     *       {@code CoordinatorAssignmentRepository.findUserIdsByShelterId}.
     *       Escalation continues normally afterward.</li>
     *   <li>{@code COC_ADMIN_GROUP} — fans out CRITICAL to every active CoC
     *       admin in the tenant via
     *       {@code userService.findActiveUserIdsByRole(tenantId, "COC_ADMIN")}.
     *       Escalation continues normally.</li>
     *   <li>{@code SPECIFIC_USER} — single notification to {@code targetUserId},
     *       AND sets {@code escalation_chain_broken = true} on the referral
     *       so the batch tasklet stops auto-escalating it. The named user is
     *       now the single thread of accountability (Casey Drummond + Marcus
     *       Okafor design call).</li>
     * </ul>
     *
     * <p><b>Audit:</b> writes one {@code DV_REFERRAL_REASSIGNED} row with
     * details {@code {targetType, targetUserId?, reason, recipientCount}}.
     * The reason is stored verbatim — Marcus Webb's PII discipline applies.</p>
     *
     * <p><b>SSE:</b> publishes {@code referral.queue-changed} so connected
     * admins see the queue refresh.</p>
     *
     * @throws NoSuchElementException if the referral does not exist
     * @throws IllegalStateException  if the referral is not pending
     * @throws IllegalArgumentException if {@code SPECIFIC_USER} target type is
     *         used without a {@code targetUserId}
     */
    @Transactional
    public ReferralToken reassignToken(UUID tokenId, UUID actorUserId, ReassignReferralRequest request) {
        if (request.targetType() == ReassignReferralRequest.TargetType.SPECIFIC_USER
                && request.targetUserId() == null) {
            throw new IllegalArgumentException("targetUserId is required for SPECIFIC_USER reassign");
        }

        ReferralToken token = repository.findById(tokenId)
                .orElseThrow(() -> new NoSuchElementException("Referral token not found: " + tokenId));

        // Marcus Webb #1 (war room round 3): cross-tenant guard. referral_token
        // RLS only checks dvAccess, NOT tenant — so a CoC admin in tenant A
        // could pass a tokenId from tenant B and operate on it. Same class of
        // bug we caught in T-13 (escalated queue). Fail loud, not silently.
        UUID callerTenant = TenantContext.getTenantId();
        if (callerTenant == null || !callerTenant.equals(token.getTenantId())) {
            throw new AccessDeniedException("Referral does not belong to your tenant");
        }

        if (!token.isPending()) {
            throw new IllegalStateException("Only pending referrals can be reassigned");
        }

        UUID tenantId = token.getTenantId();
        List<UUID> recipientIds;
        String severity;
        // Casey Drummond (war room round 5): track whether THIS reassign action
        // resumed a previously broken chain. Recorded explicitly in the audit
        // details so a subpoena reading the trail in sequence sees the
        // transition without having to infer it from adjacent rows.
        boolean chainResumed = false;

        switch (request.targetType()) {
            case COORDINATOR_GROUP -> {
                recipientIds = coordinatorAssignmentRepository.findUserIdsByShelterId(token.getShelterId());
                severity = "ACTION_REQUIRED";
                // Marcus Okafor (war room round 4): if a prior SPECIFIC_USER
                // reassign broke the chain, a subsequent group reassign means
                // the admin is "giving the referral back to the group" — clear
                // the flag so auto-escalation resumes. Without this, the
                // chain-broken state is sticky and silent.
                if (token.isEscalationChainBroken()) {
                    repository.markEscalationChainResumed(tokenId);
                    token.setEscalationChainBroken(false);
                    chainResumed = true;
                }
            }
            case COC_ADMIN_GROUP -> {
                recipientIds = userService.findActiveUserIdsByRole(tenantId, "COC_ADMIN");
                severity = "CRITICAL";
                // Same reasoning as COORDINATOR_GROUP — the admin is broadcasting
                // to a group, so per-user accountability is gone and the system
                // should resume auto-escalation.
                if (token.isEscalationChainBroken()) {
                    repository.markEscalationChainResumed(tokenId);
                    token.setEscalationChainBroken(false);
                    chainResumed = true;
                }
            }
            case SPECIFIC_USER -> {
                // Marcus Webb #2: tenant cross-check on target user. The
                // boundary-clean primitive `existsByIdInCurrentTenant` returns
                // true only if the user exists AND belongs to the caller's
                // tenant. The referral module never imports User.
                if (!userService.existsByIdInCurrentTenant(request.targetUserId())) {
                    throw new NoSuchElementException("Target user not found in tenant: " + request.targetUserId());
                }
                recipientIds = List.of(request.targetUserId());
                severity = "ACTION_REQUIRED";
                // Break the escalation chain — the named user owns this now,
                // the batch tasklet will skip it.
                repository.markEscalationChainBroken(tokenId);
                token.setEscalationChainBroken(true);
            }
            default -> throw new IllegalArgumentException("Unknown target type: " + request.targetType());
        }

        if (!recipientIds.isEmpty()) {
            // Keisha Thompson (war room round 3): the admin's free-text reason
            // is recorded in the audit row but NOT in the broadcast notification
            // payload. Recipients see neutral notification metadata; the reason
            // is admin-only context that an admin would relay verbally if needed.
            // Reduces the PII risk surface — even if an admin slips PII into the
            // reason despite the modal warning, only the audit_events row sees
            // it, not every recipient's notification table row.
            String payload = toJson(Map.of(
                    "referralId", tokenId.toString(),
                    "targetType", request.targetType().name()));
            notificationPersistenceService.sendToAll(tenantId, recipientIds,
                    "referral.reassigned", severity, payload);
        } else {
            // Marcus Okafor (war room round 3): zero-recipient fan-out is a
            // configuration smell — the admin paged a group with nobody in it.
            // The audit row still exists with recipientCount=0; this WARN is
            // for ops monitoring.
            log.warn("DV referral reassign produced 0 recipients: tokenId={}, targetType={}, "
                    + "tenantId={} — check group membership / coordinator assignments",
                    tokenId, request.targetType(), tenantId);
        }

        // Casey Drummond #1 + #2 (war room round 3): audit details enriched
        // with shelterId (single-table subpoena queries) and actorRoles
        // (frozen-at-action-time so a later role change doesn't rewrite history).
        // Casey Drummond (war room round 5): chainResumed=true is recorded
        // explicitly when a group reassign cleared a previously broken chain,
        // so the audit row tells the story without needing adjacent-row
        // inference.
        Map<String, Object> auditDetails = new HashMap<>();
        auditDetails.put("targetType", request.targetType().name());
        auditDetails.put("reason", request.reason());
        auditDetails.put("recipientCount", recipientIds.size());
        auditDetails.put("shelterId", token.getShelterId().toString());
        auditDetails.put("actorRoles", userService.getRolesByUserId(actorUserId));
        if (request.targetUserId() != null) {
            auditDetails.put("targetUserId", request.targetUserId().toString());
        }
        if (chainResumed) {
            auditDetails.put("chainResumed", true);
        }
        publishAudit(actorUserId, tokenId, AuditEventTypes.DV_REFERRAL_REASSIGNED, auditDetails);

        eventBus.publish(new DomainEvent("referral.queue-changed", tenantId,
                Map.of("referralId", tokenId.toString(),
                       "targetType", request.targetType().name(),
                       "recipientCount", recipientIds.size())));

        log.info("DV referral reassigned: tokenId={}, actor={}, targetType={}, recipients={}, chainBroken={}",
                tokenId, actorUserId, request.targetType(), recipientIds.size(),
                token.isEscalationChainBroken());

        return token;
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.error("Failed to serialize JSON payload: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * Auto-release expired claims (T-16). Called by @Scheduled every minute.
     *
     * <p><b>Tenant context:</b> the @Scheduled thread inherits no tenant, so
     * we bind tenantId=null, dvAccess=true (matching {@link #expireTokens}).
     * Cross-tenant cleanup is intentional — this is a system task. Per-tenant
     * SSE events are routed using each released claim's own tenant_id, which
     * the repository RETURNs alongside the id (Elena Vasquez fix).</p>
     *
     * <p><b>Defense in depth:</b> if dvAccess context is lost, fail loud
     * rather than silently scanning nothing — referral_token RLS would hide
     * DV-linked rows and the auto-release would silently stop working.</p>
     *
     * <p><b>NOTE: For multi-instance deployments, add ShedLock to prevent
     * duplicate execution.</b> Matches the project convention used by
     * {@code NotificationPersistenceService.cleanupOldNotifications},
     * {@code ApiKeyService}, and {@code SubscriptionService}. Single-instance
     * Oracle Always-Free deploy makes this safe today; horizontal scale-out
     * would race minute-aligned executions across instances. Each individual
     * UPDATE is still atomic so no data corruption — but auto-release audit
     * rows and SSE events would multi-fire.</p>
     */
    @Scheduled(fixedRate = 60_000)
    public void autoReleaseClaims() {
        TenantContext.runWithContext(null, true, () -> {
            if (!TenantContext.getDvAccess()) {
                throw new IllegalStateException(
                        "autoReleaseClaims requires dvAccess=true — DV referral claims would be invisible");
            }
            List<ReferralTokenRepository.ReleasedClaim> released = repository.clearExpiredClaims();
            if (!released.isEmpty()) {
                for (ReferralTokenRepository.ReleasedClaim r : released) {
                    publishAudit(null, r.id(), AuditEventTypes.DV_REFERRAL_RELEASED,
                            Map.of("reason", "timeout"));
                    eventBus.publish(new DomainEvent("referral.released", r.tenantId(),
                            Map.of("referralId", r.id().toString(), "reason", "timeout")));
                }
                log.info("Auto-released {} expired referral claims", released.size());
            }
        });
    }

    /** Marker exception so the controller can map to 409 Conflict. */
    public static class ClaimConflictException extends RuntimeException {
        public ClaimConflictException(String message) { super(message); }
    }

    @Transactional(readOnly = true)
    public String getShelterPhoneForToken(ReferralToken token) {
        if (!"ACCEPTED".equals(token.getStatus())) {
            return null;
        }
        return shelterService.findById(token.getShelterId())
                .map(Shelter::getPhone)
                .orElse(null);
    }

    /**
     * Publish an audit event using the project-wide
     * {@link ApplicationEventPublisher} pattern. The {@code AuditEventService}
     * picks these up via {@code @EventListener} and writes to {@code audit_events}
     * — the same indirection used by {@code UserService.publishAuditEvent}.
     */
    private void publishAudit(UUID actorUserId, UUID targetId, String action, Object details) {
        eventPublisher.publishEvent(new AuditEventRecord(
                actorUserId, targetId, action, details, /* ipAddress */ null));
    }

    /**
     * Determine whether the actor is acting in a CoC-admin (or platform-admin)
     * capacity, so the audit trail can record {@code DV_REFERRAL_ADMIN_ACCEPTED}
     * vs {@code DV_REFERRAL_ACCEPTED}. Casey Drummond's chain-of-custody
     * requirement: a court-bound audit must distinguish coordinator screening
     * from admin intervention.
     *
     * <p>Resolved through {@code UserService.isAdminActor(...)} so the
     * referral module never imports {@code auth.domain.User} (Alex Chen
     * ArchUnit boundary).</p>
     */
    private boolean isAdminActor(UUID actorUserId) {
        return userService.isAdminActor(actorUserId);
    }

    private Counter dvReferralCounter(String status) {
        return Counter.builder("fabt.dv.referral.total")
                .tag("status", status)
                .register(meterRegistry);
    }

    private void recordResponseTime(ReferralToken token) {
        if (token.getCreatedAt() != null) {
            long responseMs = java.time.Duration.between(token.getCreatedAt(), Instant.now()).toMillis();
            Timer.builder("fabt.dv.referral.response")
                    .description("Time from referral request to shelter response")
                    .register(meterRegistry)
                    .record(java.time.Duration.ofMillis(responseMs));
        }
    }

    private int getDvReferralExpiryMinutes(UUID tenantId) {
        try {
            return tenantService.findById(tenantId)
                    .filter(t -> t.getConfig() != null && t.getConfig().value() != null)
                    .map(t -> {
                        try {
                            JsonNode node = objectMapper.readTree(t.getConfig().value());
                            JsonNode expiry = node.get("dv_referral_expiry_minutes");
                            return expiry != null ? expiry.asInt(DEFAULT_EXPIRY_MINUTES) : DEFAULT_EXPIRY_MINUTES;
                        } catch (tools.jackson.core.JacksonException e) {
                            log.warn("Failed to read DV referral expiry from tenant config, using default: {}", e.getMessage());
                            return DEFAULT_EXPIRY_MINUTES;
                        }
                    })
                    .orElse(DEFAULT_EXPIRY_MINUTES);
        } catch (Exception e) {
            log.warn("Failed to read DV referral expiry from tenant config, using default: {}", e.getMessage());
            return DEFAULT_EXPIRY_MINUTES;
        }
    }
}
