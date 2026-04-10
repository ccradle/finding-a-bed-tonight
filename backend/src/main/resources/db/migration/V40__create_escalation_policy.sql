-- V40: Per-tenant escalation policy with append-only versioning (#82, coc-admin-escalation).
-- The escalation batch job currently uses hardcoded thresholds [1h, 2h, 3.5h, 4h].
-- Different CoCs have different operational rhythms (faith volunteers, hospital
-- discharge windows, 24/7 staffed shelters) — hardcoded thresholds train every
-- non-Wake-County partner to ignore CRITICAL alerts within 30 days. This table
-- moves the thresholds to configurable per-tenant policy with audit-pure
-- frozen-at-creation semantics (each referral records the policy version that
-- was active when it was created — see V41 + ReferralTokenService.create()).
--
-- APPEND-ONLY: each PATCH from the admin inserts a new row with version+1.
-- Existing rows are NEVER updated or deleted. The version column is the audit
-- trail for "what policy was active when?" — Casey Drummond's chain-of-custody
-- requirement. Cleanup of old policies is out of scope for MVP (~12 versions
-- per tenant per year × 100 tenants × 10 years = 12,000 rows, trivial).

CREATE TABLE escalation_policy (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID REFERENCES tenant(id),  -- NULL = platform default policy
    event_type   VARCHAR(64) NOT NULL,
    version      INTEGER NOT NULL,
    thresholds   JSONB NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- ON DELETE SET NULL: preserve the policy row's audit-trail integrity
    -- if the creating admin is later deleted. The actor identity is also
    -- captured in audit_events.actor_user_id at insert time, so losing
    -- this FK pointer doesn't lose chain-of-custody (Elena Vasquez's lens).
    created_by   UUID REFERENCES app_user(id) ON DELETE SET NULL,

    -- NULLS NOT DISTINCT (PG 15+) — without this, PostgreSQL would treat
    -- two rows with tenant_id IS NULL as non-conflicting (NULL ≠ NULL by
    -- default), allowing duplicate platform-default policies to coexist.
    -- That would silently corrupt the "find current policy" lookup, which
    -- uses ORDER BY version DESC LIMIT 1 — whichever row PostgreSQL returned
    -- first would win, non-deterministically. Elena Vasquez's catch in the
    -- Session 1 round-table review.
    UNIQUE NULLS NOT DISTINCT (tenant_id, event_type, version)
);

-- Used by ReferralTokenService.create() to find the *current* policy for a
-- tenant when snapshotting a new referral. Sorted DESC by version so the
-- service-layer LIMIT 1 query is index-only.
CREATE INDEX idx_escalation_policy_current
    ON escalation_policy (tenant_id, event_type, version DESC);

-- Used by the escalation batch job to look up a frozen policy by ID.
-- Primary key already covers this; no separate index needed.

-- RLS: read is unrestricted for fabt_app (admin reads via service-layer tenant
-- filter). Writes are trusted to the service layer (EscalationPolicyService
-- enforces caller is COC_ADMIN+ and uses caller's tenant_id from TenantContext).
-- This matches the V35 notification table pattern: simple unrestricted RLS,
-- service-layer authorization.
ALTER TABLE escalation_policy ENABLE ROW LEVEL SECURITY;

CREATE POLICY escalation_policy_read ON escalation_policy
    FOR SELECT
    USING (true);

CREATE POLICY escalation_policy_insert ON escalation_policy
    FOR INSERT
    WITH CHECK (true);

-- No UPDATE policy: the table is append-only by design. New versions are
-- INSERTed, never UPDATEd. Lack of policy + RLS enabled = UPDATE blocked.

-- No DELETE policy: same reason. Old policy versions are the audit trail and
-- must not be removed. Cleanup is out of scope for MVP.

-- Seed the platform default policy. tenant_id IS NULL means "every tenant
-- without a custom policy uses this." Thresholds match the existing hardcoded
-- values in ReferralEscalationJobConfig EXACTLY so existing tenants see zero
-- behavior change after the policy refactor (Session 2 of the implementation
-- plan). When a tenant customizes via PATCH, the service layer inserts a new
-- row with their tenant_id and version=1.
--
-- Threshold semantics (all ISO 8601 durations from referral creation):
--   PT1H     — 1 hour:    coordinator reminder (ACTION_REQUIRED)
--   PT2H     — 2 hours:   CoC admin escalation (CRITICAL)
--   PT3H30M  — 3.5 hours: all-hands (CRITICAL to coordinators + outreach)
--   PT4H     — 4 hours:   final expiry warning (INFO to outreach worker)
INSERT INTO escalation_policy (tenant_id, event_type, version, thresholds, created_by)
VALUES (
    NULL,
    'dv-referral',
    1,
    '[
      {"at":"PT1H",   "severity":"ACTION_REQUIRED","recipients":["COORDINATOR"]},
      {"at":"PT2H",   "severity":"CRITICAL",       "recipients":["COC_ADMIN"]},
      {"at":"PT3H30M","severity":"CRITICAL",       "recipients":["COORDINATOR","OUTREACH_WORKER"]},
      {"at":"PT4H",   "severity":"INFO",           "recipients":["OUTREACH_WORKER"]}
    ]'::jsonb,
    NULL  -- system-seeded, no human actor
);
