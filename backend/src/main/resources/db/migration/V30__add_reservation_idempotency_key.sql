-- Add idempotency key to reservation table for offline queue deduplication.
-- Nullable: requests without a key behave as before.
ALTER TABLE reservation ADD COLUMN idempotency_key VARCHAR(36);
