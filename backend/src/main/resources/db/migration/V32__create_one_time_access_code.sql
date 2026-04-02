-- One-time access codes for password recovery.
-- Admin generates a code for a locked-out worker.
-- Code is stored hashed (bcrypt), expires in 15 minutes, single-use.

CREATE TABLE one_time_access_code (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    tenant_id   UUID NOT NULL REFERENCES tenant(id),
    code_hash   VARCHAR(255) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used        BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by  UUID NOT NULL REFERENCES app_user(id)
);

CREATE INDEX idx_ott_user_id ON one_time_access_code(user_id);
CREATE INDEX idx_ott_expires ON one_time_access_code(expires_at);

-- Grant to fabt_app role
GRANT SELECT, INSERT, UPDATE, DELETE ON one_time_access_code TO fabt_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO fabt_app;
