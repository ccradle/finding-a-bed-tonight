-- Demo Activity Seed — generates 28 days of realistic activity data
-- Run after seed-data.sql: psql -U fabt -d fabt -f demo-activity-seed.sql
--
-- Idempotent: DELETEs from activity tables before inserting.
-- Does NOT touch shelter, shelter_constraints, or app_user tables.
--
-- Generates:
--   - ~4 bed_availability snapshots per shelter per day (28 days)
--   - ~80 bed_search_log entries per day with zero-result variation
--   - ~18 reservations per day with realistic lifecycle distribution
--   - daily_utilization_summary pre-aggregated from snapshots
--   - Spring Batch job execution history (28 daily agg + 4 hmis push + 1 hic export)

-- ============================================================================
-- Constants
-- ============================================================================
DO $$
DECLARE
    v_tenant_id UUID := 'a0000000-0000-0000-0000-000000000001';
    v_outreach_user_id UUID := 'b0000000-0000-0000-0000-000000000002';
    v_day DATE;
    v_hour INT;
    v_shelter RECORD;
    v_base_occupancy FLOAT;
    v_day_factor FLOAT;
    v_noise FLOAT;
    v_occupied INT;
    v_on_hold INT;
    v_snapshot_ts TIMESTAMPTZ;
    v_search_count INT;
    v_zero_count INT;
    v_pop_types TEXT[] := ARRAY['SINGLE_ADULT', 'FAMILY_WITH_CHILDREN', 'VETERAN', 'YOUTH_18_24', 'WOMEN_ONLY'];
    v_pop_type TEXT;
    v_res_count INT;
    v_res_status TEXT;
    v_res_statuses TEXT[] := ARRAY['CONFIRMED', 'CONFIRMED', 'CONFIRMED', 'CONFIRMED', 'CONFIRMED', 'CONFIRMED', 'CONFIRMED', 'EXPIRED', 'EXPIRED', 'CANCELLED'];
    v_hold_minutes INT;
    v_created_at TIMESTAMPTZ;
    v_job_exec_id BIGINT;
    v_step_exec_id BIGINT;
    v_job_instance_id BIGINT;
BEGIN

-- ============================================================================
-- Cleanup (idempotent — preserve shelter/user data)
-- ============================================================================
DELETE FROM daily_utilization_summary WHERE tenant_id = v_tenant_id;
DELETE FROM bed_search_log WHERE tenant_id = v_tenant_id;

-- Preserve any reservations created in the last hour (active test holds)
DELETE FROM reservation WHERE tenant_id = v_tenant_id AND created_at < NOW() - INTERVAL '1 hour';

-- Spring Batch tables (FK order matters)
DELETE FROM BATCH_STEP_EXECUTION_CONTEXT;
DELETE FROM BATCH_JOB_EXECUTION_CONTEXT;
DELETE FROM BATCH_STEP_EXECUTION;
DELETE FROM BATCH_JOB_EXECUTION_PARAMS;
DELETE FROM BATCH_JOB_EXECUTION;
DELETE FROM BATCH_JOB_INSTANCE;

-- Delete old demo snapshots (keep the original seed snapshots from seed-data.sql)
DELETE FROM bed_availability
WHERE tenant_id = v_tenant_id
  AND updated_by = 'demo-seed'
  AND snapshot_ts < NOW() - INTERVAL '1 hour';

