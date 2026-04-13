-- V51: Add snapshotted shelter name to referral tokens for offline/list visibility
-- Elena Vasquez (Security Architect) review: operational data only, no PII.
-- Darius Kessler (Outreach Worker) lens: ensures list distinguishability.

ALTER TABLE referral_token ADD COLUMN shelter_name VARCHAR(255);

-- Backfill existing tokens with the current shelter name. Idempotent
-- (WHERE shelter_name IS NULL guard). War room review 2026-04-12:
-- Elena Vasquez overruled the original "avoid backfilling" note —
-- in-flight referrals showing "Unknown shelter" on a live system is
-- worse than a one-time UPDATE. The 24h purge cycle means this
-- backfill touches at most a few hundred rows.
UPDATE referral_token rt
SET shelter_name = s.name
FROM shelter s
WHERE rt.shelter_id = s.id
  AND rt.shelter_name IS NULL;
