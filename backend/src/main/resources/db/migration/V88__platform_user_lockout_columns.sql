-- V88 — Phase G slice G-4.2: per-account MFA lockout state + atomic MFA
-- enrollment + TOTP replay-protection columns on platform_user.
--
-- Schema additions:
--   * failed_mfa_attempts_at  TIMESTAMPTZ[]  — recent fail timestamps,
--                                              pruned to the active window
--                                              on every insert
--   * locked_out_at           TIMESTAMPTZ    — when auto-lockout fired
--                                              (NULL = not auto-locked,
--                                              distinguishes from the
--                                              manual lock the bootstrap
--                                              row carries)
--   * last_totp_code          TEXT           — most-recently-accepted TOTP
--                                              code, used to reject replay
--                                              within the 89-second
--                                              ±1-step RFC 6238 window
--                                              (Marcus warroom M1)
--   * last_totp_used_at       TIMESTAMPTZ    — companion to last_totp_code
--
-- The existing `account_locked BOOLEAN` column from V87 remains the
-- canonical "is this account allowed to log in" flag. `locked_out_at`
-- adds context: was this lockout from MFA-fail-counter (auto-unlock
-- candidate) or from bootstrap / operator action (stays locked until
-- manual intervention).
--
-- SECURITY DEFINER functions added: `record_failure`, `clear_failures`,
-- `unlock_expired`, `setup_mfa` (atomic), `record_totp_use`,
-- `was_totp_recently_used`. Mirrors the V87 access pattern — fabt_app has
-- no direct access to platform_user, calls go through these wrappers.
--
-- This is V88 of the platform-admin-split-and-access-log change. The
-- platform_admin_access_log table (originally drafted as V88 in tasks.md)
-- moves to V89 in G-4.3 because per-account lockout requires schema that
-- naturally lives with the auth flow that USES it.

ALTER TABLE platform_user
    ADD COLUMN failed_mfa_attempts_at TIMESTAMPTZ[] NOT NULL DEFAULT '{}',
    ADD COLUMN locked_out_at          TIMESTAMPTZ,
    ADD COLUMN last_totp_code         TEXT,
    ADD COLUMN last_totp_used_at      TIMESTAMPTZ;

COMMENT ON COLUMN platform_user.failed_mfa_attempts_at IS
    'Rolling window of recent failed-MFA timestamps. Pruned on every insert by platform_user_record_failure to the threshold-window only.';

COMMENT ON COLUMN platform_user.locked_out_at IS
    'NULL = not auto-locked. Non-NULL = MFA-counter triggered lockout at this instant. Cron clears + unlocks at +15min.';

COMMENT ON COLUMN platform_user.last_totp_code IS
    'Most-recently-accepted TOTP code; used to reject replay within the 89s ±1-step RFC 6238 window.';

-- Partial index supporting the cron auto-unlock query (Elena warroom E1).
-- WHERE-clause matches the predicate in platform_user_unlock_expired.
CREATE INDEX platform_user_locked_out_at
    ON platform_user (locked_out_at)
    WHERE locked_out_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- SECURITY DEFINER wrappers — V87 REVOKE on platform_user still applies.
-- ---------------------------------------------------------------------------

-- Records a failed login/MFA attempt, prunes the rolling window to last
-- p_window_min minutes, and auto-locks if the count meets the threshold.
-- Returns true iff THIS call triggered the lockout transition (so the
-- service can write a single PLATFORM_USER_LOCKED_OUT audit row even under
-- concurrent failed attempts).
CREATE OR REPLACE FUNCTION platform_user_record_failure(
    p_id          UUID,
    p_window_min  INT,
    p_threshold   INT
) RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    v_recent       TIMESTAMPTZ[];
    v_was_locked   BOOLEAN;
    v_now_locked   BOOLEAN := false;
