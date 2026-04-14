-- =====================================================================
-- Seed for `findOldestPendingByShelterIds` EXPLAIN ANALYZE probe
-- (notification-deep-linking task 16.1.5)
-- =====================================================================
-- Purpose:
--   Seed a realistic pilot-scale dataset to EXPLAIN ANALYZE the query
--   `SELECT * FROM referral_token
--    WHERE shelter_id = ANY(?) AND status = 'PENDING'
--    ORDER BY created_at ASC LIMIT 1`
--   which powers the coordinator banner's `firstPending` routing hint
--   (design decision D-BP). The decision gate per task 16.1.5: if the
--   Sort step shows meaningful cost at pilot scale, add a partial index
--   `(shelter_id, created_at) WHERE status = 'PENDING'` in a Flyway
--   migration; otherwise document and move on.
--
-- Data shape (Elena Vasquez, warroom 2026-04-14):
--   1 tenant + 1 coordinator + 40 outreach workers
--   50 DV shelters, all assigned to the coordinator
--   500 PENDING referral_token rows, power-law distributed across shelters:
--     * 3 hot shelters  × 40 referrals each = 120
--     * 7 warm shelters × 20 referrals each = 140
--     * 15 cool         × 10 each            = 150
--     * 15 cold         ×  4 each            =  60
--     * 10 very cold    ×  3 each            =  30
--                                            ------
--                                   TOTAL:   500
--   created_at staggered across an 8-hour window — the Sort has
--   something to sort.
--
--   Constraint note: `uq_referral_token_pending` is a partial UNIQUE
--   index on (referring_user_id, shelter_id) WHERE status='PENDING'.
--   One outreach worker can have at most one PENDING referral per
--   shelter. The seed rotates across 40 outreach workers — matching
--   the max per-shelter count — so every (user, shelter) pair is
--   unique.
--
-- Determinism (Sam Okafor): setseed(0.42) — EXPLAIN output is comparable
--   across runs. Distribution is deterministic by shelter rank.
--
-- Idempotency (Sam): DELETE of the entire tenant at the top cascades
--   through shelter → referral_token + coordinator_assignment, and
--   app_user → coordinator_assignment. Re-running replaces the dataset.
--
-- Safety (Marcus Webb): tenant slug `perf-probe-16-1-5` is distinct
--   from any dev/demo/staging tenant; cleanup script filters on this
--   exact slug.
--
-- PII-free (Casey Drummond): shelter names start with `[PERF]`, emails
--   under @perf-probe.fabt.org, callback numbers are 919-555-XXXX.
--   If any row leaks into analytics, it is self-labeling as synthetic.
--
-- Schema version: built against Flyway HEAD V54 (2026-04-14). Columns
--   enumerated from \d output for tenant, app_user, shelter,
--   coordinator_assignment, referral_token — includes V47 escalation
--   columns (left NULL), V49 escalation_chain_broken (DEFAULT false),
--   V51 shelter_name (populated), V52 shelter.active (explicit true),
--   V53 shelter.deactivated_* (left NULL).
--
-- Usage:
--   docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt \
--     < docs/performance/seed-oldest-pending-probe.sql
-- =====================================================================

\echo '=== Seeding perf-probe-16-1-5 tenant for findOldestPendingByShelterIds EXPLAIN probe ==='

BEGIN;

-- Idempotency: comprehensive cleanup of ALL tenant-referencing tables
-- before re-creating. Matches the cleanup script. Missing even one
-- tenant-scoped table (discovered 2026-04-14: bed_search_log) causes
-- the tenant DELETE to FK-fail and silently roll back the whole
-- transaction — re-seeding then STACKS on top of the previous run.
CREATE TEMP TABLE IF NOT EXISTS _seed_cleanup_tenant_id AS
SELECT id FROM tenant WHERE slug = 'perf-probe-16-1-5';

DELETE FROM bed_search_log           WHERE tenant_id IN (SELECT id FROM _seed_cleanup_tenant_id);
DELETE FROM subscription             WHERE tenant_id IN (SELECT id FROM _seed_cleanup_tenant_id);
DELETE FROM surge_event              WHERE tenant_id IN (SELECT id FROM _seed_cleanup_tenant_id);
DELETE FROM daily_utilization_summary WHERE tenant_id IN (SELECT id FROM _seed_cleanup_tenant_id);
DELETE FROM import_log               WHERE tenant_id IN (SELECT id FROM _seed_cleanup_tenant_id);
DELETE FROM tenant_oauth2_provider   WHERE tenant_id IN (SELECT id FROM _seed_cleanup_tenant_id);
DELETE FROM hmis_outbox              WHERE tenant_id IN (SELECT id FROM _seed_cleanup_tenant_id);
DELETE FROM hmis_audit_log           WHERE tenant_id IN (SELECT id FROM _seed_cleanup_tenant_id);
DELETE FROM api_key                  WHERE tenant_id IN (SELECT id FROM _seed_cleanup_tenant_id);
DELETE FROM reservation              WHERE tenant_id IN (SELECT id FROM _seed_cleanup_tenant_id);
DELETE FROM referral_token           WHERE tenant_id IN (SELECT id FROM _seed_cleanup_tenant_id);
DELETE FROM escalation_policy        WHERE tenant_id IN (SELECT id FROM _seed_cleanup_tenant_id);
-- Cascades: shelter → bed_availability/coord_assignment/shelter_constraints/shelter_capacity
DELETE FROM shelter                  WHERE tenant_id IN (SELECT id FROM _seed_cleanup_tenant_id);
-- Cascades: app_user → notification/one_time_access_code/password_reset_token/user_oauth2_link/coord_assignment
DELETE FROM app_user                 WHERE tenant_id IN (SELECT id FROM _seed_cleanup_tenant_id);
DELETE FROM tenant                   WHERE slug = 'perf-probe-16-1-5';
DROP TABLE _seed_cleanup_tenant_id;

