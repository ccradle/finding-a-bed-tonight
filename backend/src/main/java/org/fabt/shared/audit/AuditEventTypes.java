package org.fabt.shared.audit;

/**
 * Centralized constants for audit event type strings used in the
 * {@code audit_events.action} column.
 *
 * <p>Originally created in v0.34.0 (bed-hold-integrity, Issue #102 RCA) to hold
 * {@link #BED_HOLDS_RECONCILED}. Extended in Session 1 of the
 * {@code coc-admin-escalation} change to support the chain-of-custody audit trail
 * for CoC admin actions on DV referrals — Casey Drummond's lens, the court
 * subpoena answer to "who acted on the survivor's referral, when, and from
 * which IP".</p>
 *
 * <p><b>Why a constants class instead of a Java {@code enum}:</b> the existing
 * codebase passes audit type strings as bare {@code String} literals to
 * {@code AuditEventService.publish(...)} (e.g. {@code "ROLE_CHANGED"} in
 * {@code UserService}). The {@code AuditEventRecord} record itself declares
 * the {@code action} field as {@code String}. Migrating to a true {@code enum}
 * would require refactoring every existing call site at once, which is out of
 * scope for both the v0.34.0 bed-hold-integrity work and the additive
 * coc-admin-escalation Session 1 schema work. New code in bed-hold-integrity
 * and coc-admin-escalation Sessions 3-4 references these constants; legacy
 * strings remain as-is until a future cleanup change.</p>
 *
 * <p><b>Spec drift note (coc-admin-escalation):</b> tasks.md T-6 originally said
 * "Add the 6 new constants to the application's {@code AuditEventType} enum" —
 * discovered during implementation that no such enum exists. The deviation
 * (constants class vs enum) was made consciously to keep Session 1 purely
 * additive and defer the larger refactor to a follow-up.</p>
 *
 * <p><b>Test pin:</b> {@code AuditEventTypesTest} asserts every constant defined
 * here is non-null and non-empty. When adding a new constant, add a new test
 * method to that class as well — Casey Drummond's chain-of-custody ask is that
 * every audit action is contract-pinned so a refactor can't silently rename
 * the string and break historical queries.</p>
 */
public final class AuditEventTypes {

    // ---- bed-hold-integrity (v0.34.0, Issue #102 RCA) ----

    /**
     * Written by the bed-holds reconciliation tasklet whenever a corrective
     * snapshot is fired to bring {@code bed_availability.beds_on_hold} back into
     * agreement with the actual count of HELD reservations for that
     * shelter+population. ALSO written by the V45 one-time backfill migration
     * with {@code correction_source='V45_backfill'} in the details payload.
     *
     * <p>Payload: {@code {shelter_id, population_type, snapshot_value_before,
     * actual_count, delta}} for reconciliation rows. V45 backfill rows include
     * an additional {@code correction_source} and {@code github_issue} field.</p>
     *
     * <p>Actor: {@code null} (system-driven). V44 (v0.34.0) dropped the
     * {@code NOT NULL} constraint on {@code audit_events.actor_user_id} to
     * enable these system-actor rows.</p>
     */
    public static final String BED_HOLDS_RECONCILED = "BED_HOLDS_RECONCILED";

    // ---- coc-admin-escalation: CoC admin actions on DV referrals (Sessions 3-4) ----

    /**
     * An outreach worker (or other DV-authorized role) requested a new DV referral via
     * {@code POST /api/v1/dv-referrals}. Detail blob: {@code shelter_id}, {@code shelter_name},
     * {@code urgency} — zero client PII.
     */
    public static final String DV_REFERRAL_REQUESTED = "DV_REFERRAL_REQUESTED";

    /**
     * A CoC admin claimed a pending DV referral via {@code POST /api/v1/dv-referrals/{id}/claim}.
     * Detail blob: {@code {referral_id, claimed_until}}. Zero PII.
     */
    public static final String DV_REFERRAL_CLAIMED = "DV_REFERRAL_CLAIMED";

    /**
     * A CoC admin released a claim manually, OR the auto-release scheduler
     * cleared an expired claim ({@code actor_user_id = system}).
     * Detail blob: {@code {referral_id, reason: "manual"|"timeout"}}. Zero PII.
     */
    public static final String DV_REFERRAL_RELEASED = "DV_REFERRAL_RELEASED";

    /**
     * A CoC admin reassigned a pending DV referral via
     * {@code POST /api/v1/dv-referrals/{id}/reassign}. Detail blob:
     * {@code {referral_id, target_type: COORDINATOR_GROUP|COC_ADMIN_GROUP|SPECIFIC_USER, target_id, previous_assignee_id}}.
     * SPECIFIC_USER target type breaks the escalation chain by design (PagerDuty pattern).
     */
    public static final String DV_REFERRAL_REASSIGNED = "DV_REFERRAL_REASSIGNED";

    /**
     * A CoC admin (not a coordinator) accepted a pending DV referral directly
     * via {@code PATCH /api/v1/dv-referrals/{id}/accept}. Distinct from the
     * coordinator-action audit type (which would be {@code DV_REFERRAL_ACCEPTED})
     * so the chain-of-custody trail can differentiate admin overrides from
     * normal coordinator screening. Detail blob: {@code {referral_id, shelter_id}}.
     */
    public static final String DV_REFERRAL_ADMIN_ACCEPTED = "DV_REFERRAL_ADMIN_ACCEPTED";

