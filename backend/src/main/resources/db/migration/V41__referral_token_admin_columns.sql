-- V41: Add admin escalation columns to referral_token (#82, coc-admin-escalation).
--
-- Three new nullable columns:
--
-- 1. escalation_policy_id — FK to escalation_policy.id (V40). Records the
--    policy version that was active when the referral was created. The
--    escalation batch job uses THIS column (not the current tenant policy)
--    to determine which thresholds apply. Mid-day policy changes only affect
--    new referrals — Casey Drummond's chain-of-custody requirement.
--    NULL on existing rows: the batch job falls back to the platform default
--    policy (escalation_policy WHERE tenant_id IS NULL AND event_type='dv-referral')
--    so backwards compatibility is preserved with zero behavior change.
--
-- 2. claimed_by_admin_id — FK to app_user.id. Set when a CoC admin claims a
--    pending referral via POST /api/v1/dv-referrals/{id}/claim. Soft-lock,
--    NOT a hard lock — the claim is advisory. Other admins can override by
--    sending an Override-Claim: true header. PagerDuty acknowledge pattern.
--
-- 3. claim_expires_at — when the soft-lock auto-releases. Default 10 minutes
--    from claim time, configurable via fabt.dv-referral.claim-duration-minutes.
--    A @Scheduled job runs every 60s (configurable) finding rows where
--    claim_expires_at < NOW() AND claimed_by_admin_id IS NOT NULL, clearing
--    both columns and writing a DV_REFERRAL_RELEASED audit event with
--    actor_user_id = system.

ALTER TABLE referral_token
    ADD COLUMN escalation_policy_id UUID REFERENCES escalation_policy(id),
    ADD COLUMN claimed_by_admin_id  UUID REFERENCES app_user(id),
    ADD COLUMN claim_expires_at     TIMESTAMPTZ;

-- Partial index for the auto-release scheduler. Only rows with an active
-- claim are indexed (most rows have both columns NULL — never indexed).
-- Query pattern: SELECT id FROM referral_token
--                WHERE claimed_by_admin_id IS NOT NULL
--                  AND claim_expires_at < NOW()
CREATE INDEX idx_referral_token_active_claim
    ON referral_token (claim_expires_at)
    WHERE claimed_by_admin_id IS NOT NULL;

-- Partial index for "find pending referrals across tenant for the admin queue
-- view." Only PENDING referrals are indexed. The admin queue endpoint sorts
-- by expires_at ASC (most-urgent first); the index supports this directly.
CREATE INDEX idx_referral_token_pending_by_expiry
    ON referral_token (tenant_id, expires_at)
    WHERE status = 'PENDING';
