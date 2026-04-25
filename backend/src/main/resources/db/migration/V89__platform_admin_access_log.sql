-- V89 — Phase G slice G-4.3: platform_admin_access_log (PAL).
--
-- Append-only structured admin record for every method tagged
-- @PlatformAdminOnly. Per design Decision 6 (audit chain double-write),
-- PAL plays a complementary role to audit_events:
--
--   * platform_admin_access_log: structured platform-admin record —
--     justification (required, length >= 10), endpoint, request body
--     fingerprint, before/after state, who+when. Append-only via REVOKE
--     UPDATE/DELETE + a defense-in-depth BEFORE-mutate trigger (warroom D4).
--
--   * audit_events: universal audit log; cross-tenant queries; chain-walk
--     forensics; OCI anchor coverage. The G-4.3 AOP aspect double-writes
--     to BOTH tables in a single REQUIRES_NEW transaction with pre-
--     generated UUIDs (Decision 11) so PAL.audit_event_id == AE.id and
--     AE.details->>'platform_admin_access_log_id' == PAL.id.
--
-- Schema decisions encoded in this migration (warroom 2026-04-25):
--
--   D1 — NO FK on audit_event_id. Theoretical orphan risk accepted (AE
--        deletes are forbidden by Phase B append-only). Revisit as F6 if
--        a compliance audit flags it.
--
--   D2 — request_body_excerpt holds Content-Type + Content-Length +
--        SHA-256(body), NEVER raw body content. The aspect stores the
--        fingerprint; forensic readers correlate against application
--        logs (which already redact sensitive fields per Phase A).
--
--   D4 — Append-only trigger function platform_admin_access_log_no_mutate()
--        raises on any UPDATE or DELETE in addition to the REVOKE.
--        Belt-and-suspenders against future GRANT regressions.
--
--   D7 — Size CHECK constraints on TEXT/JSONB columns to prevent log
--        bloat from a misbehaving caller (large request bodies, oversized
--        before/after-state snapshots).