    /**
     * A CoC admin rejected a pending DV referral directly via
     * {@code PATCH /api/v1/dv-referrals/{id}/reject}. Distinct from coordinator
     * rejection. Detail blob: {@code {referral_id, shelter_id, reason}}. The
     * admin-supplied reason field is the admin's responsibility to keep
     * PII-free; the UI shows a warning above the field.
     */
    public static final String DV_REFERRAL_ADMIN_REJECTED = "DV_REFERRAL_ADMIN_REJECTED";

    /**
     * A CoC admin updated the per-tenant escalation policy via
     * {@code PATCH /api/v1/admin/escalation-policy/{eventType}}. The policy is
     * append-only versioned, so this audit row records the new version
     * created, not a mutation of an existing row. Detail blob:
     * {@code {policy_id, event_type, version, previous_version}}.
     */
    public static final String ESCALATION_POLICY_UPDATED = "ESCALATION_POLICY_UPDATED";

    // ---- shelter-activate-deactivate (Issue #108) ----

    /**
     * A CoC admin deactivated a shelter via {@code PATCH /api/v1/shelters/{id}/deactivate}.
     * Detail blob: {@code {shelter_id, shelter_name, deactivation_reason}}.
     */
    public static final String SHELTER_DEACTIVATED = "SHELTER_DEACTIVATED";

    /**
     * A CoC admin reactivated a shelter via {@code PATCH /api/v1/shelters/{id}/reactivate}.
     * Detail blob: {@code {shelter_id, shelter_name}}.
     */
    public static final String SHELTER_REACTIVATED = "SHELTER_REACTIVATED";

    // ---- multi-tenant-production-readiness Phase C (cache isolation) ----

    /**
     * Emitted by {@code TenantScopedCacheService.invalidateTenant(UUID)} when
     * a tenant's cache entries are evicted across every registered cache name.
     * Fires at tenant suspend / hard-delete (Phase F F4) and on demand by the
     * platform-admin API.
     *
     * <p>Detail blob: {@code {tenantId, perCacheEvictionCounts: {cacheName: N, ...}}}.
     * Actor: {@code null} for FSM-driven calls; platform-admin UUID for manual
     * invalidations. Target: {@code null} (operation scope is the tenant itself).
     *
     * <p>Persisted via the event-bus path ({@code ApplicationEventPublisher} →
     * {@code AuditEventService.onAuditEvent} → {@code AuditEventPersister} with
     * {@code PROPAGATION_REQUIRED}). Normal rollback semantics apply: operator-
     * initiated invalidations are not attacker-triggered, so the usual "audit
     * joins caller tx" contract is correct.
     */
    public static final String TENANT_CACHE_INVALIDATED = "TENANT_CACHE_INVALIDATED";

    /**
     * Emitted by {@code TenantScopedCacheService.get} when the on-read tenant
     * verification (cached-value stamp mismatch) detects that the envelope's
     * stamped tenant does not match the reader's {@code TenantContext}.
     *
     * <p>Detail blob: {@code {cacheName, expectedTenant, observedTenant}}.
     * Actor: current user from {@code TenantContext.getUserId()}. Target: {@code null}.
     *
     * <p><b>Persisted via {@link DetachedAuditPersister}</b> with
     * {@code PROPAGATION_REQUIRES_NEW} — NOT the normal event-bus path. Rationale:
     * this is a security-evidence signal. An attacker who triggers a cross-tenant
     * read in a transactional endpoint and relies on the subsequent
     * {@code IllegalStateException} to roll the caller's transaction back must
     * NOT be able to erase the audit trail. REQUIRES_NEW commits the audit row
     * independently so it survives the caller's rollback. Marcus Webb warroom
     * lens, Phase C task 4.1 skeleton review (design-c D-C-9).
     */
    public static final String CROSS_TENANT_CACHE_READ = "CROSS_TENANT_CACHE_READ";

    /**
     * Emitted by {@code TenantScopedCacheService.get} when it encounters a
     * cache entry whose underlying value is not a {@code TenantScopedValue}
     * envelope. Indicates a caller wrote via raw {@code CacheService} (bypassing
     * the wrapper) — either a pre-migration call site not yet converted
     * (task 4.b) or a new call site slipping past the ArchUnit Family C rule.
     *
     * <p>Detail blob: {@code {cacheName, observedType}}. Value fragments are
     * NOT included (avoid leaking cached payload into audit rows).
     *
     * <p>Persisted via the event-bus path (not security-evidence; shouldn't happen
     * at all once task 4.b completes call-site migration).
     */
    public static final String MALFORMED_CACHE_ENTRY = "MALFORMED_CACHE_ENTRY";

    /**
     * Emitted by {@code EscalationPolicyService.findByTenantAndId} when the
     * on-read tenant verification detects that the stored policy's
     * {@code tenantId} does not match the caller's {@code TenantContext} AND
     * the policy is not a platform-default (null-tenant) row.
     *
     * <p>Detail blob: {@code {policyId, expectedTenant, observedTenant}}.
     * Actor: current user from {@code TenantContext.getUserId()}. Target: {@code null}.
     *
     * <p>Persisted via {@link DetachedAuditPersister} with
     * {@code PROPAGATION_REQUIRES_NEW} — mirrors {@link #CROSS_TENANT_CACHE_READ}
     * (task 4.1, design-c D-C-9). A cross-tenant read is security-evidence
     * and must survive attacker-triggered caller rollback. Phase C task 4.4
     * (EscalationPolicyService split) wires the first caller.
     */
    public static final String CROSS_TENANT_POLICY_READ = "CROSS_TENANT_POLICY_READ";

    private AuditEventTypes() {
        // utility class — do not instantiate
    }
}
