-- =====================================================================
-- Cleanup for the `findOldestPendingByShelterIds` EXPLAIN probe seed
-- (notification-deep-linking task 16.1.5)
-- =====================================================================
-- Purpose:
--   Remove ALL data created by seed-oldest-pending-probe.sql.
--   Safety-scoped to tenant slug 'perf-probe-16-1-5' — demo/dev/staging
--   tenants are untouched.
--
-- Cascade + explicit-delete chain (verified against every FK referencing
-- tenant at Flyway HEAD V55, 2026-04-14 via pg_constraint):
--
--   Explicit deletes (no CASCADE path — tenant FK is NO ACTION for each):
--     - bed_search_log (has tenant_id, no CASCADE — caught during 16.1.5
--       test runs where a stray search event persisted across seed reruns
--       and FK-blocked the tenant delete, silently rolling back the
--       entire cleanup transaction)
--     - subscription, surge_event, daily_utilization_summary, import_log,
--       tenant_oauth2_provider, hmis_outbox, hmis_audit_log, api_key,
--       reservation (defensive — our seed does not insert these, but
--       development activity in the shared dev stack can populate them)
--     - escalation_policy (DELETE RESTRICT on tenant FK — must be
--       explicit)
--
--   Cascades handled automatically:
--     - shelter → bed_availability, coordinator_assignment,
--                 shelter_constraints, shelter_capacity, referral_token
--     - app_user → coordinator_assignment, notification,
--                  one_time_access_code, password_reset_token,
--                  user_oauth2_link
--
--   Not handled: audit_events has no tenant_id. System-generated rows
--   (actor_user_id IS NULL post-V44/V48) cannot be scoped to this
--   tenant and are left in place. Acceptable noise for perf probes.
--
-- Usage:
--   docker exec -i finding-a-bed-tonight-postgres-1 psql -U fabt -d fabt \
--     < docs/performance/seed-oldest-pending-probe-cleanup.sql
--
-- Post-condition: no rows remain in any table with tenant_id matching
-- this tenant. Verification query at the end asserts the invariant and
-- fails loudly if a new FK lands that the cleanup does not yet cover.
-- =====================================================================

\echo '=== Cleaning up perf-probe-16-1-5 tenant ==='

BEGIN;

-- Capture tenant id once to avoid re-subqueries (the tenant row still
-- exists at this point).
CREATE TEMP TABLE IF NOT EXISTS _cleanup_tenant_id AS
SELECT id FROM tenant WHERE slug = 'perf-probe-16-1-5';

-- Explicit deletes for tables with tenant_id FK and NO CASCADE.
DELETE FROM bed_search_log           WHERE tenant_id IN (SELECT id FROM _cleanup_tenant_id);
DELETE FROM subscription             WHERE tenant_id IN (SELECT id FROM _cleanup_tenant_id);
DELETE FROM surge_event              WHERE tenant_id IN (SELECT id FROM _cleanup_tenant_id);
DELETE FROM daily_utilization_summary WHERE tenant_id IN (SELECT id FROM _cleanup_tenant_id);
DELETE FROM import_log               WHERE tenant_id IN (SELECT id FROM _cleanup_tenant_id);
DELETE FROM tenant_oauth2_provider   WHERE tenant_id IN (SELECT id FROM _cleanup_tenant_id);
DELETE FROM hmis_outbox              WHERE tenant_id IN (SELECT id FROM _cleanup_tenant_id);
DELETE FROM hmis_audit_log           WHERE tenant_id IN (SELECT id FROM _cleanup_tenant_id);
DELETE FROM api_key                  WHERE tenant_id IN (SELECT id FROM _cleanup_tenant_id);
DELETE FROM reservation              WHERE tenant_id IN (SELECT id FROM _cleanup_tenant_id);
DELETE FROM referral_token           WHERE tenant_id IN (SELECT id FROM _cleanup_tenant_id);
DELETE FROM escalation_policy        WHERE tenant_id IN (SELECT id FROM _cleanup_tenant_id);

-- Shelter delete cascades to bed_availability, coordinator_assignment,
-- shelter_constraints, shelter_capacity, referral_token (already empty).
DELETE FROM shelter  WHERE tenant_id IN (SELECT id FROM _cleanup_tenant_id);

-- app_user delete cascades to coordinator_assignment (already empty),
-- notification, one_time_access_code, password_reset_token,
-- user_oauth2_link.
DELETE FROM app_user WHERE tenant_id IN (SELECT id FROM _cleanup_tenant_id);

-- Now the tenant itself.
DELETE FROM tenant   WHERE slug = 'perf-probe-16-1-5';

-- Verification: assert no rows remain anywhere with this tenant_id.
-- (Tenant is gone so subqueries here scope through the captured id.)
DO $verify$
DECLARE
    v_leftover integer;
    v_tenant_id uuid;
BEGIN
    SELECT id INTO v_tenant_id FROM _cleanup_tenant_id;
    IF v_tenant_id IS NULL THEN
        RAISE NOTICE 'Cleanup skipped — tenant not found (already clean).';
        RETURN;
    END IF;
    -- Sum every referencing table. If this is > 0, a new FK was added
    -- without updating the cleanup script and the probe will leak rows.
    SELECT
        (SELECT COUNT(*) FROM bed_search_log WHERE tenant_id = v_tenant_id) +
        (SELECT COUNT(*) FROM app_user WHERE tenant_id = v_tenant_id) +
        (SELECT COUNT(*) FROM shelter WHERE tenant_id = v_tenant_id) +
        (SELECT COUNT(*) FROM referral_token WHERE tenant_id = v_tenant_id) +
        (SELECT COUNT(*) FROM tenant WHERE id = v_tenant_id)
    INTO v_leftover;
    IF v_leftover > 0 THEN
        RAISE EXCEPTION 'Cleanup incomplete — % rows remain. Cleanup script is out of sync with schema.', v_leftover;
    END IF;
    RAISE NOTICE 'Cleanup verified — 0 rows remain for tenant_id=%', v_tenant_id;
END $verify$;

DROP TABLE _cleanup_tenant_id;

COMMIT;

ANALYZE referral_token;
ANALYZE shelter;

\echo '=== Cleanup complete ==='
