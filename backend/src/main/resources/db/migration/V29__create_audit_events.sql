-- V29: Audit events table for admin action tracking.
-- Records who changed what, when, and from where.
-- Retained indefinitely (security audit trail, not subject to GDPR erasure).

CREATE TABLE audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    timestamp TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    actor_user_id UUID NOT NULL,
    target_user_id UUID,
    action VARCHAR(50) NOT NULL,
    details JSONB,
    ip_address VARCHAR(45)
);

-- BRIN index on timestamp for efficient range queries on append-only data
CREATE INDEX idx_audit_events_timestamp ON audit_events USING BRIN (timestamp);

-- Index for querying audit events by target user
CREATE INDEX idx_audit_events_target_user ON audit_events (target_user_id);

-- Grant to fabt_app role
GRANT SELECT, INSERT ON audit_events TO fabt_app;
