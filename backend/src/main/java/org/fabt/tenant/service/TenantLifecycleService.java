package org.fabt.tenant.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.fabt.auth.service.ApiKeyService;
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.audit.AuditEventTypes;
import org.fabt.shared.audit.DetachedAuditPersister;
import org.fabt.shared.security.TenantKeyRotationService;
import org.fabt.tenant.domain.IllegalStateTransitionException;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.domain.TenantState;
import org.fabt.tenant.repository.TenantRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tenant lifecycle FSM, authoritative owner of every {@code tenant.state} write.
 *
 * <p><b>Sole write path directive:</b> this service is the only component permitted to
 * mutate {@link Tenant#setState(TenantState)}. {@code TenantService} remains canonical
 * for tenant name/config/read paths but does NOT touch state. Duplicate state-mutation
 * paths are a compliance hazard (GDPR Art. 17 erasure depends on a single well-audited
 * transition graph) and will be guarded by an ArchUnit rule in slice F-3.</p>
 *
 * <p><b>Current implementation status (slice F-1):</b> FSM skeleton only. Methods perform
 * the §D8 transition assertion + state column update; they do NOT yet emit audit rows,
 * bump JWT key generations, deactivate API keys, run offboard exports, or crypto-shred.
 * Those side effects arrive in slices F-2 (suspend + audit + JWT bump), F-4 (atomic
 * create), F-5 (offboard export), F-6 (crypto-shred hard-delete). The {@code create}
 * and {@code hardDelete} methods throw {@link UnsupportedOperationException} in this
 * slice — full semantics require migrations + services that are not yet in place.</p>
 *
 * <p><b>Feature flag:</b> the entire service is gated behind
 * {@code fabt.tenant.lifecycle.enabled} (default {@code false}). Until slice F-3 ships
 * the read-path state enforcement, callers MUST NOT rely on lifecycle state for
 * authorization decisions — the flag stays off in prod until then.</p>
 *
 * <p>Design reference: {@code multi-tenant-production-readiness} §D8, §F1–F8.</p>
 */
@Service
@ConditionalOnProperty(name = "fabt.tenant.lifecycle.enabled", havingValue = "true", matchIfMissing = false)
public class TenantLifecycleService {

    private final TenantRepository tenantRepository;
    private final TenantKeyRotationService tenantKeyRotationService;
    private final ApiKeyService apiKeyService;
    private final ApplicationEventPublisher eventPublisher;
    private final JdbcTemplate jdbc;
    private final TenantStateGuard tenantStateGuard;
    private final DetachedAuditPersister detachedAuditPersister;

    public TenantLifecycleService(TenantRepository tenantRepository,
                                   TenantKeyRotationService tenantKeyRotationService,
                                   ApiKeyService apiKeyService,
                                   ApplicationEventPublisher eventPublisher,
                                   JdbcTemplate jdbc,
                                   TenantStateGuard tenantStateGuard,
                                   DetachedAuditPersister detachedAuditPersister) {
        this.tenantRepository = tenantRepository;
        this.tenantKeyRotationService = tenantKeyRotationService;
        this.apiKeyService = apiKeyService;
        this.eventPublisher = eventPublisher;
        this.jdbc = jdbc;
        this.tenantStateGuard = tenantStateGuard;
        this.detachedAuditPersister = detachedAuditPersister;
    }

    /**
     * Helper to wrap a transition attempt in the Family-D attempt-audit pattern: if
     * the §D8 FSM rejects, persist a {@code *_REJECTED} audit row via the detached
     * persister (REQUIRES_NEW) BEFORE re-throwing. The detached persister swallows
     * its own errors so a failing audit row doesn't mask the original
     * {@link IllegalStateTransitionException}.
     *
     * <p>Why REQUIRES_NEW: the outer {@code @Transactional} on the calling method
     * will roll back when the FSM exception propagates out. If we persisted the
     * audit row in the outer tx, it would roll back too — losing the forensic
     * evidence. Marcus warroom F-2 HIGH pattern, formalised in F-3.6.</p>
     */
    private <T> T wrapAttemptAudit(UUID tenantId, UUID actorUserId, String justification,
                                    String rejectedAction,
                                    java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalStateTransitionException rejected) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("actor_user_id", actorUserId == null ? null : actorUserId.toString());
            details.put("justification", justification);
            details.put("current_state", rejected.from() == null ? null : rejected.from().name());
            details.put("requested_state", rejected.to() == null ? null : rejected.to().name());
            details.put("rejection_reason", rejected.getMessage());
            detachedAuditPersister.persistDetached(tenantId,
                new AuditEventRecord(actorUserId, null, rejectedAction, details, null));
            throw rejected;
        }
    }

    /**
     * Registers an after-commit hook to invalidate the {@link TenantStateGuard} cache
     * for {@code tenantId}. Runs ONLY if the transaction commits — on rollback the
     * state change didn't happen, so the cache entry is still correct. Without this
     * hook, suspended tenants could continue serving reads for up to the 10s cache
     * TTL; with it, the next request sees the new state within milliseconds.
     */
    private void scheduleStateCacheInvalidation(UUID tenantId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
            && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        tenantStateGuard.invalidate(tenantId);
                    }
                });
        } else {
            // Defensive — callers shouldn't reach here (all public methods are
            // @Transactional and Spring enables synchronization within the tx
            // boundary). Fall back to synchronous invalidation so unit tests
            // without a real tx still see the right behavior, and any future
            // refactor that drops @Transactional doesn't leave the cache stale.
            tenantStateGuard.invalidate(tenantId);
        }
    }

    /**
     * Binds {@code app.tenant_id} session GUC to {@code tenantId} for the duration of
     * the current {@code @Transactional} scope. Mirrors the pattern in
     * {@link TenantKeyRotationService#bumpJwtKeyGeneration} (the B11 ArchUnit rule forbids
     * nested {@code TenantContext.runWithContext} inside {@code @Transactional}, so we
     * {@code set_config} directly). {@code AuditEventService} reads this GUC via its D55
     * three-level lookup so the audit row lands with the correct {@code tenant_id} rather
     * than the {@code SYSTEM_TENANT_ID} fallback.
     *
     * <p>Asserts an active transaction — {@code set_config(..., true)} binds to the
     * current tx only. Without an active tx the binding scopes to a single statement
     * and the subsequent audit INSERT silently loses the GUC, falling back to
     * SYSTEM_TENANT_ID. Catching this at call time surfaces the silent regression that
     * would otherwise only show up in production audit queries.</p>
     */
    private void bindTenantContextForAudit(UUID tenantId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "bindTenantContextForAudit requires an active transaction; caller must "
                + "be @Transactional so set_config(..., is_local=true) persists across "
                + "the subsequent audit INSERT (D55 path 2)");
        }
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
            String.class, tenantId.toString());
    }

    /**
     * Atomic tenant-create bootstrap (NEW → ACTIVE). Implementation deferred to slice F-4
     * (derives JWT gen-1 key, derives DEKs, seeds audit_chain_head, verifies RLS canary).
     * Callers must continue to use {@link TenantService#create(String, String)} until
     * F-4 lands and the switch is made with a {@code @Deprecated} redirect.
     *
     * <p>Not {@code @Transactional} yet — F-4 adds the annotation when real bodies land,
     * avoiding the wasted tx open-then-rollback cycle that a stub throw would otherwise
     * incur.</p>
     */
    public Tenant create(String name, String slug, UUID actorUserId) {
        throw new UnsupportedOperationException(
            "TenantLifecycleService.create is implemented in slice F-4; "
            + "use TenantService.create until then");
    }

    /**
     * Quarantines a tenant — transitions ACTIVE → SUSPENDED with the §D11 5-action
     * atomic pattern (here we execute 4 of 5; the 5th — stop background worker
     * dispatch — is a no-op today because worker dispatch is not yet tenant-scoped,
     * tracked as part of Phase E).
     *
     * <ol>
     *   <li>Load + FSM assert (self-transition on already-SUSPENDED → 409 via
     *       {@link org.fabt.tenant.domain.IllegalStateTransitionException}, which is
     *       the idempotency contract).</li>
     *   <li>Bump JWT key generation — invalidates every live refresh token across
     *       the tenant; delegates to {@link TenantKeyRotationService#bumpJwtKeyGeneration}
     *       (joins this tx via default REQUIRED propagation, emits its own
     *       {@code JWT_KEY_GENERATION_BUMPED} audit row).</li>
     *   <li>Deactivate all API keys for the tenant (bulk UPDATE via
     *       {@link ApiKeyService#deactivateAllForTenant}). Intentionally NOT
     *       auto-reactivated on {@link #unsuspend}: operator re-issues keys after
     *       incident review (post-compromise hygiene).</li>
     *   <li>Flip state to SUSPENDED + save.</li>
     *   <li>Emit {@link AuditEventTypes#TENANT_SUSPENDED} — details capture
     *       actor, justification, previous state, and counts returned by steps 2–3
     *       for forensic join with the {@code JWT_KEY_GENERATION_BUMPED} row.</li>
     * </ol>
     *
     * @param tenantId      the tenant to quarantine
     * @param actorUserId   platform admin triggering the suspend; may be null for
     *                      system-initiated operations (e.g. automated abuse response)
     * @param justification human-readable reason recorded in the audit row
     * @return the suspended tenant aggregate
     */
    @Transactional
    public Tenant suspend(UUID tenantId, UUID actorUserId, String justification) {
        return wrapAttemptAudit(tenantId, actorUserId, justification,
            AuditEventTypes.TENANT_SUSPEND_REJECTED,
            () -> doSuspend(tenantId, actorUserId, justification));
    }

    private Tenant doSuspend(UUID tenantId, UUID actorUserId, String justification) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new NoSuchElementException("tenant not found: " + tenantId));
        TenantState previous = tenant.getState();
        TenantState.assertTransition(previous, TenantState.SUSPENDED);

        // Bind app.tenant_id GUC so the TENANT_SUSPENDED audit row lands with the
        // correct tenant_id (AuditEventService D55 path 2). bumpJwtKeyGeneration
        // also sets this same GUC at its own entry, but re-binding here is
        // defensive: if a future refactor reorders steps or drops the JWT bump,
        // the TENANT_SUSPENDED audit must still carry the right tenant_id.
        bindTenantContextForAudit(tenantId);

        TenantKeyRotationService.RotationResult rotation =
            tenantKeyRotationService.bumpJwtKeyGeneration(tenantId, actorUserId);

        int deactivatedKeys = apiKeyService.deactivateAllForTenant(tenantId);

        tenant.setState(TenantState.SUSPENDED);
        Tenant saved = tenantRepository.save(tenant);

        eventPublisher.publishEvent(new AuditEventRecord(
            actorUserId, null, AuditEventTypes.TENANT_SUSPENDED,
            auditDetails(actorUserId, justification, previous, Map.of(
                "revokedKidCount", rotation.revokedKidCount(),
                "deactivatedApiKeys", deactivatedKeys)),
            null));

        scheduleStateCacheInvalidation(tenantId);
        return saved;
    }

    /**
     * Restores a SUSPENDED tenant to ACTIVE. Intentionally asymmetric with
     * {@link #suspend}:
     *
     * <ul>
     *   <li>JWT keys are <em>not</em> re-rotated — the suspend bump already
     *       invalidated prior tokens; fresh tokens issue at next login from the
     *       current generation.</li>
     *   <li>API keys are <em>not</em> auto-reactivated — post-compromise the
     *       operator decides which keys to re-issue (D11 safer default).</li>
     * </ul>
     *
     * @param tenantId      the tenant to un-quarantine
     * @param actorUserId   platform admin triggering the unsuspend; may be null
     * @param justification reason recorded in the audit row
     */
    @Transactional
    public Tenant unsuspend(UUID tenantId, UUID actorUserId, String justification) {
        return wrapAttemptAudit(tenantId, actorUserId, justification,
            AuditEventTypes.TENANT_UNSUSPEND_REJECTED,
            () -> doUnsuspend(tenantId, actorUserId, justification));
    }

    private Tenant doUnsuspend(UUID tenantId, UUID actorUserId, String justification) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new NoSuchElementException("tenant not found: " + tenantId));
        TenantState previous = tenant.getState();
        TenantState.assertTransition(previous, TenantState.ACTIVE);

        // Bind app.tenant_id so TENANT_UNSUSPENDED audit row lands with the correct
        // tenant_id. No JWT bump on unsuspend (§D11 post-compromise hygiene), so this
        // is the only path that sets the GUC for the audit lookup.
        bindTenantContextForAudit(tenantId);

        tenant.setState(TenantState.ACTIVE);
        Tenant saved = tenantRepository.save(tenant);

        eventPublisher.publishEvent(new AuditEventRecord(
            actorUserId, null, AuditEventTypes.TENANT_UNSUSPENDED,
            auditDetails(actorUserId, justification, previous, Map.of()),
            null));

        scheduleStateCacheInvalidation(tenantId);
        return saved;
    }

    /**
     * Transition {ACTIVE, SUSPENDED} → OFFBOARDING. Slice F-5 wires the export workflow
     * (schema'd JSON dump written to a GDPR Art. 20 delivery location) and stores the
     * export receipt URI on the tenant row.
     */
    @Transactional
    public Tenant offboard(UUID tenantId, UUID actorUserId, String justification) {
        return wrapAttemptAudit(tenantId, actorUserId, justification,
            AuditEventTypes.TENANT_OFFBOARD_REJECTED,
            () -> transitionTo(tenantId, TenantState.OFFBOARDING));
    }

    /**
     * Transition OFFBOARDING → ARCHIVED. Slice F-5 adds the {@code archived_at}
     * timestamp write and requires a non-null {@code offboard_export_receipt_uri}
     * before permitting the transition.
     */
    @Transactional
    public Tenant archive(UUID tenantId, UUID actorUserId, String justification) {
        return wrapAttemptAudit(tenantId, actorUserId, justification,
            AuditEventTypes.TENANT_ARCHIVE_REJECTED,
            () -> transitionTo(tenantId, TenantState.ARCHIVED));
    }

    /**
     * Crypto-shred: ARCHIVED → DELETED with the key-material, audit-chain-head, and
     * tenant row DELETE cascade (design §D11). Implementation deferred to slice F-6,
     * which (a) gates on {@code archived_at < NOW() - 30d}, (b) writes TENANT_HARD_DELETED
     * audit row in a separate committed tx BEFORE the destructive tx, (c) deletes in the
     * order {@code tenant_key_material → tenant_audit_chain_head → tenant} per §D11.
     *
     * <p>Not {@code @Transactional} yet — F-6's two-tx pattern (audit-commit first,
     * destructive-tx second) is NOT a single {@code @Transactional} wrap anyway. The
     * annotation arrives on a private helper in F-6, not here.</p>
     */
    public void hardDelete(UUID tenantId, UUID actorUserId, String justification) {
        throw new UnsupportedOperationException(
            "TenantLifecycleService.hardDelete is implemented in slice F-6 with full "
            + "crypto-shred cascade and audit-before-destructive-tx ordering");
    }

    /**
     * Core transition helper used by offboard/archive (suspend/unsuspend inline the same
     * load-assert-flip-save pattern because they also need to emit audits and call other
     * services). F-5 will restructure offboard/archive with export logic; until then this
     * helper keeps those methods shaped like F-1's skeleton.
     *
     * <p>Throws {@link NoSuchElementException} if the tenant does not exist (F-3 translates
     * to 404). Throws {@link org.fabt.tenant.domain.IllegalStateTransitionException} if the
     * transition violates §D8 (F-3 translates to 409).</p>
     */
    private Tenant transitionTo(UUID tenantId, TenantState target) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new NoSuchElementException("tenant not found: " + tenantId));
        TenantState.assertTransition(tenant.getState(), target);
        tenant.setState(target);
        Tenant saved = tenantRepository.save(tenant);
        scheduleStateCacheInvalidation(tenantId);
        return saved;
    }

    /**
     * Builds the audit-event details JSON skeleton shared by every Phase F lifecycle
     * event. The schema is pinned in
     * {@link AuditEventTypes#TENANT_SUSPENDED} (and peers): Phase G's
     * {@code platform_admin_access_log} reads {@code actor_user_id} and
     * {@code justification} directly, so we commit to those field names now.
     *
     * <p>{@code extras} layers transition-specific details (e.g. revokedKidCount)
     * on top without duplicating the baseline at every call site.</p>
     */
    private static Map<String, Object> auditDetails(UUID actorUserId, String justification,
                                                     TenantState previousState,
                                                     Map<String, Object> extras) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("actor_user_id", actorUserId == null ? null : actorUserId.toString());
        details.put("justification", justification);
        details.put("previous_state", previousState.name());
        details.putAll(extras);
        return details;
    }
}
