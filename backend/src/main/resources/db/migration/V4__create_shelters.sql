CREATE TABLE shelter (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES tenant(id),
    name           VARCHAR(255) NOT NULL,
    address_street VARCHAR(255),
    address_city   VARCHAR(100),
    address_state  VARCHAR(50),
    address_zip    VARCHAR(20),
    phone          VARCHAR(50),
    latitude       DOUBLE PRECISION,
    longitude      DOUBLE PRECISION,
    dv_shelter     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_shelter_tenant_dv ON shelter(tenant_id, dv_shelter);
CREATE INDEX idx_shelter_tenant_name ON shelter(tenant_id, name);

-- Add deferred FK from api_key to shelter
ALTER TABLE api_key
    ADD CONSTRAINT fk_api_key_shelter
    FOREIGN KEY (shelter_id) REFERENCES shelter(id);
