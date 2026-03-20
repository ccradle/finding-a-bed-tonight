CREATE TABLE tenant_oauth2_provider (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID NOT NULL REFERENCES tenant(id),
    provider_name          VARCHAR(100) NOT NULL,
    client_id              VARCHAR(500) NOT NULL,
    client_secret_encrypted VARCHAR(1000) NOT NULL,
    issuer_uri             VARCHAR(500),
    enabled                BOOLEAN NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, provider_name)
);

CREATE TABLE user_oauth2_link (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    provider_name       VARCHAR(100) NOT NULL,
    external_subject_id VARCHAR(500) NOT NULL,
    linked_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (provider_name, external_subject_id)
);

CREATE INDEX idx_user_oauth2_link_user ON user_oauth2_link(user_id);
