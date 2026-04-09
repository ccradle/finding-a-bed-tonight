-- Email-based password reset tokens.
-- SHA-256 hashed (not BCrypt) — tokens are 256-bit high-entropy,
-- brute force is infeasible, fast hash enables O(1) DB lookup.
-- Separate from one_time_access_code (BCrypt, O(n) loop comparison).

CREATE TABLE password_reset_token (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    tenant_id   UUID NOT NULL REFERENCES tenant(id),
    token_hash  CHAR(64) NOT NULL,     -- SHA-256 hex is always exactly 64 chars
    expires_at  TIMESTAMPTZ NOT NULL,
    used        BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- O(1) lookup by SHA-256 hash during token validation
CREATE UNIQUE INDEX idx_password_reset_token_hash ON password_reset_token(token_hash);

-- Cleanup scheduler: purge expired/used tokens
CREATE INDEX idx_password_reset_token_expires ON password_reset_token(expires_at);

-- GRANTs: ALTER DEFAULT PRIVILEGES (V16) covers fabt_app automatically
