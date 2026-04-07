-- V34: Webhook delivery log and subscription consecutive failure tracking.
-- Stores recent delivery attempts for admin visibility.
-- consecutive_failures on subscription tracks auto-disable threshold.

CREATE TABLE webhook_delivery_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID NOT NULL REFERENCES subscription(id) ON DELETE CASCADE,
    event_type VARCHAR(100) NOT NULL,
    status_code INTEGER,
    response_time_ms INTEGER,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    attempt_number INTEGER NOT NULL DEFAULT 1,
    response_body TEXT
);

CREATE INDEX idx_delivery_log_subscription ON webhook_delivery_log (subscription_id, attempted_at DESC);

-- Track consecutive failures for auto-disable (5 failures → DEACTIVATED)
ALTER TABLE subscription ADD COLUMN consecutive_failures INTEGER NOT NULL DEFAULT 0;
