-- V90: Add `platform_user_get_me` SECURITY DEFINER function for the F11
-- platform-operator UI (v0.54). Returns operator-self metadata in a single
-- round-trip for the SPA dashboard's `GET /api/v1/auth/platform/me`.
--
-- Rationale: the existing `platform_user_lookup_by_id` (V87) returns only
-- credential-shaped fields (id, email, password_hash, mfa_secret, mfa_enabled,
-- account_locked) and is the hot-path login resolver. Extending its return
-- shape would change the LOOKUP_MAPPER contract for every caller. A separate
-- metadata function keeps the auth path stable while exposing the dashboard
-- fields.
--
-- Returned fields:
--   id                       — operator's UUID (matches JWT sub claim)
--   email                    — for in-banner display (frontend masks per UX)
--   mfa_enabled              — should always be true for an authenticated
--                              caller, but returned for defensive rendering
--   last_login_at            — Instant of most recent successful auth
--   mfa_enabled_at           — derived from MIN(platform_user_backup_code
--                              .created_at); the earliest backup code is
--                              created during MFA enrollment confirmation
--   backup_codes_remaining   — count where used_at IS NULL; drives the
--                              dashboard urgency badge (amber@3, red@1)
--
-- Anonymized rows (anonymized_at IS NOT NULL) return zero rows, matching
-- the existing lookup function's behaviour.
CREATE OR REPLACE FUNCTION platform_user_get_me(p_id UUID)
RETURNS TABLE (
    id                     UUID,
    email                  TEXT,
    mfa_enabled            BOOLEAN,
    last_login_at          TIMESTAMPTZ,
    mfa_enabled_at         TIMESTAMPTZ,
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
               (SELECT MIN(pubc.created_at)
                  FROM public.platform_user_backup_code pubc
                 WHERE pubc.platform_user_id = pu.id) AS mfa_enabled_at,
               (SELECT COUNT(*)::INTEGER
                  FROM public.platform_user_backup_code pubc
                 WHERE pubc.platform_user_id = pu.id
                   AND pubc.used_at IS NULL)            AS backup_codes_remaining
          FROM public.platform_user pu
         WHERE pu.id = p_id
           AND pu.anonymized_at IS NULL;
END;
$$;

GRANT EXECUTE ON FUNCTION platform_user_get_me(UUID) TO fabt_app;
