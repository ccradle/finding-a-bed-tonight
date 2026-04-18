-- V67 — Phase B (task 3.3): CASE-guarded STABLE LEAKPROOF PARALLEL SAFE SQL
-- function wrapping current_setting('app.tenant_id'). Returns NULL for
-- unset/empty/malformed input; only valid canonical UUIDs pass through.
--
-- Per design-b-rls-hardening §D43:
--   - STABLE — deterministic within a single query; enables index pushdown
--   - LEAKPROOF — REQUIRED for use in RLS policy expressions without
--     disabling planner optimizations at policy barriers
--   - PARALLEL SAFE — allows parallel query plans on RLS'd scans
--
-- Operator-asserted LEAKPROOF lie: Postgres marks functions that can throw
-- as NOT truly leakproof in the strict sense. The naïve
-- `NULLIF(current_setting(...), '')::uuid` form throws on malformed input.
-- The CASE-guarded regex normalizes three sad paths (NULL / empty /
-- malformed) before the cast, guaranteeing no throw for any text input.
-- Combined with RlsDataSourceConfig only writing validated UUIDs to
-- app.tenant_id, the throw path is unreachable.
--
-- Verification test (V67 integration test): every branch of the CASE
-- statement returns the expected NULL or UUID; function is flagged
-- proleakproof=true, provolatile='s', proparallel='s' in pg_proc.

CREATE OR REPLACE FUNCTION fabt_current_tenant_id()
RETURNS uuid
LANGUAGE sql
STABLE
LEAKPROOF
PARALLEL SAFE
AS $$
  SELECT CASE
    WHEN current_setting('app.tenant_id', true) IS NULL THEN NULL::uuid
    WHEN current_setting('app.tenant_id', true) = '' THEN NULL::uuid
    WHEN current_setting('app.tenant_id', true) !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' THEN NULL::uuid
    ELSE current_setting('app.tenant_id', true)::uuid
  END
$$;

COMMENT ON FUNCTION fabt_current_tenant_id() IS
  'Returns the current session tenant UUID, or NULL if unset/malformed. '
  'Operator-asserted LEAKPROOF per design-b-rls-hardening D43: the regex-'
  'guarded CASE statement prevents the ::uuid cast from throwing on '
  'malformed input, which would otherwise make the function NOT truly '
  'LEAKPROOF (functions that throw can leak via error-path timing). '
  'The app.tenant_id GUC is only written by RlsDataSourceConfig with a '
  'validated UUID or empty string — the regex is belt-and-suspenders. '
  'Used by Phase B RLS policies on audit_events, hmis_audit_log, '
  'password_reset_token, one_time_access_code, hmis_outbox, '
  'tenant_key_material, kid_to_tenant_key.';
