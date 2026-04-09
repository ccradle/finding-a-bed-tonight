-- V37: Partial index for referral token expiry job (Elena, NYC-scale readiness).
-- The expiry job runs every 60 seconds:
--   UPDATE referral_token SET status = 'EXPIRED'
--   WHERE status = 'PENDING' AND expires_at < NOW() RETURNING id
-- Without this index, the query scans ALL referral tokens (including terminal:
-- ACCEPTED, REJECTED, EXPIRED). At NYC scale (~1,400 pending + thousands of
-- terminal tokens waiting for 24h purge), this wastes I/O.
-- Partial index: only PENDING tokens are indexed.

CREATE INDEX idx_referral_token_pending_expires
    ON referral_token (status, expires_at)
    WHERE status = 'PENDING';
