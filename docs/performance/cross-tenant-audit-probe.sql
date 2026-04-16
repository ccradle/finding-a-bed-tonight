-- =====================================================================
-- Cross-Tenant Audit Performance Probe (Issue #117 closeout)
-- =====================================================================
-- Validates that Phase 2/4 query changes did NOT regress query plans:
--   - Phase 2: findById(UUID) → findByIdAndTenantId(UUID, UUID) on 5
--     services (subscription, api_key, oauth2_provider, audit_events
--     reads, user lookups via getUser).
--   - Phase 4.8: extra set_config('app.tenant_id', ?, false) on every
--     connection borrow.
--
-- Approach: pg_stat_statements 100-run capture per query +
-- EXPLAIN (ANALYZE, BUFFERS) for plan inspection. The pre-audit
-- baseline is findByTenantIdAndEmail — a long-established
-- (tenant_id, email) lookup pattern. Anything in the same
-- order-of-magnitude is a no-regression signal.
--
-- Usage (against the running dev stack):
--   docker compose exec -T postgres psql -U fabt -d fabt < \
--     docs/performance/cross-tenant-audit-probe.sql
--   # OR from host:
--   PGPASSWORD=fabt psql -h localhost -U fabt -d fabt < \
--     docs/performance/cross-tenant-audit-probe.sql
--
-- Acceptance criteria (Elena, warroom 2026-04-16):
--   - Every new findByIdAndTenantId query plan = "Index Scan" or
--     "Index Only Scan" (NOT Seq Scan, NOT Bitmap Heap Scan unless
--     reading a full tenant's worth of rows).
--   - Mean exec time within 2× of the baseline findByTenantIdAndEmail.
--   - Buffer hits ≤ 50 per call (sub-millisecond on warm cache).
--
-- Cardinality at dev scale is small — this probe primarily catches
-- plan-quality regressions, not absolute throughput. CAVEAT: Postgres
-- may choose Seq Scan over Index Scan on tiny (<1000 row) tables —
-- that is NOT a regression. Re-run against NYC-scale data or the
-- seed-oldest-pending-probe dataset to validate plan choice on real
-- volume.
-- =====================================================================

\timing on
-- Abort on first error so RAISE EXCEPTION in preconditions stops the
-- script cleanly (warroom v3 #3).
\set ON_ERROR_STOP on

\echo ''
\echo '====================================================================='
\echo 'PRECONDITION 1 — pg_stat_statements extension available'
\echo '====================================================================='
SELECT extname, extversion FROM pg_extension WHERE extname = 'pg_stat_statements';
-- Expected: one row. If empty, add `shared_preload_libraries = pg_stat_statements`
-- to postgres.conf and restart.

\echo ''
\echo '====================================================================='
\echo 'PRECONDITION 2 — current user can call pg_stat_statements_reset()'
\echo '====================================================================='
-- pg_stat_statements_reset() requires SUPERUSER or pg_read_all_stats role.
-- Without it, the resets fail silently and stats accumulate from prior
-- sessions, contaminating the per-query timings below.
DO $check$
BEGIN
    IF NOT (
        (SELECT rolsuper FROM pg_roles WHERE rolname = current_user)
        OR pg_has_role(current_user, 'pg_read_all_stats', 'MEMBER')
    ) THEN
        RAISE EXCEPTION 'cross-tenant-audit-probe requires SUPERUSER or '
            'pg_read_all_stats role. Current user: %. '
            'Re-run as a privileged user (the dev container postgres '
            'image uses POSTGRES_USER=fabt with SUPERUSER).', current_user;
    END IF;
END $check$;
SELECT current_user AS probe_running_as;

\echo ''
\echo '====================================================================='
\echo 'PRECONDITION 3 — V57 (audit_events.tenant_id) migration applied'
\echo '====================================================================='
DO $check$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'audit_events' AND column_name = 'tenant_id'
    ) THEN
        RAISE EXCEPTION 'audit_events.tenant_id column missing — V57 has '
            'not been applied. Run Flyway migrate before this probe.';
    END IF;
END $check$;
SELECT 'V57 applied' AS audit_events_tenant_id_check;

-- Refresh tenant temp table — DROP first so re-runs don't keep stale data.
DROP TABLE IF EXISTS _probe_tenant;
CREATE TEMP TABLE _probe_tenant AS
SELECT id AS tenant_id FROM tenant WHERE slug = 'dev-coc';

\echo ''
\echo 'Tenant under probe:'
SELECT * FROM _probe_tenant;

-- =====================================================================
-- Q0 (NEW) — baseline: 2-arg set_config (PRE-D13 shape)
-- =====================================================================
\echo ''
\echo '====================================================================='
\echo 'Q0 — BASELINE: 2-arg set_config (pre-D13 shape, dv_access + user_id)'
\echo '====================================================================='
\echo 'Uses generate_series so all 100 set_config calls happen INSIDE one'
\echo 'top-level SQL statement — pg_stat_statements track=top tracks them'
\echo 'as a single row with calls=1, total_time = 100 invocations.'
\echo 'Per-borrow cost ≈ total_exec_time / 100.'

SELECT pg_stat_statements_reset();

-- Wrap the 100-row generate_series in a count(*) sink so only one row
-- prints (the count). Portable across Linux + Windows psql — no
-- platform-specific \o /dev/null vs \o nul (warroom v3 #1).
-- pg_stat_statements still tracks the inner SELECT as a separate
-- statement (the OUTER count is a different fingerprint), so the
-- filter below picks up the set_config statement, not the count.
SELECT count(*) AS set_config_invocations
  FROM (
    SELECT set_config('app.dv_access', 'false', false),
           set_config('app.current_user_id', '00000000-0000-0000-0000-000000000000', false)
      FROM generate_series(1, 100) AS s(i)
  ) AS sink;

SELECT
    substring(query FROM 1 FOR 90) AS query_snippet,
    calls,
    round(total_exec_time::numeric, 4) AS total_ms,
    round((total_exec_time / 100)::numeric, 6) AS approx_per_call_ms
FROM pg_stat_statements
WHERE query ILIKE '%set_config%dv_access%'
  AND query NOT ILIKE '%count(%'
ORDER BY total_exec_time DESC
LIMIT 1;

-- =====================================================================
-- Q1 — BASELINE app_user lookup (established pattern)
-- =====================================================================
\echo ''
\echo '====================================================================='
\echo 'Q1 — BASELINE: findByTenantIdAndEmail (pre-audit pattern, established)'
\echo '====================================================================='
SELECT pg_stat_statements_reset();

DO $run$
DECLARE
    v_tenant uuid;
    v_result record;
BEGIN
    SELECT tenant_id INTO v_tenant FROM _probe_tenant;
    FOR i IN 1..100 LOOP
        EXECUTE 'SELECT * FROM app_user WHERE tenant_id = $1 AND email = $2'
            INTO v_result
            USING v_tenant, 'admin@dev.fabt.org';
    END LOOP;
END $run$;

SELECT
    substring(query FROM 1 FOR 70) AS query_snippet,
    calls,
    round(mean_exec_time::numeric, 4) AS mean_ms,
    round(max_exec_time::numeric, 4) AS max_ms,
    round(stddev_exec_time::numeric, 4) AS stddev_ms,
    round(total_exec_time::numeric, 2) AS total_ms
FROM pg_stat_statements
WHERE query ILIKE '%app_user%tenant_id%email%'
  AND query NOT ILIKE '%pg_stat_statements%'
ORDER BY total_exec_time DESC
LIMIT 3;

-- =====================================================================
-- Q2 — subscription.findByIdAndTenantId (Phase 2.4)
-- =====================================================================
-- Per warroom #4: gen_random_uuid() is called INSIDE the loop USING
-- clause so each iteration hits a different leaf page (cold-cache
-- realism, not the same-UUID warm-cache underestimate).
\echo ''
\echo '====================================================================='
\echo 'Q2 — subscription.findByIdAndTenantId (Phase 2.4)'
\echo '====================================================================='
SELECT pg_stat_statements_reset();

DO $run$
DECLARE
    v_tenant uuid;
    v_result record;
BEGIN
    SELECT tenant_id INTO v_tenant FROM _probe_tenant;
    FOR i IN 1..100 LOOP
        EXECUTE 'SELECT * FROM subscription WHERE id = $1 AND tenant_id = $2'
            INTO v_result
            USING gen_random_uuid(), v_tenant;
    END LOOP;
END $run$;

SELECT
    substring(query FROM 1 FOR 70) AS query_snippet,
    calls,
    round(mean_exec_time::numeric, 4) AS mean_ms,
    round(max_exec_time::numeric, 4) AS max_ms,
    round(stddev_exec_time::numeric, 4) AS stddev_ms
FROM pg_stat_statements
WHERE query ILIKE '%subscription%id = $%tenant_id%'
ORDER BY total_exec_time DESC
LIMIT 3;

-- =====================================================================
-- Q3 — api_key.findByIdAndTenantId (Phase 2.2)
-- =====================================================================
\echo ''
\echo '====================================================================='
\echo 'Q3 — api_key.findByIdAndTenantId (Phase 2.2)'
\echo '====================================================================='
SELECT pg_stat_statements_reset();

DO $run$
DECLARE
    v_tenant uuid;
    v_result record;
BEGIN
    SELECT tenant_id INTO v_tenant FROM _probe_tenant;
    FOR i IN 1..100 LOOP
        EXECUTE 'SELECT * FROM api_key WHERE id = $1 AND tenant_id = $2'
            INTO v_result
            USING gen_random_uuid(), v_tenant;
    END LOOP;
END $run$;

SELECT
    substring(query FROM 1 FOR 70) AS query_snippet,
    calls,
    round(mean_exec_time::numeric, 4) AS mean_ms,
    round(max_exec_time::numeric, 4) AS max_ms,
    round(stddev_exec_time::numeric, 4) AS stddev_ms
FROM pg_stat_statements
WHERE query ILIKE '%api_key%id = $%tenant_id%'
ORDER BY total_exec_time DESC
LIMIT 3;

-- =====================================================================
-- Q4 — tenant_oauth2_provider.findByIdAndTenantId (Phase 2.1)
-- =====================================================================
\echo ''
\echo '====================================================================='
\echo 'Q4 — tenant_oauth2_provider.findByIdAndTenantId (Phase 2.1)'
\echo '====================================================================='
SELECT pg_stat_statements_reset();

DO $run$
DECLARE
    v_tenant uuid;
    v_result record;
BEGIN
    SELECT tenant_id INTO v_tenant FROM _probe_tenant;
    FOR i IN 1..100 LOOP
        EXECUTE 'SELECT * FROM tenant_oauth2_provider WHERE id = $1 AND tenant_id = $2'
            INTO v_result
            USING gen_random_uuid(), v_tenant;
    END LOOP;
END $run$;

SELECT
    substring(query FROM 1 FOR 70) AS query_snippet,
    calls,
    round(mean_exec_time::numeric, 4) AS mean_ms,
    round(max_exec_time::numeric, 4) AS max_ms,
    round(stddev_exec_time::numeric, 4) AS stddev_ms
FROM pg_stat_statements
WHERE query ILIKE '%tenant_oauth2_provider%id = $%tenant_id%'
ORDER BY total_exec_time DESC
LIMIT 3;

-- =====================================================================
-- Q5 — audit_events tenant-filtered read (Phase 2.12 / V57)
-- =====================================================================
-- Query shape matches AuditEventRepository.findByTargetUserIdAndTenantId
-- exactly (warroom #3): WHERE target_user_id AND tenant_id ORDER BY
-- timestamp DESC LIMIT 100. Uses the V57 composite index
-- (tenant_id, target_user_id, timestamp DESC).
-- audit_events has the most volume in dev — most meaningful timing.
\echo ''
\echo '====================================================================='
\echo 'Q5 — audit_events findByTargetUserIdAndTenantId (Phase 2.12 / V57)'
\echo '====================================================================='
SELECT pg_stat_statements_reset();

DO $run$
DECLARE
    v_tenant uuid;
    v_target uuid;
    v_result record;
BEGIN
    SELECT tenant_id INTO v_tenant FROM _probe_tenant;
    SELECT id INTO v_target FROM app_user
     WHERE tenant_id = v_tenant AND email = 'admin@dev.fabt.org';
    FOR i IN 1..100 LOOP
        EXECUTE 'SELECT * FROM audit_events
                 WHERE target_user_id = $1 AND tenant_id = $2
                 ORDER BY timestamp DESC LIMIT 100'
            INTO v_result
            USING v_target, v_tenant;
    END LOOP;
END $run$;

SELECT
    substring(query FROM 1 FOR 90) AS query_snippet,
    calls,
    round(mean_exec_time::numeric, 4) AS mean_ms,
    round(max_exec_time::numeric, 4) AS max_ms,
    round(stddev_exec_time::numeric, 4) AS stddev_ms
FROM pg_stat_statements
WHERE query ILIKE '%audit_events%target_user_id%tenant_id%'
ORDER BY total_exec_time DESC
LIMIT 3;

-- =====================================================================
-- Q6 — 3-arg set_config (NEW D13 shape) — compare to Q0
-- =====================================================================
\echo ''
\echo '====================================================================='
\echo 'Q6 — 3-arg set_config (NEW D13 shape, includes app.tenant_id)'
\echo '====================================================================='
\echo 'Compare approx_per_call_ms here vs Q0 to measure D13 overhead.'
\echo 'Expected: ≤ 0.01ms incremental cost (one extra parameter binding,'
\echo 'same single-round-trip statement per Sam bench in design D13).'

SELECT pg_stat_statements_reset();

-- Same count(*) sink wrapper as Q0 (warroom v3 #1).
SELECT count(*) AS set_config_invocations
  FROM (
    SELECT set_config('app.dv_access', 'false', false),
           set_config('app.current_user_id', '00000000-0000-0000-0000-000000000000', false),
           set_config('app.tenant_id', '00000000-0000-0000-0000-000000000000', false)
      FROM generate_series(1, 100) AS s(i)
  ) AS sink;

SELECT
    substring(query FROM 1 FOR 90) AS query_snippet,
    calls,
    round(total_exec_time::numeric, 4) AS total_ms,
    round((total_exec_time / 100)::numeric, 6) AS approx_per_call_ms
FROM pg_stat_statements
WHERE query ILIKE '%set_config%app.tenant_id%'
  AND query NOT ILIKE '%count(%'
ORDER BY total_exec_time DESC
LIMIT 1;

-- =====================================================================
-- EXPLAIN ANALYZE inspections — run AFTER all pg_stat_statements
-- captures so the EXPLAIN's own execution doesn't contaminate the
-- mean_exec_time numbers reported above (warroom #3 fix).
-- =====================================================================
\echo ''
\echo '====================================================================='
\echo 'EXPLAIN inspections (run after stats capture so they do not pollute)'
\echo '====================================================================='

\echo '--- BASELINE: app_user findByTenantIdAndEmail ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM app_user
 WHERE tenant_id = (SELECT tenant_id FROM _probe_tenant)
   AND email = 'admin@dev.fabt.org';

\echo '--- subscription.findByIdAndTenantId ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM subscription
 WHERE id = gen_random_uuid()
   AND tenant_id = (SELECT tenant_id FROM _probe_tenant);

\echo '--- api_key.findByIdAndTenantId ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM api_key
 WHERE id = gen_random_uuid()
   AND tenant_id = (SELECT tenant_id FROM _probe_tenant);

\echo '--- tenant_oauth2_provider.findByIdAndTenantId ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM tenant_oauth2_provider
 WHERE id = gen_random_uuid()
   AND tenant_id = (SELECT tenant_id FROM _probe_tenant);

\echo '--- audit_events findByTargetUserIdAndTenantId ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM audit_events
 WHERE target_user_id = (SELECT id FROM app_user
                          WHERE tenant_id = (SELECT tenant_id FROM _probe_tenant)
                            AND email = 'admin@dev.fabt.org')
   AND tenant_id = (SELECT tenant_id FROM _probe_tenant)
 ORDER BY timestamp DESC LIMIT 100;

\echo ''
\echo '====================================================================='
\echo 'INTERPRETATION GUIDE'
\echo '====================================================================='
\echo ''
\echo 'set_config delta (Q0 vs Q6):'
\echo '  approx_per_call_ms_Q6 - approx_per_call_ms_Q0 = D13 incremental cost'
\echo '  PASS: ≤ 0.01ms (Sam bench expectation per design D13)'
\echo '  FAIL: > 0.05ms — investigate (would be 5× the budget)'
\echo ''
\echo '  CAVEAT (warroom v2 #B): Q0/Q6 measure the SELECT half of the'
\echo '  applyRlsContext statement only. Production also runs SET ROLE'
\echo '  fabt_app in the same prepareStatement, which is identical pre-'
\echo '  and post-D13 and cancels out of the delta. The per_call_ms here'
\echo '  is NOT the full per-borrow cost — it is the set_config-only cost.'
\echo '  Use the delta (Q6 - Q0) to answer "did D13 add overhead?", NOT'
\echo '  the absolute Q6 number to answer "how much does each borrow cost?"'
\echo ''
\echo 'findByIdAndTenantId queries (Q2-Q4):'
\echo '  Compare mean_ms to Q1 baseline (findByTenantIdAndEmail).'
\echo '  PASS: within 2× of baseline (often FASTER because PK lookup +'
\echo '        post-filter beats secondary-index lookup).'
\echo '  FAIL: > 5× baseline OR EXPLAIN shows Seq Scan with > 1000 rows.'
\echo ''
\echo 'audit_events query (Q5):'
\echo '  Should use the V57 composite index (tenant_id, target_user_id,'
\echo '  timestamp DESC). EXPLAIN should show "Index Scan" using that index'
\echo '  with Buffers: shared hit ≤ 5 (the LIMIT 100 stops early).'
\echo '  FAIL: Bitmap Heap Scan reading > 1000 rows OR Sort node OR Seq Scan.'
\echo ''
\echo 'CAVEAT — dev-scale Seq Scan is NOT necessarily a regression.'
\echo '  Postgres prefers Seq Scan on tables under ~1000 rows because the'
\echo '  index lookup overhead exceeds the table read. Re-validate at NYC'
\echo '  scale via docs/performance/generate-nyc-loadtest.py before flagging'
\echo '  Seq Scan as a real plan-choice problem.'
\echo ''
\echo 'See docs/performance/nyc-scale-load-test-plan.md for production-scale'
\echo 'validation criteria.'
