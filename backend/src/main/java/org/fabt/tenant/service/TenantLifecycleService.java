package org.fabt.tenant.service;

import java.util.NoSuchElementException;
import java.util.UUID;

import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.domain.TenantState;
import org.fabt.tenant.repository.TenantRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public TenantLifecycleService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    /**
     * Atomic tenant-create bootstrap (NEW → ACTIVE). Implementation deferred to slice F-4
     * (derives JWT gen-1 key, derives DEKs, seeds audit_chain_head, verifies RLS canary).
     * Callers must continue to use {@link TenantService#create(String, String)} until
     * F-4 lands and the switch is made with a {@code @Deprecated} redirect.
     */
    @Transactional
    public Tenant create(String name, String slug, UUID actorUserId) {
        throw new UnsupportedOperationException(
            "TenantLifecycleService.create is implemented in slice F-4; "
            + "use TenantService.create until then");
    }

    /**
     * Transition ACTIVE → SUSPENDED. Slice F-1 performs the state mutation only; slice
     * F-2 adds JWT key-generation bump, API-key deactivation, audit emission, and
     * idempotency handling (second call returns 409, not 200).
     */
    @Transactional
    public Tenant suspend(UUID tenantId, UUID actorUserId, String justification) {
        return transitionTo(tenantId, TenantState.SUSPENDED);
    }

    /**
     * Transition SUSPENDED → ACTIVE. Slice F-2 adds audit emission; JWT keys are NOT
     * re-rotated on unsuspend (the bump on suspend invalidated all prior tokens, and
     * unsuspend issues a fresh JWT from the current gen at next login).
     */
    @Transactional
    public Tenant unsuspend(UUID tenantId, UUID actorUserId, String justification) {
        return transitionTo(tenantId, TenantState.ACTIVE);
    }

    /**
     * Transition {ACTIVE, SUSPENDED} → OFFBOARDING. Slice F-5 wires the export workflow
     * (schema'd JSON dump written to a GDPR Art. 20 delivery location) and stores the
     * export receipt URI on the tenant row.
     */
    @Transactional
    public Tenant offboard(UUID tenantId, UUID actorUserId, String justification) {
        return transitionTo(tenantId, TenantState.OFFBOARDING);
    }

    /**
     * Transition OFFBOARDING → ARCHIVED. Slice F-5 adds the {@code archived_at}
     * timestamp write and requires a non-null {@code offboard_export_receipt_uri}
     * before permitting the transition.
     */
    @Transactional
    public Tenant archive(UUID tenantId, UUID actorUserId, String justification) {
        return transitionTo(tenantId, TenantState.ARCHIVED);
    }

    /**
     * Crypto-shred: ARCHIVED → DELETED with the key-material, audit-chain-head, and
     * tenant row DELETE cascade (design §D11). Implementation deferred to slice F-6,
     * which (a) gates on {@code archived_at < NOW() - 30d}, (b) writes TENANT_HARD_DELETED
     * audit row in a separate committed tx BEFORE the destructive tx, (c) deletes in the
     * order {@code tenant_key_material → tenant_audit_chain_head → tenant} per §D11.
     */
    @Transactional
    public void hardDelete(UUID tenantId, UUID actorUserId, String justification) {
        throw new UnsupportedOperationException(
            "TenantLifecycleService.hardDelete is implemented in slice F-6 with full "
            + "crypto-shred cascade and audit-before-destructive-tx ordering");
    }

    /**
     * Core transition helper: load tenant, assert transition via {@link TenantState#assertTransition},
     * flip state, save. Extracted so every lifecycle method shares the same guard.
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
        return tenantRepository.save(tenant);
    }
}