-- ============================================================================
-- Generate 28 days of bed_availability snapshots
-- ============================================================================
FOR v_day IN SELECT generate_series(
    CURRENT_DATE - INTERVAL '28 days',
    CURRENT_DATE - INTERVAL '1 day',
    INTERVAL '1 day'
)::DATE
LOOP
    -- Day-of-week factor: weekdays busier, weekends quieter
    v_day_factor := CASE EXTRACT(DOW FROM v_day)
        WHEN 0 THEN -0.08  -- Sunday
        WHEN 6 THEN -0.05  -- Saturday
        WHEN 5 THEN -0.02  -- Friday
        ELSE 0.10           -- Mon-Thu
    END;

    -- 4 snapshots per day (~6h apart: 6am, noon, 6pm, midnight)
    FOREACH v_hour IN ARRAY ARRAY[6, 12, 18, 23]
    LOOP
        -- Evening snapshots show higher occupancy
        v_noise := (random() - 0.5) * 0.10;

        FOR v_shelter IN
            SELECT ba.shelter_id, ba.population_type, ba.beds_total,
                   s.name AS shelter_name
            FROM bed_availability ba
            JOIN shelter s ON s.id = ba.shelter_id
            WHERE ba.tenant_id = v_tenant_id
              AND ba.updated_by = 'seed'
        LOOP
            -- Base occupancy varies by shelter (story-driven)
            v_base_occupancy := CASE
                WHEN v_shelter.shelter_name = 'Downtown Warming Station' THEN 0.92  -- High demand
                WHEN v_shelter.shelter_name = 'New Beginnings Family Shelter' THEN 0.35  -- Underutilized
                WHEN v_shelter.shelter_name = 'South Wilmington Haven' AND v_shelter.population_type = 'SINGLE_ADULT' THEN 0.88
                WHEN v_shelter.shelter_name = 'Oak City Emergency Shelter' THEN 0.75
                WHEN v_shelter.shelter_name = 'Safe Haven DV Shelter' THEN 0.55
                ELSE 0.65
            END;

            -- Evening boost
            IF v_hour >= 18 THEN
                v_base_occupancy := v_base_occupancy + 0.08;
            END IF;

            v_occupied := GREATEST(0, LEAST(
                v_shelter.beds_total,
                ROUND((v_base_occupancy + v_day_factor + v_noise) * v_shelter.beds_total)::INT
            ));

            v_on_hold := CASE WHEN v_hour >= 18 AND random() > 0.6 THEN LEAST(3, v_shelter.beds_total - v_occupied) ELSE 0 END;
            v_snapshot_ts := (v_day + (v_hour || ' hours')::INTERVAL)::TIMESTAMPTZ + (random() * INTERVAL '30 minutes');

            INSERT INTO bed_availability (
                shelter_id, tenant_id, population_type, beds_total,
                beds_occupied, beds_on_hold, accepting_new_guests,
                snapshot_ts, updated_by, notes
            ) VALUES (
                v_shelter.shelter_id, v_tenant_id, v_shelter.population_type,
                v_shelter.beds_total, v_occupied, v_on_hold,
                (v_occupied + v_on_hold) < v_shelter.beds_total,
                v_snapshot_ts, 'demo-seed', NULL
            ) ON CONFLICT ON CONSTRAINT uq_bed_avail_shelter_pop_ts DO NOTHING;
        END LOOP;
    END LOOP;

    -- ========================================================================
    -- Generate bed_search_log entries for this day
    -- ========================================================================
    v_search_count := 50 + (random() * 70)::INT;  -- 50-120 searches
    IF EXTRACT(DOW FROM v_day) IN (0, 6) THEN
        v_search_count := (v_search_count * 0.65)::INT;  -- Weekend dip
    END IF;

    FOR i IN 1..v_search_count LOOP
        v_pop_type := CASE (random() * 10)::INT
            WHEN 0 THEN 'SINGLE_ADULT'
            WHEN 1 THEN 'SINGLE_ADULT'
            WHEN 2 THEN 'SINGLE_ADULT'
            WHEN 3 THEN 'SINGLE_ADULT'
            WHEN 4 THEN 'FAMILY_WITH_CHILDREN'
            WHEN 5 THEN 'FAMILY_WITH_CHILDREN'
            WHEN 6 THEN 'VETERAN'
            WHEN 7 THEN NULL  -- unfiltered
            WHEN 8 THEN 'WOMEN_ONLY'
            ELSE 'YOUTH_18_24'
        END;

        -- Zero-result searches: 8-15%, higher on weeknight evenings
        v_zero_count := CASE
            WHEN random() < 0.12 THEN 0
            ELSE 3 + (random() * 8)::INT
        END;

        INSERT INTO bed_search_log (id, tenant_id, population_type, results_count, search_ts)
        VALUES (
            gen_random_uuid(), v_tenant_id, v_pop_type, v_zero_count,
            (v_day + (6 + random() * 17)::INT * INTERVAL '1 hour' + random() * INTERVAL '59 minutes')::TIMESTAMPTZ
        );
    END LOOP;

    -- ========================================================================
    -- Generate reservations for this day
    -- ========================================================================
    v_res_count := 10 + (random() * 15)::INT;  -- 10-25 per day

    FOR i IN 1..v_res_count LOOP
        v_res_status := v_res_statuses[1 + (random() * 9)::INT];
        v_hold_minutes := 20 + (random() * 70)::INT;  -- 20-90 min (matches 90-min default)
        v_created_at := (v_day + (8 + random() * 14)::INT * INTERVAL '1 hour')::TIMESTAMPTZ;

        -- Only the most recent day can have HELD status
        IF v_res_status = 'CONFIRMED' AND v_day < CURRENT_DATE - INTERVAL '1 day' THEN
            v_res_status := 'CONFIRMED';
        END IF;

        INSERT INTO reservation (
            id, shelter_id, tenant_id, population_type, user_id,
            status, expires_at, confirmed_at, cancelled_at, created_at, notes
        ) VALUES (
            gen_random_uuid(),
            ('d0000000-0000-0000-0000-00000000000' || (1 + (random() * 7)::INT))::UUID,
            v_tenant_id,
            v_pop_types[1 + (random() * 4)::INT],
            v_outreach_user_id,
            v_res_status,
            v_created_at + (v_hold_minutes || ' minutes')::INTERVAL,
            CASE WHEN v_res_status = 'CONFIRMED' THEN v_created_at + ((v_hold_minutes - 5) || ' minutes')::INTERVAL END,
            CASE WHEN v_res_status IN ('CANCELLED', 'EXPIRED') THEN v_created_at + (v_hold_minutes || ' minutes')::INTERVAL END,
            v_created_at,
            NULL
        );
    END LOOP;

    -- ========================================================================
    -- Pre-compute daily_utilization_summary for this day
    -- ========================================================================
    INSERT INTO daily_utilization_summary (
        id, tenant_id, shelter_id, population_type, summary_date,
        avg_utilization, max_occupied, min_available, snapshot_count
    )
    SELECT
        gen_random_uuid(),
        v_tenant_id,
        ba.shelter_id,
        ba.population_type,
        v_day,
        AVG(CASE WHEN ba.beds_total > 0 THEN ba.beds_occupied::FLOAT / ba.beds_total ELSE 0 END),
        MAX(ba.beds_occupied),
        MIN(ba.beds_total - ba.beds_occupied - ba.beds_on_hold),
        COUNT(*)
    FROM bed_availability ba
    WHERE ba.tenant_id = v_tenant_id
      AND DATE(ba.snapshot_ts) = v_day
      AND ba.updated_by = 'demo-seed'
    GROUP BY ba.shelter_id, ba.population_type
    ON CONFLICT ON CONSTRAINT uq_daily_util_tenant_shelter_pop_date
    DO UPDATE SET
        avg_utilization = EXCLUDED.avg_utilization,
        max_occupied = EXCLUDED.max_occupied,
        min_available = EXCLUDED.min_available,
        snapshot_count = EXCLUDED.snapshot_count;

