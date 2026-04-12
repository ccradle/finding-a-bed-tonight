-- V49: referral_token.escalation_chain_broken — admin manual takeover marker
-- (#82, coc-admin-escalation, Session 4).
-- Renumbered from V43 on 2026-04-11 during the post-v0.34.0 rebase (see V46 header).
--
-- Set TRUE when a CoC admin reassigns a referral via SPECIFIC_USER. Semantically:
-- "an admin took manual responsibility for getting this referral to a human;
-- the system should not auto-escalate further." The escalation batch tasklet
-- skips referrals where this column is TRUE.
--
-- Why a column instead of repurposing escalation_policy_id IS NULL: setting
-- the policy id to NULL falls back to the platform default, which still
-- escalates. We need an EXPLICIT "escalation paused by admin" signal that
-- the batch job can branch on.
--
-- COORDINATOR_GROUP / COC_ADMIN_GROUP reassigns leave this column FALSE — they
-- "page the group again" but escalation continues normally because no single
-- person took ownership. SPECIFIC_USER is the only path that sets it.
--
-- Default FALSE: existing rows are not affected; new rows behave the same as
-- pre-V49 unless an admin explicitly reassigns to a SPECIFIC_USER.

ALTER TABLE referral_token
    ADD COLUMN escalation_chain_broken BOOLEAN NOT NULL DEFAULT FALSE;
