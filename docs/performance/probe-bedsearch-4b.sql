-- =====================================================================
-- DB-floor measurement harness for BedSearch canonical query
-- =====================================================================
-- Canonical pattern per feedback_pgstat_for_index_validation.md +
-- docs/performance/probe-ab-test.sql. Uses pg_stat_statements to capture
-- aggregate DB-side latency for BedAvailabilityRepository.findLatestByTenantId
-- across 100 identical calls, eliminating single-run noise.
--
-- WHAT THIS MEASURES
--   The SQL-only floor latency of the recursive skip-scan over
--   bed_availability that BedSearchService.doSearch calls on every
--   cache miss. This is the lower bound on p95 search latency — the
--   number BedSearch approaches as cache hit-rate drops to 0%.
--
-- WHAT THIS DOES NOT MEASURE
--   The effect of the task 4.b migration (CacheService →
--   TenantScopedCacheService). The wrapper's overhead is a microseconds-
--   scale envelope allocation + one Micrometer counter update; it is
--   separately exercised by the unit-level TenantScopedCacheServiceUnitTest
--   + integration-level Task4bCacheHitRateTest (backend/src/test/java/...).
--   This harness deliberately bypasses Spring to isolate the DB-side
--   cost from the app-side cost.
--
-- WHEN TO RUN THIS
--   Before/after any change to bed_availability indexing, partitioning,
--   or the recursive CTE shape. The migration itself (4.b) does NOT
--   change any of these — run this harness on a future PR that does.
--
-- HOW TO INTERPRET THE RESULTS
--   mean_exec_time is the DB-side floor. If prod p95 sits at 2× or
--   more above mean_exec_time, the gap is app-side (serialization,
--   JDBC row mapping, Spring handler cost) — cache is the mitigation.
--   If prod p95 is within 1.2× of this floor, most latency IS DB-side
--   and cache hit-rate is the primary lever.
--
-- PGSTATSTATEMENTS FINGERPRINT CONFIRMATION
--   pg_stat_statements fingerprints by normalised query tree. PREPARE
--   + EXECUTE sometimes register under a different queryid than
--   JDBC-issued text-mode statements. The first block below resets
--   pgss, issues ONE probe call, and reports the observed fingerprint
--   so the ILIKE filter can be sanity-checked against what actually
--   appeared. If the fingerprint shape is unexpected, halt and
--   investigate before trusting the 100-call aggregates.
--
-- Run against a pre-seeded probe tenant at demo scale.
-- =====================================================================

\timing on

\set tenant_slug 'perf-probe-bedsearch-4b'

-- Capture the probe tenant's UUID (must be pre-seeded).
CREATE TEMP TABLE IF NOT EXISTS _4b_probe AS
SELECT id AS tenant_id
FROM tenant
WHERE slug = 'perf-probe-bedsearch-4b';

-- Guard: halt if tenant is missing rather than producing misleading
-- all-zero stats.
DO $guard$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM _4b_probe WHERE tenant_id IS NOT NULL) THEN
        RAISE EXCEPTION 'Probe tenant perf-probe-bedsearch-4b not found. '
            'Seed the tenant with representative bed_availability rows '
            'before running this harness.';
    END IF;
END $guard$;

-- Prepare the recursive skip-scan query exactly as issued by
-- BedAvailabilityRepository.findLatestByTenantId so pg_stat_statements
-- fingerprints it as one query across all scenarios.
DEALLOCATE ALL;
PREPARE find_latest_by_tenant(uuid) AS
WITH RECURSIVE combos AS (
    (SELECT shelter_id, population_type
     FROM bed_availability
     WHERE tenant_id = $1
     ORDER BY shelter_id, population_type
     LIMIT 1)
    UNION ALL
    (SELECT ba.shelter_id, ba.population_type
     FROM combos c, LATERAL (
         SELECT shelter_id, population_type
         FROM bed_availability
         WHERE tenant_id = $1
           AND (shelter_id, population_type) > (c.shelter_id, c.population_type)
         ORDER BY shelter_id, population_type
         LIMIT 1
     ) ba)
)
SELECT ba.*
FROM combos c
JOIN LATERAL (
    SELECT * FROM bed_availability
    WHERE tenant_id = $1
      AND shelter_id = c.shelter_id
      AND population_type = c.population_type
    ORDER BY snapshot_ts DESC
    LIMIT 1
) ba ON true;