END LOOP;

-- ============================================================================
-- Spring Batch job execution history
-- ============================================================================

-- Reset sequences
ALTER SEQUENCE BATCH_JOB_SEQ RESTART WITH 1;
ALTER SEQUENCE BATCH_JOB_EXECUTION_SEQ RESTART WITH 1;
ALTER SEQUENCE BATCH_STEP_EXECUTION_SEQ RESTART WITH 1;

-- dailyAggregation: 28 completed executions
INSERT INTO BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY)
VALUES (nextval('BATCH_JOB_SEQ'), 0, 'dailyAggregation', 'default');

v_job_instance_id := currval('BATCH_JOB_SEQ');

FOR v_day IN SELECT generate_series(
    CURRENT_DATE - INTERVAL '28 days',
    CURRENT_DATE - INTERVAL '1 day',
    INTERVAL '1 day'
)::DATE
LOOP
    v_job_exec_id := nextval('BATCH_JOB_EXECUTION_SEQ');
    v_step_exec_id := nextval('BATCH_STEP_EXECUTION_SEQ');
    v_snapshot_ts := (v_day + INTERVAL '3 hours')::TIMESTAMPTZ;  -- Runs at 3 AM

    INSERT INTO BATCH_JOB_EXECUTION (
        JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID,
        CREATE_TIME, START_TIME, END_TIME, STATUS, EXIT_CODE, EXIT_MESSAGE, LAST_UPDATED
    ) VALUES (
        v_job_exec_id, 0, v_job_instance_id,
        v_snapshot_ts, v_snapshot_ts, v_snapshot_ts + INTERVAL '2 seconds',
        'COMPLETED', 'COMPLETED', NULL, v_snapshot_ts + INTERVAL '2 seconds'
    );

    INSERT INTO BATCH_JOB_EXECUTION_PARAMS (JOB_EXECUTION_ID, PARAMETER_NAME, PARAMETER_TYPE, PARAMETER_VALUE, IDENTIFYING)
    VALUES (v_job_exec_id, 'scheduledTime', 'java.lang.String', v_snapshot_ts::TEXT, 'Y');

    INSERT INTO BATCH_STEP_EXECUTION (
        STEP_EXECUTION_ID, VERSION, STEP_NAME, JOB_EXECUTION_ID,
        CREATE_TIME, START_TIME, END_TIME, STATUS,
        COMMIT_COUNT, READ_COUNT, FILTER_COUNT, WRITE_COUNT,
        READ_SKIP_COUNT, WRITE_SKIP_COUNT, PROCESS_SKIP_COUNT, ROLLBACK_COUNT,
        EXIT_CODE, EXIT_MESSAGE, LAST_UPDATED
    ) VALUES (
        v_step_exec_id, 0, 'aggregateSnapshots', v_job_exec_id,
        v_snapshot_ts, v_snapshot_ts, v_snapshot_ts + INTERVAL '1 second',
        'COMPLETED',
        12 + (random() * 5)::INT, 48 + (random() * 20)::INT, 0, 12 + (random() * 5)::INT,
        0, 0, 0, 0,
        'COMPLETED', NULL, v_snapshot_ts + INTERVAL '1 second'
    );

    INSERT INTO BATCH_STEP_EXECUTION_CONTEXT (STEP_EXECUTION_ID, SHORT_CONTEXT, SERIALIZED_CONTEXT)
    VALUES (v_step_exec_id, 'rO0ABXNyABFqYXZhLnV0aWwuSGFzaE1hcAUH2sHDFmDRAwACRgAKbG9hZEZhY3RvckkACXRocmVzaG9sZHhwP0AAAAAAAAB3CAAAABAAAAAAeA==', NULL);

    INSERT INTO BATCH_JOB_EXECUTION_CONTEXT (JOB_EXECUTION_ID, SHORT_CONTEXT, SERIALIZED_CONTEXT)
    VALUES (v_job_exec_id, 'rO0ABXNyABFqYXZhLnV0aWwuSGFzaE1hcAUH2sHDFmDRAwACRgAKbG9hZEZhY3RvckkACXRocmVzaG9sZHhwP0AAAAAAAAB3CAAAABAAAAAAeA==', NULL);
