-- V20: Eliminate dual source of truth for beds_total.
--
-- Problem: shelter_capacity.beds_total and bed_availability.beds_total can diverge,
-- causing the UI to display wrong available counts. bed_availability is the operational
-- source used in all calculations; shelter_capacity is redundant.
--
-- Strategy:
-- 1. Migrate capacity-only rows (no availability snapshot yet) into bed_availability
-- 2. Drop the shelter_capacity table and its RLS policy

-- Step 1: For each (shelter_id, population_type) in shelter_capacity that has NO
-- corresponding bed_availability row, create an initial snapshot.
-- Join shelter to get tenant_id (required by bed_availability).
INSERT INTO bed_availability (shelter_id, tenant_id, population_type, beds_total, beds_occupied, beds_on_hold, accepting_new_guests, snapshot_ts, updated_by)
SELECT
    sc.shelter_id,
    s.tenant_id,
    sc.population_type,
    sc.beds_total,
    0,              -- beds_occupied
    0,              -- beds_on_hold
    TRUE,           -- accepting_new_guests
    NOW(),          -- snapshot_ts
    'migration-v20' -- updated_by
FROM shelter_capacity sc
JOIN shelter s ON s.id = sc.shelter_id
WHERE NOT EXISTS (
    SELECT 1
    FROM bed_availability ba
    WHERE ba.shelter_id = sc.shelter_id
      AND ba.population_type = sc.population_type
);

-- Step 2: Drop RLS policy on shelter_capacity (created in V8)
DROP POLICY IF EXISTS dv_shelter_capacity_access ON shelter_capacity;

-- Step 3: Drop the table
DROP TABLE shelter_capacity;