-- Deterministic RNG for any random() calls below.
SELECT setseed(0.42);

DO $seed$
DECLARE
    v_tenant_id    uuid := gen_random_uuid();
    v_coord_id     uuid := gen_random_uuid();
    v_shelter_count integer := 50;
    v_outreach_count integer := 40;  -- matches max pending-per-shelter
BEGIN
    -- 1. Tenant (no RLS on this table).
    INSERT INTO tenant (id, name, slug, config)
    VALUES (v_tenant_id, 'Perf Probe 16.1.5', 'perf-probe-16-1-5',
            '{"default_locale":"en","hold_duration_minutes":90}');

    -- 2. Session vars so shelter / referral_token RLS policies permit the
    --    INSERTs below:
    --      - shelter.dv_shelter_access  → requires app.dv_access='true'
    --        for dv_shelter=true rows
    --      - referral_token.dv_referral_token_access → requires a matching
    --        shelter row to EXIST (which we just inserted in the same
    --        transaction, same session)
    PERFORM set_config('app.tenant_id', v_tenant_id::text, true);
    PERFORM set_config('app.dv_access', 'true', true);

    -- 3. Users.
    --    - 1 COORDINATOR (for assignments).
    --    - 40 OUTREACH_WORKERs, rotated across referrals so every
    --      (referring_user_id, shelter_id) pair is unique under the
    --      partial unique index uq_referral_token_pending.
    --    No RLS on app_user. password_hash intentionally NULL (not login-tested).
    INSERT INTO app_user (id, tenant_id, email, display_name, roles, dv_access, status)
    VALUES
        (v_coord_id, v_tenant_id, 'perf-coord@perf-probe.fabt.org',
         '[PERF] Coordinator', ARRAY['COORDINATOR']::text[], true, 'ACTIVE');

    -- 40 outreach workers. Emails deterministic so cleanup + debugging are easy.
    CREATE TEMP TABLE IF NOT EXISTS _perf_outreach_ids (
        rank integer PRIMARY KEY,
        user_id uuid NOT NULL
    ) ON COMMIT DROP;

    WITH inserted_outreach AS (
        INSERT INTO app_user (id, tenant_id, email, display_name, roles, dv_access, status)
        SELECT
            gen_random_uuid(),
            v_tenant_id,
            'perf-outreach-' || lpad(gs::text, 3, '0') || '@perf-probe.fabt.org',
            '[PERF] Outreach ' || lpad(gs::text, 3, '0'),
            ARRAY['OUTREACH_WORKER']::text[],
            true,
            'ACTIVE'
        FROM generate_series(1, v_outreach_count) gs
        RETURNING id, email
    )
    INSERT INTO _perf_outreach_ids (rank, user_id)
    SELECT
        CAST(substring(email FROM 'perf-outreach-0*([0-9]+)@') AS integer),
        id
    FROM inserted_outreach;

    -- 4. Shelters — 50 DV shelters, all active. Capture IDs in a temp
    --    table for the next steps. rank column (1..50) drives the
    --    power-law referral distribution in step 6.
    CREATE TEMP TABLE IF NOT EXISTS _perf_shelter_ids (
        rank integer PRIMARY KEY,
        shelter_id uuid NOT NULL
    ) ON COMMIT DROP;

    WITH inserted AS (
        INSERT INTO shelter (
            id, tenant_id, name, address_street, address_city,
            address_state, address_zip, phone, dv_shelter, active
        )
        SELECT
            gen_random_uuid(),
            v_tenant_id,
            '[PERF] Shelter ' || lpad(gs::text, 3, '0'),
            gs || ' Perf St',
            'Raleigh', 'NC', '27601',
            '919-555-' || lpad(gs::text, 4, '0'),
            true,   -- dv_shelter
            true    -- V52 active
        FROM generate_series(1, v_shelter_count) gs
        RETURNING id, name
    )
    INSERT INTO _perf_shelter_ids (rank, shelter_id)
    SELECT
        CAST(substring(name FROM '([0-9]+)$') AS integer),
        id
    FROM inserted;

    -- 5. Coordinator assignments — coordinator is assigned to ALL 50 shelters.
    INSERT INTO coordinator_assignment (user_id, shelter_id)
    SELECT v_coord_id, shelter_id FROM _perf_shelter_ids;

    -- 6. PENDING referral tokens — power-law distribution.
    --    Per-shelter count driven by rank:
    --       rank 1-3   : 40 each (hot)    → 120
    --       rank 4-10  : 20 each (warm)   → 140
    --       rank 11-25 : 10 each (cool)   → 150
    --       rank 26-40 :  4 each (cold)   →  60
    --       rank 41-50 :  3 each (v cold) →  30
    --                             TOTAL:  = 500
    --
    --    created_at staggered across 8 hours so ORDER BY created_at has
    --    something to sort. expires_at is NOW + 8h so rows stay PENDING.
    WITH per_shelter AS (
        SELECT
            shelter_id,
            rank,
            CASE
                WHEN rank BETWEEN 1  AND 3  THEN 40
                WHEN rank BETWEEN 4  AND 10 THEN 20
                WHEN rank BETWEEN 11 AND 25 THEN 10
                WHEN rank BETWEEN 26 AND 40 THEN 4
                ELSE 3                           -- rank 41-50
            END AS n_pending
        FROM _perf_shelter_ids
    ),
    expanded AS (
        SELECT
            p.shelter_id,
            p.rank,
            gs AS idx_in_shelter
        FROM per_shelter p,
             LATERAL generate_series(1, p.n_pending) gs
    )
    INSERT INTO referral_token (
        shelter_id, tenant_id, referring_user_id, household_size,
        population_type, urgency, callback_number, status,
        created_at, expires_at, shelter_name
    )
    SELECT
        e.shelter_id,
        v_tenant_id,
        -- Rotate outreach workers by idx_in_shelter (1..40). Since max
        -- per-shelter is 40 and we have 40 outreach workers, every
        -- (referring_user_id, shelter_id) pair is unique under the
        -- partial unique index uq_referral_token_pending.
        o.user_id,
        -- household_size: deterministic 1-4 via modular, avoids random()
        1 + (e.rank + e.idx_in_shelter) % 4,
        'DV_SURVIVOR',
        CASE (e.rank + e.idx_in_shelter) % 3
            WHEN 0 THEN 'EMERGENCY'
            WHEN 1 THEN 'URGENT'
            ELSE 'STANDARD'
        END,
        '919-555-' || lpad(((e.rank * 100 + e.idx_in_shelter) % 10000)::text, 4, '0'),
        'PENDING',
        -- Stagger created_at across 8 hours. Deterministic formula: each row
        -- gets a distinct timestamp driven by rank + idx. Formula produces
        -- values in [0, 8) hours before NOW.
        NOW() - ((e.rank * 23 + e.idx_in_shelter * 7) % 480) * INTERVAL '1 minute',
        -- Expires 8 hours from NOW so all rows stay PENDING.
        NOW() + INTERVAL '8 hours',
        '[PERF] Shelter ' || lpad(e.rank::text, 3, '0')
    FROM expanded e
    JOIN _perf_outreach_ids o ON o.rank = e.idx_in_shelter;

    RAISE NOTICE 'Seeded tenant=% coordinator=% outreach_users=% shelters=% pending_tokens=500',
        v_tenant_id, v_coord_id, v_outreach_count, v_shelter_count;
