-- V42: audit_events.actor_user_id nullable for system actors (#82, coc-admin-escalation).
--
-- The original V29 schema declared actor_user_id NOT NULL because every audit
-- row at that time was triggered by a human admin acting via the web UI.
--
-- coc-admin-escalation introduces a new audit producer: the @Scheduled
-- auto-release task in ReferralTokenService.autoReleaseClaims(). When a CoC
-- admin's soft-lock claim expires without manual release, the system itself
-- clears the claim and writes a DV_REFERRAL_RELEASED audit row with
-- {"reason": "timeout"}. There is no human actor — the actor IS the platform.
--
-- The previous shape (NOT NULL) caused these system audit rows to fail the
-- INSERT and be silently swallowed by AuditEventService.onAuditEvent's
-- try/catch. Casey Drummond's chain-of-custody requirement is that EVERY
-- claim transition is recorded — silent loss of timeout-release rows breaks
-- that guarantee.
--
-- Reads must treat NULL actor_user_id as "system" (e.g. display "System
-- (auto-release)" in the admin audit log UI). The application layer is
-- responsible for that mapping; the schema just allows the value.

ALTER TABLE audit_events
    ALTER COLUMN actor_user_id DROP NOT NULL;
