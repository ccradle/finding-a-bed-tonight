CREATE TABLE subscription (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL REFERENCES tenant(id),
    event_type           VARCHAR(100) NOT NULL,
    filter               JSONB NOT NULL DEFAULT '{}',
    callback_url         VARCHAR(1000) NOT NULL,
    callback_secret_hash VARCHAR(255) NOT NULL,
    status               VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    expires_at           TIMESTAMPTZ,
    last_error           TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_subscription_tenant_status ON subscription(tenant_id, status);
CREATE INDEX idx_subscription_event_type ON subscription(event_type, status);