BEGIN
    -- Append NOW() then keep only those still within the rolling window.
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
        -- Row not found / anonymized — no-op.
        RETURN false;
    END IF;

    IF NOT v_was_locked
       AND COALESCE(array_length(v_recent, 1), 0) >= p_threshold THEN
        UPDATE public.platform_user
           SET account_locked = true,
               locked_out_at  = NOW()
         WHERE id = p_id;
        v_now_locked := true;
    END IF;

    RETURN v_now_locked;
END;
$$;
GRANT EXECUTE ON FUNCTION platform_user_record_failure(UUID, INT, INT) TO fabt_app;


-- Clears the failure window on a successful authentication.
CREATE OR REPLACE FUNCTION platform_user_clear_failures(p_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    UPDATE public.platform_user
       SET failed_mfa_attempts_at = '{}'::TIMESTAMPTZ[]
     WHERE id = p_id AND anonymized_at IS NULL;
END;
$$;
GRANT EXECUTE ON FUNCTION platform_user_clear_failures(UUID) TO fabt_app;


-- Cron-callable: unlocks every row whose lockout has aged past
-- p_window_min. Returns the count of rows unlocked, for ops visibility /
-- metrics. Only touches rows where locked_out_at IS NOT NULL — manual
-- locks (bootstrap row, operator-set) stay locked. Anonymized rows are
-- filtered defensively (Elena warroom E2).
CREATE OR REPLACE FUNCTION platform_user_unlock_expired(p_window_min INT)
RETURNS INT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    v_count INT;
BEGIN
    UPDATE public.platform_user
       SET account_locked         = false,
           locked_out_at          = NULL,
           failed_mfa_attempts_at = '{}'::TIMESTAMPTZ[]
     WHERE locked_out_at IS NOT NULL
       AND locked_out_at < NOW() - make_interval(mins => p_window_min)
       AND anonymized_at IS NULL;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$;
GRANT EXECUTE ON FUNCTION platform_user_unlock_expired(INT) TO fabt_app;


-- Atomic MFA enrollment in a single SECURITY DEFINER call (Alex warroom A1).
-- Replaces the 2-call setup pattern (updateCredentials + replaceBackupCodes)
-- so a partial failure can never leave a TOTP secret without backup codes.
-- Refuses if the user is already enrolled (mfa_enabled = true) — guards
-- against stale mfa-setup-token replay regenerating credentials on an
-- enrolled account (Alex warroom A4).
--
-- Backup codes are passed as parallel arrays of equal length; mismatched
-- lengths raise check_violation. The function DELETEs any pre-existing
-- backup codes for the user (a fresh enrollment invalidates them) before
-- inserting the new set.
--
-- Returns true on success, false if the user already has mfa_enabled=true
-- (caller treats as 409 / refuse-replay).
CREATE OR REPLACE FUNCTION platform_user_setup_mfa(
    p_id        UUID,
    p_secret    TEXT,
    p_ids       UUID[],
    p_hashes    TEXT[],
    p_salts     BYTEA[]
) RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    v_mfa_enabled BOOLEAN;
    v_count       INT;
BEGIN
    SELECT mfa_enabled INTO v_mfa_enabled
      FROM public.platform_user
     WHERE id = p_id AND anonymized_at IS NULL;
    IF NOT FOUND THEN
        RETURN false;
    END IF;
    IF v_mfa_enabled THEN
        RETURN false;
    END IF;
    IF array_length(p_ids, 1) IS DISTINCT FROM array_length(p_hashes, 1)
       OR array_length(p_ids, 1) IS DISTINCT FROM array_length(p_salts, 1) THEN
        RAISE EXCEPTION 'platform_user_setup_mfa: id/hash/salt array lengths differ';
    END IF;

    UPDATE public.platform_user
       SET mfa_secret = p_secret
     WHERE id = p_id AND anonymized_at IS NULL;

    DELETE FROM public.platform_user_backup_code
     WHERE platform_user_id = p_id;

    INSERT INTO public.platform_user_backup_code
        (id, platform_user_id, code_hash, code_salt)
    SELECT p_ids[i], p_id, p_hashes[i], p_salts[i]
      FROM generate_subscripts(p_ids, 1) AS i;

    GET DIAGNOSTICS v_count = ROW_COUNT;
    IF v_count <> array_length(p_ids, 1) THEN
        RAISE EXCEPTION 'platform_user_setup_mfa: inserted % rows, expected %',
            v_count, array_length(p_ids, 1);
    END IF;
    RETURN true;
END;
$$;
GRANT EXECUTE ON FUNCTION platform_user_setup_mfa(UUID, TEXT, UUID[], TEXT[], BYTEA[]) TO fabt_app;


-- Records that a TOTP code was just accepted (Marcus warroom M1).
-- Stored alongside the timestamp so was_totp_recently_used can reject
-- replays within the 89-second ±1-step RFC 6238 acceptance window.
CREATE OR REPLACE FUNCTION platform_user_record_totp_use(
    p_id    UUID,
    p_code  TEXT
) RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    UPDATE public.platform_user
       SET last_totp_code    = p_code,
           last_totp_used_at = NOW()
     WHERE id = p_id AND anonymized_at IS NULL;
END;
$$;
GRANT EXECUTE ON FUNCTION platform_user_record_totp_use(UUID, TEXT) TO fabt_app;


-- Sets email on a platform_user row. Used by the bootstrap activation
-- flow (operator runs the equivalent UPDATE via psql in production, but
-- integration tests can't reach the table directly under fabt_app). Also
-- the foundation for the Phase H+ "create new platform_user via existing
-- operator" flow + operator self-service email change.
CREATE OR REPLACE FUNCTION platform_user_set_email(
    p_id    UUID,
    p_email TEXT
) RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    UPDATE public.platform_user
       SET email = p_email
     WHERE id = p_id AND anonymized_at IS NULL;
    RETURN FOUND;
END;
$$;
GRANT EXECUTE ON FUNCTION platform_user_set_email(UUID, TEXT) TO fabt_app;


-- Returns true if the presented code matches last_totp_code AND
-- last_totp_used_at is within p_window_seconds of NOW(). Caller rejects
-- replay before invoking the TOTP verifier.
CREATE OR REPLACE FUNCTION platform_user_was_totp_recently_used(
    p_id              UUID,
    p_code            TEXT,
    p_window_seconds  INT
) RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    v_recent BOOLEAN := false;
BEGIN
    SELECT (last_totp_code = p_code
            AND last_totp_used_at IS NOT NULL
            AND last_totp_used_at > NOW() - make_interval(secs => p_window_seconds))
      INTO v_recent
      FROM public.platform_user
     WHERE id = p_id AND anonymized_at IS NULL;
    RETURN COALESCE(v_recent, false);
END;
$$;
GRANT EXECUTE ON FUNCTION platform_user_was_totp_recently_used(UUID, TEXT, INT) TO fabt_app;


-- Defensive ownership transfer (prod only — fabt role doesn't exist in test).
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'fabt') THEN
        ALTER FUNCTION platform_user_record_failure(UUID, INT, INT) OWNER TO fabt;
        ALTER FUNCTION platform_user_clear_failures(UUID) OWNER TO fabt;
        ALTER FUNCTION platform_user_unlock_expired(INT) OWNER TO fabt;
        ALTER FUNCTION platform_user_setup_mfa(UUID, TEXT, UUID[], TEXT[], BYTEA[]) OWNER TO fabt;
        ALTER FUNCTION platform_user_record_totp_use(UUID, TEXT) OWNER TO fabt;
        ALTER FUNCTION platform_user_was_totp_recently_used(UUID, TEXT, INT) OWNER TO fabt;
        ALTER FUNCTION platform_user_set_email(UUID, TEXT) OWNER TO fabt;
    END IF;
END
$$;
