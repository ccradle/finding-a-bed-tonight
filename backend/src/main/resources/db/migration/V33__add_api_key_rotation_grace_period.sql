-- V33: Add grace period support for API key rotation.
-- During rotation, the old key hash is preserved with an expiry timestamp.
-- Both old and new keys authenticate during the grace window.
-- last_used_at already exists (V3).

ALTER TABLE api_key ADD COLUMN old_key_hash VARCHAR(255);
ALTER TABLE api_key ADD COLUMN old_key_expires_at TIMESTAMPTZ;

-- Index for grace period key lookup (old key validation)
CREATE INDEX idx_api_key_old_hash ON api_key (old_key_hash) WHERE old_key_hash IS NOT NULL;
