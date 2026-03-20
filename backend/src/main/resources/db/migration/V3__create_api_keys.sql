CREATE TABLE api_key (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL REFERENCES tenant(id),
    shelter_id   UUID,
    key_hash     VARCHAR(255) NOT NULL,
    key_suffix   CHAR(4) NOT NULL,
    label        VARCHAR(255),
    role         VARCHAR(50) NOT NULL,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ
);

CREATE INDEX idx_api_key_hash ON api_key(key_hash);
CREATE INDEX idx_api_key_tenant_id ON api_key(tenant_id);