END $seed$;

-- 7. Refresh planner stats so EXPLAIN uses current row counts.
ANALYZE tenant;
ANALYZE app_user;
ANALYZE shelter;
ANALYZE coordinator_assignment;
ANALYZE referral_token;

COMMIT;

-- 8. Verification output.
\echo ''
\echo '=== Verification ==='
SELECT 'shelters' AS object, COUNT(*) AS count
FROM shelter WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'perf-probe-16-1-5')
UNION ALL
SELECT 'coordinator_assignments',
       COUNT(*)
FROM coordinator_assignment ca
    JOIN app_user u ON u.id = ca.user_id
WHERE u.tenant_id = (SELECT id FROM tenant WHERE slug = 'perf-probe-16-1-5')
UNION ALL
SELECT 'pending_referral_tokens',
       COUNT(*)
FROM referral_token
WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'perf-probe-16-1-5')
  AND status = 'PENDING';

\echo ''
\echo '=== Per-shelter pending counts (top 10, should show hot→cold gradient) ==='
SELECT s.name, COUNT(r.id) AS pending
FROM shelter s
    LEFT JOIN referral_token r ON r.shelter_id = s.id AND r.status = 'PENDING'
WHERE s.tenant_id = (SELECT id FROM tenant WHERE slug = 'perf-probe-16-1-5')
GROUP BY s.id, s.name
ORDER BY pending DESC, s.name
LIMIT 10;

\echo ''
\echo '=== Seed complete. Run the EXPLAIN probe next. ==='
