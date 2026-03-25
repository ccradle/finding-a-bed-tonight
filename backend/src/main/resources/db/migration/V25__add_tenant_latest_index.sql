-- V25: Composite index for bed search query performance.
--
-- The bed search query uses DISTINCT ON (shelter_id, population_type)
-- with WHERE tenant_id = ? ORDER BY snapshot_ts DESC. The existing
-- idx_bed_avail_latest index starts with shelter_id, so PostgreSQL
-- can't use it to filter by tenant_id first — it falls back to a
-- sequential scan + external merge sort.
--
-- This index leads with tenant_id so the planner can:
-- 1. Filter to the tenant's rows via index
-- 2. Walk the index in (shelter_id, population_type, snapshot_ts DESC) order
-- 3. Pick the first row per group (DISTINCT ON) without a sort
--
-- Measured: 65K rows, seq scan = 250ms → index scan = expected <5ms.
-- Little's Law: at 0.17s avg hold time, reducing to <0.05s reduces
-- peak pool demand from 12 to ~4 connections.

CREATE INDEX idx_bed_avail_tenant_latest
    ON bed_availability(tenant_id, shelter_id, population_type, snapshot_ts DESC);
