-- NYC Load Test Data Cleanup
-- Removes ALL data created by generate-nyc-loadtest.py
-- Safe: only touches the 'nyc-loadtest' tenant — demo data is untouched.
--
-- Cascade chain (verified against Flyway migrations):
--   DELETE shelter → bed_availability (V12), reservation (V14), referral_token (V21),
--                    daily_utilization_summary (V23), shelter_constraints (V5),
--                    coordinator_assignment (V7)
--   DELETE app_user → user_oauth2_link (V10), one_time_access_code (V32),
--                     notification (V35), password_reset_token (V39),
--                     coordinator_assignment (V7)
--
-- Explicit deletes needed for tables with tenant_id but NO cascade:
--   bed_search_log, surge_event, subscription
--
-- audit_events has NO tenant_id — cleaned via actor_user_id FK match
--
-- Usage: psql -U fabt -d fabt -f docs/performance/nyc-loadtest-cleanup.sql

\echo '=== Cleaning up NYC load test data ==='
\echo 'Tenant: nyc-loadtest'

BEGIN;

-- 1. Explicit deletes for tables with tenant_id but no CASCADE
\echo 'Deleting non-cascaded tenant-scoped data...'
DELETE FROM bed_search_log WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'nyc-loadtest');
DELETE FROM surge_event WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'nyc-loadtest');
DELETE FROM subscription WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'nyc-loadtest');

-- 2. audit_events — no tenant_id, linked through user IDs
\echo 'Deleting audit events...'
DELETE FROM audit_events WHERE actor_user_id IN (SELECT id FROM app_user WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'nyc-loadtest'));

-- 3. Delete shelters — CASCADE handles: bed_availability, reservation,
--    referral_token, daily_utilization_summary, shelter_constraints,
--    coordinator_assignment
\echo 'Deleting shelters (+ cascaded: availability, reservations, constraints, assignments)...'
DELETE FROM shelter WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'nyc-loadtest');

-- 4. Delete users — CASCADE handles: user_oauth2_link, one_time_access_code,
--    notification, password_reset_token, coordinator_assignment
\echo 'Deleting users (+ cascaded: notifications, access codes, reset tokens)...'
DELETE FROM app_user WHERE tenant_id = (SELECT id FROM tenant WHERE slug = 'nyc-loadtest');

-- 5. Delete tenant
\echo 'Deleting tenant...'
DELETE FROM tenant WHERE slug = 'nyc-loadtest';

COMMIT;

\echo 'Reclaiming disk space...'
VACUUM FULL;
ANALYZE;

\echo '=== Cleanup complete ==='