END LOOP;

-- hmisPush: 4 executions (3 completed, 1 failed)
INSERT INTO BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY)
VALUES (nextval('BATCH_JOB_SEQ'), 0, 'hmisPush', 'default');

v_job_instance_id := currval('BATCH_JOB_SEQ');

FOR i IN 1..4 LOOP
    v_job_exec_id := nextval('BATCH_JOB_EXECUTION_SEQ');
    v_step_exec_id := nextval('BATCH_STEP_EXECUTION_SEQ');
    v_snapshot_ts := (CURRENT_DATE - ((4 - i) * 7) * INTERVAL '1 day' + INTERVAL '6 hours')::TIMESTAMPTZ;

    INSERT INTO BATCH_JOB_EXECUTION (
        JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID,
        CREATE_TIME, START_TIME, END_TIME, STATUS, EXIT_CODE, EXIT_MESSAGE, LAST_UPDATED
    ) VALUES (
        v_job_exec_id, 0, v_job_instance_id,
        v_snapshot_ts, v_snapshot_ts, v_snapshot_ts + INTERVAL '5 seconds',
        CASE WHEN i = 3 THEN 'FAILED' ELSE 'COMPLETED' END,
        CASE WHEN i = 3 THEN 'FAILED' ELSE 'COMPLETED' END,
        CASE WHEN i = 3 THEN 'Connection refused: HMIS vendor endpoint unreachable' ELSE NULL END,
        v_snapshot_ts + INTERVAL '5 seconds'
    );

    INSERT INTO BATCH_JOB_EXECUTION_PARAMS (JOB_EXECUTION_ID, PARAMETER_NAME, PARAMETER_TYPE, PARAMETER_VALUE, IDENTIFYING)
    VALUES (v_job_exec_id, 'scheduledTime', 'java.lang.String', v_snapshot_ts::TEXT, 'Y');

    -- createOutboxEntries step
    INSERT INTO BATCH_STEP_EXECUTION (
        STEP_EXECUTION_ID, VERSION, STEP_NAME, JOB_EXECUTION_ID,
        CREATE_TIME, START_TIME, END_TIME, STATUS,
        COMMIT_COUNT, READ_COUNT, FILTER_COUNT, WRITE_COUNT,
        READ_SKIP_COUNT, WRITE_SKIP_COUNT, PROCESS_SKIP_COUNT, ROLLBACK_COUNT,
        EXIT_CODE, EXIT_MESSAGE, LAST_UPDATED
    ) VALUES (
        v_step_exec_id, 0, 'createOutboxEntries', v_job_exec_id,
        v_snapshot_ts, v_snapshot_ts, v_snapshot_ts + INTERVAL '2 seconds',
        'COMPLETED', 1, 10, 0, 10, 0, 0, 0, 0,
        'COMPLETED', NULL, v_snapshot_ts + INTERVAL '2 seconds'
    );

    INSERT INTO BATCH_STEP_EXECUTION_CONTEXT (STEP_EXECUTION_ID, SHORT_CONTEXT, SERIALIZED_CONTEXT)
    VALUES (v_step_exec_id, 'rO0ABXNyABFqYXZhLnV0aWwuSGFzaE1hcAUH2sHDFmDRAwACRgAKbG9hZEZhY3RvckkACXRocmVzaG9sZHhwP0AAAAAAAAB3CAAAABAAAAAAeA==', NULL);

    -- processOutboxEntries step
    v_step_exec_id := nextval('BATCH_STEP_EXECUTION_SEQ');
    INSERT INTO BATCH_STEP_EXECUTION (
        STEP_EXECUTION_ID, VERSION, STEP_NAME, JOB_EXECUTION_ID,
        CREATE_TIME, START_TIME, END_TIME, STATUS,
        COMMIT_COUNT, READ_COUNT, FILTER_COUNT, WRITE_COUNT,
        READ_SKIP_COUNT, WRITE_SKIP_COUNT, PROCESS_SKIP_COUNT, ROLLBACK_COUNT,
        EXIT_CODE, EXIT_MESSAGE, LAST_UPDATED
    ) VALUES (
        v_step_exec_id, 0, 'processOutboxEntries', v_job_exec_id,
        v_snapshot_ts + INTERVAL '2 seconds', v_snapshot_ts + INTERVAL '2 seconds',
        v_snapshot_ts + INTERVAL '5 seconds',
        CASE WHEN i = 3 THEN 'FAILED' ELSE 'COMPLETED' END,
        CASE WHEN i = 3 THEN 0 ELSE 1 END,
        10, 0,
        CASE WHEN i = 3 THEN 0 ELSE 10 END,
        0, 0, 0,
        CASE WHEN i = 3 THEN 1 ELSE 0 END,
        CASE WHEN i = 3 THEN 'FAILED' ELSE 'COMPLETED' END,
        CASE WHEN i = 3 THEN 'Connection refused: HMIS vendor endpoint unreachable' ELSE NULL END,
        v_snapshot_ts + INTERVAL '5 seconds'
    );

    INSERT INTO BATCH_STEP_EXECUTION_CONTEXT (STEP_EXECUTION_ID, SHORT_CONTEXT, SERIALIZED_CONTEXT)
    VALUES (v_step_exec_id, 'rO0ABXNyABFqYXZhLnV0aWwuSGFzaE1hcAUH2sHDFmDRAwACRgAKbG9hZEZhY3RvckkACXRocmVzaG9sZHhwP0AAAAAAAAB3CAAAABAAAAAAeA==', NULL);

    INSERT INTO BATCH_JOB_EXECUTION_CONTEXT (JOB_EXECUTION_ID, SHORT_CONTEXT, SERIALIZED_CONTEXT)
    VALUES (v_job_exec_id, 'rO0ABXNyABFqYXZhLnV0aWwuSGFzaE1hcAUH2sHDFmDRAwACRgAKbG9hZEZhY3RvckkACXRocmVzaG9sZHhwP0AAAAAAAAB3CAAAABAAAAAAeA==', NULL);