CREATE TABLE platform_admin_access_log (
    id                    UUID         PRIMARY KEY,
    -- The platform_user that triggered the action. FK enforced even though
    -- platform_user has REVOKE ALL from fabt_app — PostgreSQL FK validation
    -- runs as the constraint trigger, not the calling role.
    platform_user_id      UUID         NOT NULL REFERENCES platform_user(id),
    -- AuditEventType.name() of the action (e.g. PLATFORM_TENANT_SUSPENDED).
    action                TEXT         NOT NULL,
    -- Free-form action target (e.g. "tenant", "platform_user", "batch_job").
    -- Distinct from resource_id which is the UUID where applicable.
    resource              TEXT,
    resource_id           UUID,
    -- Operator-supplied justification from X-Platform-Justification header.
    -- Spec line 248-256 / Decision 10: documentation-only, NOT server-validated
    -- authority. The CHECK enforces the minimum length so the field is
    -- meaningfully populated for compliance review.
    justification         TEXT         NOT NULL
                                       CHECK (length(trim(justification)) >= 10),
    request_method        TEXT         NOT NULL,
    request_path          TEXT         NOT NULL,
    -- Body fingerprint, NEVER raw content (D2). Format:
    -- "Content-Type=<ct>;Content-Length=<n>;SHA-256=<hex>".
    -- Bound to 2000 chars (D7) — the longest legitimate fingerprint is
    -- well under that; anything longer indicates a misbehaving caller.
    request_body_excerpt  TEXT
                          CHECK (request_body_excerpt IS NULL
                                 OR length(request_body_excerpt) <= 2000),
    -- Sanitized snapshots (D7 size cap). Aspect populates from an explicit
    -- per-action allowlist (P2) — NEVER credentials / OAuth2 secrets / API keys.
    before_state          JSONB
                          CHECK (before_state IS NULL
                                 OR pg_column_size(before_state) <= 65536),
    after_state           JSONB
                          CHECK (after_state IS NULL
                                 OR pg_column_size(after_state) <= 65536),
    -- Pre-generated UUID of the companion audit_events row (Decision 11).
    -- NO FK constraint (D1) — keeps the aspect's REQUIRES_NEW insert order
    -- simple. AE deletes are forbidden so orphan risk is theoretical.
    audit_event_id        UUID,
    timestamp             TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE platform_admin_access_log IS
    'Phase G-4.3 platform-admin audit ledger. Append-only via REVOKE UPDATE/DELETE + BEFORE-mutate trigger. Written by the @PlatformAdminOnly AOP aspect alongside an audit_events row in a single REQUIRES_NEW transaction. Compliance queries: see docs/runbook.md. WARNING: an audit row indicates the action was ATTEMPTED, not necessarily completed — for actual completion correlate via audit_event_id against application logs at the same correlation window.';

COMMENT ON COLUMN platform_admin_access_log.request_body_excerpt IS
    'Body fingerprint (Content-Type + Content-Length + SHA-256), NEVER raw body. Forensic correlation against application logs at the same audit_event_id.';

COMMENT ON COLUMN platform_admin_access_log.audit_event_id IS
    'Pre-generated id of the companion audit_events row (Decision 11). No FK enforcement (D1) — Phase B forbids audit_events deletes so orphan risk is hypothetical.';

-- ---------------------------------------------------------------------------
-- Indexes (warroom-vetted: 4 indexes for the canonical query patterns)
-- ---------------------------------------------------------------------------

-- "show me every action by operator X, most recent first" — primary forensics.
CREATE INDEX platform_admin_access_log_user_time
    ON platform_admin_access_log (platform_user_id, timestamp DESC);

-- "show me every platform action in the last 24h, most recent first" —
-- BRIN sweep query equivalent; btree on TIMESTAMPTZ DESC is fine at our scale.
CREATE INDEX platform_admin_access_log_time
    ON platform_admin_access_log (timestamp DESC);

-- "show me every action that touched resource X" — partial because most
-- platform-wide actions have NULL resource_id.
CREATE INDEX platform_admin_access_log_resource
    ON platform_admin_access_log (resource_id)
    WHERE resource_id IS NOT NULL;

-- "show me every PLATFORM_TENANT_HARD_DELETED in 2026" — Elena's compliance-
-- query optimization. Without this index, action-filtered queries are seq
-- scans; with it, they're index range scans.
CREATE INDEX platform_admin_access_log_action_time
    ON platform_admin_access_log (action, timestamp DESC);

-- ---------------------------------------------------------------------------
-- Append-only enforcement (warroom D4 — defense-in-depth)
-- ---------------------------------------------------------------------------
-- REVOKE alone is the canonical Phase B append-only mechanism, but a
-- mistaken future GRANT regression (e.g. someone GRANTs ALL) would silently
-- re-enable mutation. The trigger function raises on any UPDATE or DELETE
-- regardless of role privileges. Belt-and-suspenders.
--
-- Trigger fires on every row touched by an UPDATE/DELETE statement; for
-- the (rare) bulk-delete attack vector this is correct behavior — every
-- row signals the violation rather than letting a partial mutate slip.

CREATE OR REPLACE FUNCTION platform_admin_access_log_no_mutate()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'platform_admin_access_log is append-only — UPDATE/DELETE forbidden (Phase G-4.3 D4)';
END;
$$;

CREATE TRIGGER platform_admin_access_log_no_mutate_trigger
    BEFORE UPDATE OR DELETE ON platform_admin_access_log
    FOR EACH ROW
    EXECUTE FUNCTION platform_admin_access_log_no_mutate();

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------
-- fabt_app needs SELECT (admin-review endpoints) + INSERT (the aspect writes
-- as fabt_app). UPDATE / DELETE / TRUNCATE are revoked; the trigger above
-- additionally raises if anyone with elevated privilege tries to mutate.

REVOKE ALL ON platform_admin_access_log FROM fabt_app;
GRANT SELECT, INSERT ON platform_admin_access_log TO fabt_app;

-- The trigger function executes with the privileges of its caller (NOT
-- SECURITY DEFINER) — the RAISE EXCEPTION fires inside the calling role's
-- transaction, which is the desired semantics.
