-- V90: Platform-operator self-metadata for the F11 dashboard (v0.54).
--
-- Adds:
--   1. `platform_user.mfa_enrolled_at TIMESTAMPTZ` column — captures the moment
--      MFA flipped from disabled→enabled. Distinct from "earliest backup code
--      created_at" because backup codes are DELETEd-and-replaced on
--      regeneration (V87 platform_user_insert_backup_codes), so an enrollment
--      timestamp derived from MIN(backup_code.created_at) drifts each time
--      the operator regenerates codes. Warroom round 3 (Alex #3) caught this.
--   2. Backfill: any existing row with mfa_enabled=true gets mfa_enrolled_at
--      set to MIN(platform_user_backup_code.created_at) — best approximation
--      from the data we have. Pre-v0.54 prod has 1 platform_user (the
--      bootstrap-activated operator) with codes intact, so this works.
--   3. CREATE OR REPLACE platform_user_update_credentials — when transitioning
--      mfa_enabled from false→true, ALSO sets mfa_enrolled_at = NOW() if not
--      already set (idempotent under retry). All other update paths (password
--      change, account_locked toggle) leave mfa_enrolled_at untouched.
--   4. New SECURITY DEFINER function platform_user_get_me() returning
--      operator-self metadata for `GET /api/v1/auth/platform/me`:
--         (id, email, mfa_enabled, last_login_at, mfa_enrolled_at,
--          backup_codes_remaining)
--      backup_codes_remaining = COUNT(*) WHERE used_at IS NULL.
--   5. Defensive ownership transfer to `fabt` mirroring V87's pattern (lines
--      365-379) so SECURITY DEFINER executes with the correct identity in
--      prod. Warroom round 3 (Elena #1) caught the missing block.
--
-- Phase B owner-bypass exemption: platform_user has no tenant_id column, so
-- there is no RLS-protected tenant scope to bypass. REVOKE ALL on direct
-- table access from fabt_app + access via these SECURITY DEFINER functions
-- matches the V87/V88 pattern. See MigrationLintTest.SECURITY_DEFINER_ALLOWLIST.

ALTER TABLE public.platform_user
    ADD COLUMN IF NOT EXISTS mfa_enrolled_at TIMESTAMPTZ;

-- Backfill: any pre-v0.54 enrolled row gets mfa_enrolled_at from the earliest
-- backup code's created_at. If the operator has no backup codes (impossible
-- in normal flow but defensive), leave NULL.
UPDATE public.platform_user pu
   SET mfa_enrolled_at = (
        SELECT MIN(pubc.created_at)
          FROM public.platform_user_backup_code pubc
         WHERE pubc.platform_user_id = pu.id)
 WHERE pu.mfa_enabled = TRUE
   AND pu.mfa_enrolled_at IS NULL;


-- ---------------------------------------------------------------------------
-- update_credentials: also set mfa_enrolled_at on first false→true transition
-- ---------------------------------------------------------------------------
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
       SET password_hash    = COALESCE(p_password_hash, password_hash),
           mfa_secret       = COALESCE(p_mfa_secret, mfa_secret),
           mfa_enabled      = COALESCE(p_mfa_enabled, mfa_enabled),
           account_locked   = COALESCE(p_account_locked, account_locked),
           -- Set mfa_enrolled_at on the very first false→true transition.
           -- If already set, leave alone (regenerate-codes / re-confirm MFA
           -- paths don't reset enrollment time). If p_mfa_enabled is null
           -- (caller doesn't touch the flag), leave alone.
           mfa_enrolled_at  = CASE
               WHEN p_mfa_enabled = TRUE
                    AND mfa_enabled = FALSE
                    AND mfa_enrolled_at IS NULL
                   THEN NOW()
               ELSE mfa_enrolled_at
           END
     WHERE id = p_id
       AND anonymized_at IS NULL;
    RETURN FOUND;
END;
$$;


-- ---------------------------------------------------------------------------
-- get_me: operator-self metadata for the F11 dashboard
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION platform_user_get_me(p_id UUID)
RETURNS TABLE (
    id                     UUID,
    email                  TEXT,
    mfa_enabled            BOOLEAN,
    last_login_at          TIMESTAMPTZ,
    mfa_enrolled_at        TIMESTAMPTZ,
    backup_codes_remaining INTEGER
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    RETURN QUERY
        SELECT pu.id,
               pu.email,
               pu.mfa_enabled,
               pu.last_login_at,
               pu.mfa_enrolled_at,
               (SELECT COUNT(*)::INTEGER
                  FROM public.platform_user_backup_code pubc
                 WHERE pubc.platform_user_id = pu.id
                   AND pubc.used_at IS NULL) AS backup_codes_remaining
          FROM public.platform_user pu
         WHERE pu.id = p_id
           AND pu.anonymized_at IS NULL;
END;
$$;

GRANT EXECUTE ON FUNCTION platform_user_get_me(UUID) TO fabt_app;


-- ---------------------------------------------------------------------------
-- Defensive ownership transfer (mirrors V87 lines 365-379)
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'fabt') THEN
        ALTER FUNCTION platform_user_update_credentials(UUID, TEXT, TEXT, BOOLEAN, BOOLEAN) OWNER TO fabt;
        ALTER FUNCTION platform_user_get_me(UUID) OWNER TO fabt;
    END IF;
END
$$;
