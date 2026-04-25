-- V87 — Phase G slice G-4.1: platform_user identity model + COC_ADMIN backfill.
--
-- Schema-only foundation for the platform-admin split (issue #141 + Phase G-4
-- per the platform-admin-split-and-access-log OpenSpec change). This
-- migration creates THREE new tables and applies a backfill UPDATE on
-- app_user; it does NOT change any @PreAuthorize gating, JWT shape, or
-- application behavior. The role split + audited unseal channel ship in
-- subsequent slices (G-4.2 through G-4.5).
--
-- Tables created:
--   * platform_user             — operator identity, no tenant_id
--   * platform_user_backup_code — SHA-256 hashed recovery codes
--   * platform_key_material     — JWT signing key for "iss=fabt-platform"
--
-- Bootstrap row inserted at well-known UUID 0fab — locked, no credentials,
-- activated by operator post-deploy via fabt-cli + psql UPDATE (G-4.2 ships
-- the CLI tool; runbook documents activation flow).
--
-- COC_ADMIN backfill ensures existing PLATFORM_ADMIN-bearing app_user rows
-- retain tenant-scoped permissions through the deprecation window. Same
-- UPDATE bumps token_version so existing PLATFORM_ADMIN-bearing JWTs are
-- invalidated at deploy time (closes the "stolen pre-v0.53 JWT retains
-- access" window — design Decision 16).
--
-- RLS posture: platform_user + platform_user_backup_code are
-- REVOKEd from fabt_app entirely; access only via SECURITY DEFINER
-- functions owned by fabt (mirrors Phase G-1 chain-head pattern).
-- platform_key_material is readable by fabt_app (the JWT decoder needs the
-- key bytes at request time) but NOT writable.

-- ---------------------------------------------------------------------------
-- 1. platform_user
-- ---------------------------------------------------------------------------

CREATE TABLE platform_user (
    id              UUID        PRIMARY KEY,
    -- Operator email; nullable so the bootstrap row can be inserted with no
    -- credentials. Once activated, email is set and account_locked flips false.
    -- anonymized_at supports the GDPR Art-17 path (Phase H+ tooling): rows
    -- are anonymized in place rather than DELETEd to preserve audit FKs.
    email           TEXT,
    password_hash   TEXT,
    mfa_secret      TEXT,
    mfa_enabled     BOOLEAN     NOT NULL DEFAULT false,
    account_locked  BOOLEAN     NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_login_at   TIMESTAMPTZ,
    anonymized_at   TIMESTAMPTZ
);

COMMENT ON TABLE platform_user IS
    'Platform operator identity (Phase G-4 / issue #141). Distinct from app_user — no tenant_id. JWTs issued from this table use iss=fabt-platform signed by platform_key_material.';

-- Email uniqueness when present. NULL bootstrap row + future anonymized rows
-- bypass the unique check via the WHERE predicate.
CREATE UNIQUE INDEX platform_user_email_unique
    ON platform_user (email)
    WHERE email IS NOT NULL AND anonymized_at IS NULL;

-- Bootstrap row at well-known UUID. Operator activates via:
--   1. java -jar fabt-cli.jar hash-password   (G-4.2 deliverable)
--   2. UPDATE platform_user SET email = '<your-email>',
--             password_hash = '$2a$12$...', account_locked = false
--      WHERE id = '00000000-0000-0000-0000-000000000fab';
--   3. Login at /auth/platform/login + complete forced MFA-on-first-login.
INSERT INTO platform_user (id, email, password_hash, account_locked)
VALUES ('00000000-0000-0000-0000-000000000fab', NULL, NULL, true);

-- ---------------------------------------------------------------------------
-- 2. platform_user_backup_code
-- ---------------------------------------------------------------------------

CREATE TABLE platform_user_backup_code (
    id                UUID        PRIMARY KEY,
    platform_user_id  UUID        NOT NULL REFERENCES platform_user(id) ON DELETE CASCADE,
    -- SHA-256 + per-row salt (NOT bcrypt — design Decision 12). Backup codes
    -- are random short strings used at most once each; bcrypt's slow-compare
    -- adds ~150ms/recovery-attempt with no security benefit (no brute-force
    -- exposure on random codes).
    code_hash         TEXT        NOT NULL,
    code_salt         BYTEA       NOT NULL,
    used_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE platform_user_backup_code IS
    'Single-use SHA-256+salt-hashed MFA recovery codes for platform_user. 10 codes generated at MFA setup, displayed once, marked used_at on use. Regeneration via Phase H+ endpoint deletes and re-creates the row set.';

CREATE INDEX platform_user_backup_code_owner ON platform_user_backup_code (platform_user_id);

-- ---------------------------------------------------------------------------
-- 3. platform_key_material
-- ---------------------------------------------------------------------------

CREATE TABLE platform_key_material (
    id          UUID        PRIMARY KEY,
    generation  INTEGER     NOT NULL,
    -- Random kid (UUID hex form). The JwtDecoder dispatches by iss claim:
    -- iss=fabt-tenant → resolve via jwt_key_generation; iss=fabt-platform →
    -- resolve via this table. Kids in this table are not in jwt_key_generation
    -- and vice versa.
    kid         TEXT        NOT NULL UNIQUE,
    -- HKDF-derived from master KEK at first boot if no active row exists.
    -- NOT the master KEK directly (defense-in-depth — same pattern as per-tenant DEKs).
    key_bytes   BYTEA       NOT NULL,
    active      BOOLEAN     NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
    -- Future rotation tooling will INSERT new active=true rows + flip old to
    -- inactive; v0.53 ships single-generation. Manual break-glass rotation
    -- procedure documented in docs/runbook.md (G-4.2 task).
    -- Single-active enforcement via partial UNIQUE index below — avoids
    -- the btree_gist extension dependency that EXCLUDE (active WITH =) requires.
);

-- One-active-row guard. The unique index over a constant expression with
-- a WHERE predicate allows at most ONE row matching `active = true` at any
-- time — second insert with active=true raises a unique_violation. Inactive
-- rows (active=false) are unbounded.
CREATE UNIQUE INDEX platform_key_material_one_active
    ON platform_key_material ((true))
    WHERE active = true;

COMMENT ON TABLE platform_key_material IS
    'JWT signing key for iss=fabt-platform tokens (Phase G-4 / issue #141). One active row at a time enforced by EXCLUDE constraint. Initial row populated by PlatformKeyRotationService on first boot via HKDF from master KEK.';

CREATE INDEX platform_key_material_active_kid
    ON platform_key_material (kid)
    WHERE active = true;

-- ---------------------------------------------------------------------------
-- 4. RLS / privilege posture
-- ---------------------------------------------------------------------------
-- platform_user + platform_user_backup_code: REVOKE all access from fabt_app.
-- Application accesses these via SECURITY DEFINER functions below.
-- Rationale: even SQL-injection in non-platform code paths cannot exfiltrate
-- platform credentials.

REVOKE ALL ON platform_user FROM fabt_app;
REVOKE ALL ON platform_user_backup_code FROM fabt_app;

-- platform_key_material: SELECT only. The JwtDecoder needs to read key_bytes
-- at every request; making this go through a SECURITY DEFINER function would
-- add overhead. Writes are restricted to the owner role (fabt) via Flyway +
-- the future PlatformKeyRotationService bootstrap path.
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON platform_key_material FROM fabt_app;
GRANT SELECT ON platform_key_material TO fabt_app;

-- ---------------------------------------------------------------------------
-- 5. SECURITY DEFINER functions for fabt_app to access platform_user
-- ---------------------------------------------------------------------------
-- All functions inherit ownership from the migration-running role (fabt in
-- prod, fabt_test in test). The migration runner IS the table owner in
-- both environments, so SECURITY DEFINER functions execute with table-owner
-- privileges and bypass the REVOKE on platform_user — same pattern as the
-- V82 tenant_dek_shred_guard trigger function. SET search_path = pg_catalog
-- prevents search-path-injection (PostgreSQL hardening guidance).
-- (Elena's correctness check satisfied implicitly: the function owner has
-- table access by virtue of also being the table creator.)

CREATE OR REPLACE FUNCTION platform_user_lookup_by_email(p_email TEXT)
RETURNS TABLE (
    id              UUID,
    email           TEXT,
    password_hash   TEXT,
    mfa_secret      TEXT,
    mfa_enabled     BOOLEAN,
    account_locked  BOOLEAN
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    RETURN QUERY
        SELECT pu.id, pu.email, pu.password_hash, pu.mfa_secret,
               pu.mfa_enabled, pu.account_locked
          FROM public.platform_user pu
         WHERE pu.email = p_email
           AND pu.anonymized_at IS NULL;
END;
$$;
GRANT EXECUTE ON FUNCTION platform_user_lookup_by_email(TEXT) TO fabt_app;


CREATE OR REPLACE FUNCTION platform_user_lookup_by_id(p_id UUID)
RETURNS TABLE (
    id              UUID,
    email           TEXT,
    password_hash   TEXT,
    mfa_secret      TEXT,
    mfa_enabled     BOOLEAN,
    account_locked  BOOLEAN
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    RETURN QUERY
        SELECT pu.id, pu.email, pu.password_hash, pu.mfa_secret,
               pu.mfa_enabled, pu.account_locked
          FROM public.platform_user pu
         WHERE pu.id = p_id
           AND pu.anonymized_at IS NULL;
END;
$$;
GRANT EXECUTE ON FUNCTION platform_user_lookup_by_id(UUID) TO fabt_app;


CREATE OR REPLACE FUNCTION platform_user_update_credentials(
    p_id            UUID,
    p_password_hash TEXT,
    p_mfa_secret    TEXT,
    p_mfa_enabled   BOOLEAN,
    p_account_locked BOOLEAN
) RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    UPDATE public.platform_user
       SET password_hash  = COALESCE(p_password_hash, password_hash),
           mfa_secret     = COALESCE(p_mfa_secret, mfa_secret),
           mfa_enabled    = COALESCE(p_mfa_enabled, mfa_enabled),
           account_locked = COALESCE(p_account_locked, account_locked)
     WHERE id = p_id
       AND anonymized_at IS NULL;
    RETURN FOUND;
END;
$$;
GRANT EXECUTE ON FUNCTION platform_user_update_credentials(UUID, TEXT, TEXT, BOOLEAN, BOOLEAN) TO fabt_app;


CREATE OR REPLACE FUNCTION platform_user_record_login(p_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    UPDATE public.platform_user
       SET last_login_at = NOW()
     WHERE id = p_id
       AND anonymized_at IS NULL;
END;
$$;
GRANT EXECUTE ON FUNCTION platform_user_record_login(UUID) TO fabt_app;


CREATE OR REPLACE FUNCTION platform_user_backup_codes_for(p_user_id UUID)
RETURNS TABLE (
    id        UUID,
    code_hash TEXT,
    code_salt BYTEA,
    used_at   TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    RETURN QUERY
        SELECT bc.id, bc.code_hash, bc.code_salt, bc.used_at
          FROM public.platform_user_backup_code bc
         WHERE bc.platform_user_id = p_user_id
           AND bc.used_at IS NULL;
END;
$$;
GRANT EXECUTE ON FUNCTION platform_user_backup_codes_for(UUID) TO fabt_app;


CREATE OR REPLACE FUNCTION platform_user_mark_backup_code_used(p_code_id UUID)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    UPDATE public.platform_user_backup_code
       SET used_at = NOW()
     WHERE id = p_code_id
       AND used_at IS NULL;
    RETURN FOUND;
END;
$$;
GRANT EXECUTE ON FUNCTION platform_user_mark_backup_code_used(UUID) TO fabt_app;


CREATE OR REPLACE FUNCTION platform_user_insert_backup_codes(
    p_user_id  UUID,
    p_ids      UUID[],
    p_hashes   TEXT[],
    p_salts    BYTEA[]
) RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    v_inserted INTEGER := 0;
    v_i        INTEGER;
BEGIN
    IF array_length(p_ids, 1) IS DISTINCT FROM array_length(p_hashes, 1)
       OR array_length(p_ids, 1) IS DISTINCT FROM array_length(p_salts, 1) THEN
        RAISE EXCEPTION 'platform_user_insert_backup_codes: array lengths must match';
    END IF;

    -- Replace any existing un-used codes (regeneration semantics)
    DELETE FROM public.platform_user_backup_code
     WHERE platform_user_id = p_user_id;

    FOR v_i IN 1..coalesce(array_length(p_ids, 1), 0) LOOP
        INSERT INTO public.platform_user_backup_code
            (id, platform_user_id, code_hash, code_salt)
        VALUES (p_ids[v_i], p_user_id, p_hashes[v_i], p_salts[v_i]);
        v_inserted := v_inserted + 1;
    END LOOP;
    RETURN v_inserted;
END;
$$;
GRANT EXECUTE ON FUNCTION platform_user_insert_backup_codes(UUID, UUID[], TEXT[], BYTEA[]) TO fabt_app;


-- platform_key_material write helper. fabt_app cannot INSERT directly (REVOKE
-- INSERT/UPDATE/DELETE/TRUNCATE above) — the future PlatformKeyRotationService
-- (G-4.2) calls this function on first boot to create the initial active key
-- row. Idempotent via the partial UNIQUE index: a second concurrent caller
-- would either succeed (if the first hadn't committed yet) and trigger a
-- unique_violation on the constraint, or get RAISE-skipped if the row is
-- already there. Service code handles the race via SELECT-then-INSERT.
CREATE OR REPLACE FUNCTION platform_key_material_create_first_active(
    p_id        UUID,
    p_kid       TEXT,
    p_key_bytes BYTEA
) RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    -- Refuse if any active row already exists; caller is expected to check
    -- existence first via SELECT, but defense-in-depth here too.
    IF EXISTS (SELECT 1 FROM public.platform_key_material WHERE active = true) THEN
        RETURN false;
    END IF;
    INSERT INTO public.platform_key_material (id, generation, kid, key_bytes, active)
    VALUES (p_id, 1, p_kid, p_key_bytes, true);
    RETURN true;
END;
$$;
GRANT EXECUTE ON FUNCTION platform_key_material_create_first_active(UUID, TEXT, BYTEA) TO fabt_app;

-- ---------------------------------------------------------------------------
-- 5b. Function ownership transfer (prod only)
-- ---------------------------------------------------------------------------
-- In prod, Flyway runs as `fabt` (the table owner) so functions are owned by
-- `fabt` automatically — SECURITY DEFINER then executes with table-owner
-- privileges, bypassing the REVOKE on platform_user. In test, the
-- Testcontainers postgres uses `fabt_test` as both owner and Flyway runner,
-- so the same property holds incidentally. The defensive transfer below
-- preserves Elena's design intent (functions explicitly owned by `fabt`)
-- WITHOUT breaking the test environment where `fabt` doesn't exist.
-- Idempotent — re-running V87 in any environment is a no-op for ownership.

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'fabt') THEN
        ALTER FUNCTION platform_user_lookup_by_email(TEXT) OWNER TO fabt;
        ALTER FUNCTION platform_user_lookup_by_id(UUID) OWNER TO fabt;
        ALTER FUNCTION platform_user_update_credentials(UUID, TEXT, TEXT, BOOLEAN, BOOLEAN) OWNER TO fabt;
        ALTER FUNCTION platform_user_record_login(UUID) OWNER TO fabt;
        ALTER FUNCTION platform_user_backup_codes_for(UUID) OWNER TO fabt;
        ALTER FUNCTION platform_user_mark_backup_code_used(UUID) OWNER TO fabt;
        ALTER FUNCTION platform_user_insert_backup_codes(UUID, UUID[], TEXT[], BYTEA[]) OWNER TO fabt;
        ALTER FUNCTION platform_key_material_create_first_active(UUID, TEXT, BYTEA) OWNER TO fabt;
    END IF;
END
$$;

-- ---------------------------------------------------------------------------
-- 6. COC_ADMIN backfill + token_version bump
-- ---------------------------------------------------------------------------
-- Two effects in a single UPDATE:
--   (a) Append COC_ADMIN to the roles array of every PLATFORM_ADMIN-bearing
--       row that doesn't already have it. Preserves tenant-scoped permissions
--       through the deprecation window — existing tenant admins keep working
--       after Role.PLATFORM_ADMIN is @Deprecated and the 11 tenant-scoped
--       @PreAuthorize sites move to hasRole('COC_ADMIN') in G-4.4.
--   (b) Increment token_version. Every existing JWT encodes a `ver` claim;
--       the JwtFilter rejects on mismatch. Bumping invalidates all existing
--       sessions — closes the "stolen pre-v0.53 PLATFORM_ADMIN JWT retains
--       access during deprecation window" gap (design Decision 16). Cost:
--       every active admin re-logs in once at deploy.
--
-- Lock-contention safety: at current scale (3 tenants, ~12 admin rows) this
-- is one statement holding row locks for milliseconds. Document for future
-- 10K+ admin scale: re-implement as batched DO-block with LIMIT N.
--
-- RLS interaction note: app_user has FORCE RLS enabled (Phase B V69). This
-- UPDATE works because Flyway runs as `fabt` (the table owner) in prod and
-- as `fabt_test` (also the owner) in test — both bypass RLS by virtue of
-- being the table owner. If Flyway's connection user is ever changed to
-- `fabt_app`, this UPDATE will silently affect ZERO rows because RLS hides
-- every row from non-owner queries without a tenant context. Hard-to-detect
-- failure mode; flagged here for future refactor.
UPDATE app_user
   SET roles = roles || ARRAY['COC_ADMIN'],
       token_version = token_version + 1
 WHERE 'PLATFORM_ADMIN' = ANY(roles)
   AND NOT ('COC_ADMIN' = ANY(roles));