\echo ''
\echo '====================================================================='
\echo 'STEP 0  —  Fingerprint confirmation'
\echo '---------------------------------------------------------------------'
\echo 'Reset pgss, issue ONE probe call, inspect the observed fingerprint.'
\echo 'If the query text the harness sees does not match the ILIKE filter'
\echo 'used in later steps, the aggregate stats will be unreliable.'
\echo '====================================================================='

SELECT pg_stat_statements_reset();

DO $probe$
DECLARE
    v_tid uuid;
    v_result record;
BEGIN
    SELECT tenant_id INTO v_tid FROM _4b_probe;
    FOR v_result IN EXECUTE 'EXECUTE find_latest_by_tenant($1)' USING v_tid
    LOOP
        -- discard
    END LOOP;
END $probe$;

\echo ''
\echo '--- Observed pg_stat_statements fingerprints after 1 probe call ----'
SELECT
    queryid,
    calls,
    substring(query FROM 1 FOR 120) AS query_snippet
FROM pg_stat_statements
WHERE query NOT ILIKE '%pg_stat_statements%'
ORDER BY calls DESC
LIMIT 5;

\echo ''
\echo '====================================================================='
\echo 'STEP 1  —  DB-floor at 100-call load'
\echo '---------------------------------------------------------------------'
\echo 'Reset pgss, issue 100 probe calls, read aggregate stats. mean_exec_time'
\echo 'is the DB-only cost per invocation — what BedSearch pays on cache miss.'
\echo '====================================================================='

SELECT pg_stat_statements_reset();

DO $load$
DECLARE
    v_tid uuid;
    v_result record;
BEGIN
    SELECT tenant_id INTO v_tid FROM _4b_probe;
    FOR i IN 1..100 LOOP
        FOR v_result IN EXECUTE 'EXECUTE find_latest_by_tenant($1)' USING v_tid
        LOOP
            -- discard
        END LOOP;
    END LOOP;
END $load$;

\echo ''
\echo '--- 100-call DB-floor stats ----------------------------------------'
SELECT
    substring(query FROM 1 FOR 100) AS query_snippet,
    calls,
    round(mean_exec_time::numeric, 4) AS mean_ms,
    round(stddev_exec_time::numeric, 4) AS stddev_ms,
    round(max_exec_time::numeric, 4) AS max_ms,
    round(total_exec_time::numeric, 2) AS total_ms
FROM pg_stat_statements
WHERE query ILIKE '%WITH RECURSIVE combos%bed_availability%'
  AND query NOT ILIKE '%pg_stat_statements%'
ORDER BY total_exec_time DESC
LIMIT 3;

\echo ''
\echo '====================================================================='
\echo 'Interpretation'
\echo '---------------------------------------------------------------------'
\echo '  mean_ms is the DB-floor BedSearch pays per cache miss.'
\echo '  Paste into PR description + compare against prod observed p95 at'
\echo '  /api/v1/queries/beds. If prod p95 << this floor × cache-miss-rate,'
\echo '  the wrapper is doing its job. If prod p95 >> floor × 1.2 + an'
\echo '  app-side constant, investigate JDBC / Spring handler overhead.'
\echo ''
\echo 'This harness is a DB-side floor. To measure the wrapper itself, run:'
\echo '  mvn -Dtest=Task4bCacheHitRateTest test'
\echo '  mvn -Dtest=TenantScopedCacheServiceUnitTest test'
\echo '  mvn -Dtest=Tenant4bMigrationCrossTenantAttackTest test'
\echo 'Each measures a different surface (hit-rate contract, unit-level'
\echo 'wrapper gate, cross-tenant envelope verification).'
\echo '====================================================================='

DEALLOCATE find_latest_by_tenant;