END LOOP;

-- hicExport: 1 completed execution
INSERT INTO BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY)
VALUES (nextval('BATCH_JOB_SEQ'), 0, 'hicExport', 'default');

v_job_instance_id := currval('BATCH_JOB_SEQ');
v_job_exec_id := nextval('BATCH_JOB_EXECUTION_SEQ');
v_step_exec_id := nextval('BATCH_STEP_EXECUTION_SEQ');
v_snapshot_ts := (CURRENT_DATE - INTERVAL '7 days' + INTERVAL '4 hours')::TIMESTAMPTZ;

INSERT INTO BATCH_JOB_EXECUTION (
    JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID,
    CREATE_TIME, START_TIME, END_TIME, STATUS, EXIT_CODE, EXIT_MESSAGE, LAST_UPDATED
) VALUES (
    v_job_exec_id, 0, v_job_instance_id,
    v_snapshot_ts, v_snapshot_ts, v_snapshot_ts + INTERVAL '3 seconds',
    'COMPLETED', 'COMPLETED', NULL, v_snapshot_ts + INTERVAL '3 seconds'
);

INSERT INTO BATCH_JOB_EXECUTION_PARAMS (JOB_EXECUTION_ID, PARAMETER_NAME, PARAMETER_TYPE, PARAMETER_VALUE, IDENTIFYING)
VALUES (v_job_exec_id, 'reportDate', 'java.lang.String', (CURRENT_DATE - INTERVAL '7 days')::TEXT, 'Y');

