-- V71 — Phase B (task 3.6): CREATE INDEX on (tenant_id, …) for the
-- regulated tables that don't already have one. Prevents full-table
-- scans once FORCE RLS filters every read through the
-- tenant_id = fabt_current_tenant_id() predicate.
--
-- Status of the 7 regulated tables BEFORE this migration:
--   audit_events           — V57 added idx_audit_events_tenant_target (OK)
--   hmis_audit_log         — V22 added idx_hmis_audit_tenant_ts (OK)
--   hmis_outbox            — V22 added idx_hmis_outbox_tenant (OK)
--   tenant_key_material    — V61 PK includes (tenant_id, generation) (OK)
--   kid_to_tenant_key      — V61 has UNIQUE (tenant_id, generation) (OK)
--   password_reset_token   — V39 indexed token_hash + expires_at only (MISSING)
--   one_time_access_code   — V32 indexed user_id + expires_at only (MISSING)
--
-- Add the two missing indexes. Covers the retention-sweep DELETE + any
-- future tenant-scoped lookup.
--
-- Investigation 2026-04-17 (post-checkpoint): CREATE INDEX CONCURRENTLY
-- initially chosen for zero-write-block, but under Flyway + Spring Data JDBC
-- it hangs on pg_stat_activity wait_event=virtualxid waiting for an
-- idle-in-transaction schema_history connection that never releases in the
-- test harness. Classic CONCURRENTLY-with-Flyway gotcha.
--
-- At pilot-scale (password_reset_token + one_time_access_code each hold
-- dozens of rows max at Phase B apply time) regular CREATE INDEX blocks
-- writes for single-digit milliseconds. Phase B deploy is already a
-- maintenance window (pgaudit image swap). Write-block tolerable at this
-- scale; use CONCURRENTLY selectively on the larger audit_events when it
-- partitions in a later migration.
--
-- Jordan warroom original recommendation (CONCURRENTLY) preserved for
-- future year-1-scale additions on audit_events / hmis_audit_log, where
-- row counts justify the operational complexity.

CREATE INDEX IF NOT EXISTS idx_password_reset_token_tenant_expires
    ON password_reset_token (tenant_id, expires_at);

CREATE INDEX IF NOT EXISTS idx_one_time_access_code_tenant_expires
    ON one_time_access_code (tenant_id, expires_at);
