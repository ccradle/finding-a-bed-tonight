-- =====================================================================
-- A/B/C test harness for findOldestPendingByShelterIds index evaluation
-- =====================================================================
-- Uses pg_stat_statements (preloaded in docker-compose for this branch)
-- to capture aggregate stats across 100 runs per scenario, eliminating
-- single-run measurement noise. Scenarios:
--
--   A. No partial index (drop V55 temporarily) — baseline
--   B. V55 shape: (shelter_id, created_at) WHERE status='PENDING'
--   C. Alternative: (created_at) WHERE status='PENDING'
--
-- For each scenario: reset stats → run query 100× → capture mean, max,
-- stddev from pg_stat_statements.
--
-- Runs against the perf-probe-16-1-5 tenant at 3,500-row scale.
-- =====================================================================

\timing on

-- Helper: coordinator's shelter IDs (captured once)
\set tenant_slug 'perf-probe-16-1-5'

-- Prepare a reusable statement so pg_stat_statements fingerprints it as one query.
-- Pass shelter ID array as a parameter.
DEALLOCATE ALL;
PREPARE oldest_pending(uuid[]) AS
SELECT *
FROM referral_token
WHERE shelter_id = ANY($1)
  AND status = 'PENDING'
ORDER BY created_at ASC
LIMIT 1;

-- Capture the shelter IDs for our coordinator.
CREATE TEMP TABLE IF NOT EXISTS _ab_shelter_ids AS
SELECT ARRAY(
    SELECT ca.shelter_id
    FROM coordinator_assignment ca
    JOIN app_user u ON u.id = ca.user_id
    WHERE u.tenant_id = (SELECT id FROM tenant WHERE slug = 'perf-probe-16-1-5')
      AND 'COORDINATOR' = ANY(u.roles)
) AS shelter_ids;

\echo ''
\echo '====================================================================='
\echo 'SCENARIO A — no partial index (drop V55)'
\echo '====================================================================='
DROP INDEX IF EXISTS idx_referral_token_pending_shelter_created_at;
DROP INDEX IF EXISTS idx_referral_token_pending_created_at;
ANALYZE referral_token;

SELECT pg_stat_statements_reset();

-- Run query 100 times
DO $run$
DECLARE
    v_ids uuid[];
    v_result record;
BEGIN
    SELECT shelter_ids INTO v_ids FROM _ab_shelter_ids;
    FOR i IN 1..100 LOOP
        EXECUTE 'SELECT * FROM referral_token
                 WHERE shelter_id = ANY($1)
                   AND status = ''PENDING''
                 ORDER BY created_at ASC LIMIT 1'
        INTO v_result
        USING v_ids;
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
WHERE query ILIKE '%referral_token%shelter_id = ANY%PENDING%'
  AND query NOT ILIKE '%pg_stat_statements%'
ORDER BY total_exec_time DESC
LIMIT 3;

\echo ''
\echo '====================================================================='
\echo 'SCENARIO B — V55 shape: (shelter_id, created_at ASC) WHERE status=PENDING'
\echo '====================================================================='
CREATE INDEX idx_referral_token_pending_shelter_created_at
    ON referral_token (shelter_id, created_at ASC)
    WHERE status = 'PENDING';
ANALYZE referral_token;

SELECT pg_stat_statements_reset();

DO $run$
DECLARE
    v_ids uuid[];
    v_result record;
BEGIN
    SELECT shelter_ids INTO v_ids FROM _ab_shelter_ids;
    FOR i IN 1..100 LOOP
        EXECUTE 'SELECT * FROM referral_token
                 WHERE shelter_id = ANY($1)
                   AND status = ''PENDING''
                 ORDER BY created_at ASC LIMIT 1'
        INTO v_result
        USING v_ids;
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
WHERE query ILIKE '%referral_token%shelter_id = ANY%PENDING%'
  AND query NOT ILIKE '%pg_stat_statements%'
ORDER BY total_exec_time DESC
LIMIT 3;

\echo ''
\echo '====================================================================='
\echo 'SCENARIO C — alt shape: (created_at) WHERE status=PENDING'
\echo '====================================================================='
DROP INDEX idx_referral_token_pending_shelter_created_at;
CREATE INDEX idx_referral_token_pending_created_at
    ON referral_token (created_at ASC)
    WHERE status = 'PENDING';
ANALYZE referral_token;

SELECT pg_stat_statements_reset();

DO $run$
DECLARE
    v_ids uuid[];
    v_result record;
BEGIN
    SELECT shelter_ids INTO v_ids FROM _ab_shelter_ids;
    FOR i IN 1..100 LOOP
        EXECUTE 'SELECT * FROM referral_token
                 WHERE shelter_id = ANY($1)
                   AND status = ''PENDING''
                 ORDER BY created_at ASC LIMIT 1'
        INTO v_result
        USING v_ids;
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
WHERE query ILIKE '%referral_token%shelter_id = ANY%PENDING%'
  AND query NOT ILIKE '%pg_stat_statements%'
ORDER BY total_exec_time DESC
LIMIT 3;

\echo ''
\echo '====================================================================='
\echo 'Plan comparison: EXPLAIN ANALYZE of query with current index (C)'
\echo '====================================================================='
EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM referral_token
WHERE shelter_id = ANY((SELECT shelter_ids FROM _ab_shelter_ids))
  AND status = 'PENDING'
ORDER BY created_at ASC
LIMIT 1;

DROP INDEX IF EXISTS idx_referral_token_pending_created_at;