INSERT INTO BATCH_STEP_EXECUTION (
    STEP_EXECUTION_ID, VERSION, STEP_NAME, JOB_EXECUTION_ID,
    CREATE_TIME, START_TIME, END_TIME, STATUS,
    COMMIT_COUNT, READ_COUNT, FILTER_COUNT, WRITE_COUNT,
    READ_SKIP_COUNT, WRITE_SKIP_COUNT, PROCESS_SKIP_COUNT, ROLLBACK_COUNT,
    EXIT_CODE, EXIT_MESSAGE, LAST_UPDATED
) VALUES (
    v_step_exec_id, 0, 'generateHicCsv', v_job_exec_id,
    v_snapshot_ts, v_snapshot_ts, v_snapshot_ts + INTERVAL '1 second',
    'COMPLETED', 1, 10, 0, 1, 0, 0, 0, 0,
    'COMPLETED', NULL, v_snapshot_ts + INTERVAL '1 second'
);

INSERT INTO BATCH_STEP_EXECUTION_CONTEXT (STEP_EXECUTION_ID, SHORT_CONTEXT, SERIALIZED_CONTEXT)
VALUES (v_step_exec_id, 'rO0ABXNyABFqYXZhLnV0aWwuSGFzaE1hcAUH2sHDFmDRAwACRgAKbG9hZEZhY3RvckkACXRocmVzaG9sZHhwP0AAAAAAAAB3CAAAABAAAAAAeA==', NULL);

INSERT INTO BATCH_JOB_EXECUTION_CONTEXT (JOB_EXECUTION_ID, SHORT_CONTEXT, SERIALIZED_CONTEXT)
VALUES (v_job_exec_id, 'rO0ABXNyABFqYXZhLnV0aWwuSGFzaE1hcAUH2sHDFmDRAwACRgAKbG9hZEZhY3RvckkACXRocmVzaG9sZHhwP0AAAAAAAAB3CAAAABAAAAAAeA==', NULL);

RAISE NOTICE 'Demo activity seed complete: 28 days of snapshots, searches, reservations, summaries, and batch history';

END $$;
