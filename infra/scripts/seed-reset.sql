-- Reset all seed-loaded data for a fresh reload.
-- Preserves Flyway schema, tenant structure, and Spring Batch schema.
-- Run before seed-data.sql when user or shelter state needs a clean slate.
--
-- Uses DELETE (DML) not TRUNCATE (DDL) — safe to run while backend is up.
-- FK dependency order: delete leaves first, then parents.
--
-- Usage: psql -U fabt -d fabt -f infra/scripts/seed-reset.sql
-- Or:    ./dev-start.sh --fresh   (runs this automatically before seed load)

-- ============================================================
-- Layer 1: Leaf tables (no children depend on these)
-- ============================================================

DELETE FROM audit_events;
DELETE FROM bed_search_log;
DELETE FROM daily_utilization_summary;
DELETE FROM hmis_audit_log;
DELETE FROM hmis_outbox;
DELETE FROM import_log;
DELETE FROM bed_availability;
DELETE FROM shelter_constraints;
DELETE FROM api_key;
DELETE FROM notification;

-- Spring Batch (leaf → parent order)
DELETE FROM batch_step_execution_context;
DELETE FROM batch_job_execution_context;
DELETE FROM batch_step_execution;
DELETE FROM batch_job_execution_params;
DELETE FROM batch_job_execution;
DELETE FROM batch_job_instance;

-- ============================================================
-- Layer 2: Tables that reference both app_user AND shelter
-- ============================================================

DELETE FROM coordinator_assignment;
DELETE FROM referral_token;
DELETE FROM reservation;
DELETE FROM one_time_access_code;
DELETE FROM surge_event;
DELETE FROM user_oauth2_link;
DELETE FROM subscription;

-- ============================================================
-- Layer 3: Parent tables (after all children are cleared)
-- ============================================================

-- Shelters (children cleared in layers 1-2)
DELETE FROM shelter;

-- Users — clears ALL users including accumulated test users.
-- seed-data.sql will reload the 5 seed users with correct passwords.
DELETE FROM app_user;

-- Tenant config (preserve tenant row, clear OAuth providers)
DELETE FROM tenant_oauth2_provider;

-- NOTE: tenant table is NOT deleted — it's the root of all FK chains
-- and seed-data.sql expects it to exist (ON CONFLICT for idempotency).

\echo 'Seed data reset complete. Run seed-data.sql and demo-activity-seed.sql to reload.'
