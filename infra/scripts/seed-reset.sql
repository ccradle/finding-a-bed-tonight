-- Reset all seed-loaded data for a fresh reload.
-- Preserves Flyway schema and Spring Batch schema structure.
-- Run before seed-data.sql when shelter structure has changed.
--
-- Usage: psql -U fabt -d fabt -f infra/scripts/seed-reset.sql
-- Or:    ./dev-start.sh --fresh   (runs this automatically before seed load)

-- Activity data (demo-activity-seed.sql handles its own cleanup, but reset all here)
DELETE FROM daily_utilization_summary;
DELETE FROM bed_search_log;
DELETE FROM dv_referral_token;
DELETE FROM reservation;

-- Spring Batch (FK order)
DELETE FROM BATCH_STEP_EXECUTION_CONTEXT;
DELETE FROM BATCH_JOB_EXECUTION_CONTEXT;
DELETE FROM BATCH_STEP_EXECUTION;
DELETE FROM BATCH_JOB_EXECUTION_PARAMS;
DELETE FROM BATCH_JOB_EXECUTION;
DELETE FROM BATCH_JOB_INSTANCE;

-- Shelter data (FK order: availability → assignments → constraints → shelters)
DELETE FROM bed_availability;
DELETE FROM coordinator_assignment;
DELETE FROM shelter_constraints;
DELETE FROM shelter;

-- User data (keep tenant, clear users for full reset)
-- Uncomment if you also need to reset users:
-- DELETE FROM app_user;
-- DELETE FROM tenant_oauth2_provider;
-- DELETE FROM tenant;

\echo 'Seed data reset complete. Run seed-data.sql and demo-activity-seed.sql to reload.'
