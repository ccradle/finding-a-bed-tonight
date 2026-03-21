CREATE TABLE surge_event (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenant(id),
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    reason          VARCHAR(500) NOT NULL,
    bounding_box    JSONB,
    activated_by    UUID NOT NULL REFERENCES app_user(id),
    activated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deactivated_at  TIMESTAMPTZ,
    deactivated_by  UUID REFERENCES app_user(id),
    scheduled_end   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_surge_status CHECK (status IN ('ACTIVE', 'DEACTIVATED', 'EXPIRED'))
);

CREATE INDEX idx_surge_event_tenant_status ON surge_event(tenant_id, status);
