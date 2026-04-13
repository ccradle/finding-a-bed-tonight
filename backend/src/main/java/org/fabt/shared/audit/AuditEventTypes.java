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

    private AuditEventTypes() {
        // utility class — do not instantiate
    }
}
