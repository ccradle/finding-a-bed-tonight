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
-- anonymize / restore: low-level Phase H+ recovery primitives
-- ---------------------------------------------------------------------------
-- Same UNAUTHORIZED-relies-on-caller-side-gating posture as V88's
-- platform_user_reset_to_bootstrap. The functions exist now so:
--   (a) F11 integration tests can exercise the anonymized-row branch of
--       platform_user_get_me's WHERE clause (without these primitives, the
--       fabt_app test connection role has no way to set anonymized_at —
--       V87 REVOKEs ALL on the table).
--   (b) Phase H+ GDPR-Art-17 tooling (per V87 PlatformUser javadoc lines
--       30-34) has the schema-level primitive ready to wrap in an
--       authorized REST flow without another migration round-trip.
-- The corresponding restore function is also gated server-side to a single
-- column update; both functions only mutate platform_user.anonymized_at,
-- not credentials or backup codes (those are separately rotated through
-- the existing primitives).
CREATE OR REPLACE FUNCTION platform_user_anonymize(p_id UUID)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    UPDATE public.platform_user
       SET anonymized_at = NOW()
     WHERE id = p_id
       AND anonymized_at IS NULL;
    RETURN FOUND;
END;
$$;
GRANT EXECUTE ON FUNCTION platform_user_anonymize(UUID) TO fabt_app;
COMMENT ON FUNCTION platform_user_anonymize(UUID) IS
    'Sets platform_user.anonymized_at = NOW() on a row that is not already anonymized. UNAUTHORIZED — relies on caller-side gating. Currently used by F11 integration tests + planned Phase H+ GDPR Art-17 tooling. Do NOT call from psql except in incident response with explicit operator authorization. The corresponding un-anonymize primitive is platform_user_restore.';

CREATE OR REPLACE FUNCTION platform_user_restore(p_id UUID)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    UPDATE public.platform_user
       SET anonymized_at = NULL
     WHERE id = p_id
       AND anonymized_at IS NOT NULL;
    RETURN FOUND;
END;
$$;
GRANT EXECUTE ON FUNCTION platform_user_restore(UUID) TO fabt_app;
COMMENT ON FUNCTION platform_user_restore(UUID) IS
    'Clears platform_user.anonymized_at on a row that was previously anonymized. UNAUTHORIZED — relies on caller-side gating. Currently used by F11 integration tests for cleanup after exercising the anonymized branch of platform_user_get_me; Phase H+ may use it for restore-from-soft-delete recovery flows.';


-- ---------------------------------------------------------------------------
-- record_failure_with_state: extended variant of V88 record_failure that
-- returns the data the F11 PlatformMfaVerify SPA needs to render the
-- "X attempts remaining before lockout" + "Account locked for 15
-- minutes" copy from spec round 3 H7.
--
-- The V88 record_failure returns a single BOOLEAN (was-this-the-lock
-- transition), which is sufficient for the audit-log emission path but
-- not for an SPA error UI. Rather than DROP+CREATE the V88 function
-- (which would break already-deployed v0.53 backend if it co-exists with
-- a v0.54 DB during a deploy window), we add a SIBLING function and
-- migrate the v0.54 service layer to call the new one.
--
-- Returned fields:
--   now_locked    — true ONLY on the failure that triggered lockout
--                   (same semantic as V88 record_failure boolean)
--   account_locked — current account_locked flag AFTER this failure;
--                    distinguishes "just locked" (now_locked=true,
--                    account_locked=true) from "already locked"
--                    (now_locked=false, account_locked=true)
--   attempts_used  — count of attempts in the current rolling window
--                    AFTER this failure was recorded; the SPA computes
--                    `attemptsRemaining = max(0, threshold - attempts_used)`
--
-- The function also auto-locks at threshold, identical to V88's
-- contract; the only difference is the richer return type.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION platform_user_record_failure_with_state(
    p_id          UUID,
    p_window_min  INT,
    p_threshold   INT
)
RETURNS TABLE (
    out_now_locked     BOOLEAN,
    out_account_locked BOOLEAN,
    out_attempts_used  INTEGER
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
-- OUT-param names use `out_` prefix to avoid name shadowing with the
-- `account_locked` column on `platform_user` — plpgsql resolves naked
-- identifiers in DML against OUT params before columns under TABLE
-- return, which silently broke the UPDATE...RETURNING below until this
-- test surfaced it as a "bad SQL grammar" runtime error.
DECLARE
    v_recent      TIMESTAMPTZ[];
    v_was_locked  BOOLEAN;
    v_now_locked  BOOLEAN := false;
    v_attempts    INTEGER;
BEGIN
    UPDATE public.platform_user
       SET failed_mfa_attempts_at = (
            SELECT COALESCE(array_agg(t), '{}'::TIMESTAMPTZ[])
              FROM unnest(failed_mfa_attempts_at || ARRAY[NOW()]) AS t
             WHERE t > NOW() - make_interval(mins => p_window_min)
       )
     WHERE id = p_id AND anonymized_at IS NULL
    RETURNING failed_mfa_attempts_at, account_locked
        INTO v_recent, v_was_locked;

    IF v_recent IS NULL THEN
        -- Row not found / anonymized — surface as "not locked, 0 attempts"
        -- so the caller's UX is unaffected (the auth flow returns generic
        -- 401 in this case anyway).
        out_now_locked := false;
        out_account_locked := false;
        out_attempts_used := 0;
        RETURN NEXT;
        RETURN;
    END IF;

    v_attempts := COALESCE(array_length(v_recent, 1), 0);

    IF NOT v_was_locked AND v_attempts >= p_threshold THEN
        UPDATE public.platform_user
           SET account_locked = true,
               locked_out_at  = NOW()
         WHERE id = p_id;
        v_now_locked := true;
        v_was_locked := true;
    END IF;

    out_now_locked := v_now_locked;
    out_account_locked := v_was_locked;
    out_attempts_used := v_attempts;
    RETURN NEXT;
END;
$$;
GRANT EXECUTE ON FUNCTION platform_user_record_failure_with_state(UUID, INT, INT) TO fabt_app;


-- ---------------------------------------------------------------------------
-- Defensive ownership transfer (mirrors V87 lines 365-379)
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'fabt') THEN
        ALTER FUNCTION platform_user_update_credentials(UUID, TEXT, TEXT, BOOLEAN, BOOLEAN) OWNER TO fabt;
        ALTER FUNCTION platform_user_get_me(UUID) OWNER TO fabt;
        ALTER FUNCTION platform_user_anonymize(UUID) OWNER TO fabt;
        ALTER FUNCTION platform_user_restore(UUID) OWNER TO fabt;
        ALTER FUNCTION platform_user_record_failure_with_state(UUID, INT, INT) OWNER TO fabt;
    END IF;
END
$$;
