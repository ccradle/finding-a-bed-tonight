-- =====================================================================
-- V40: One-time backfill for phantom beds_on_hold (Issue #102 RCA)
-- =====================================================================
-- Context:
--   bed_availability.beds_on_hold is a denormalized cache derived from
--   the count of HELD reservations for each (shelter, population) pair.
--   Three independent write paths could write the cache out of sync with
--   the source of truth (the reservation table) — see GH issue #102 for
--   the full RCA. Affected versions: v0.31.0 through v0.32.3 (live demo).
--
-- This migration is the one-time correction step. It is paired with the
-- application-level fix that disciplines all future writes through a
-- single recompute path (ReservationService.recomputeBedsOnHold).
--
-- Behavior:
--   * Append-only — INSERT only, no UPDATE, no DELETE
--   * Idempotent — re-running on a clean (zero-drift) database is a no-op
--     because the WHERE clause matches no rows
--   * Audit-traceable — every corrective snapshot is tagged
--     updated_by = 'V40-rca-backfill' so it can be queried separately
--     from coordinator and reservation-driven snapshots
--
-- Pairs with:
--   * Application change: bed-hold-integrity (single write path)
--   * Spring Batch reconciliation tasklet (catches future drift on a
--     5-minute cadence, defense-in-depth)
--
-- Cross-link: https://github.com/ccradle/finding-a-bed-tonight/issues/102
-- =====================================================================

INSERT INTO bed_availability
    (shelter_id, tenant_id, population_type, beds_total, beds_occupied,
     beds_on_hold, accepting_new_guests, snapshot_ts, updated_by, notes, overflow_beds)
WITH latest AS (
    SELECT DISTINCT ON (shelter_id, population_type)
           id, shelter_id, tenant_id, population_type,
           beds_total, beds_occupied, beds_on_hold,
           accepting_new_guests, overflow_beds
    FROM bed_availability
    ORDER BY shelter_id, population_type, snapshot_ts DESC
),
held_counts AS (
    SELECT shelter_id, population_type, COUNT(*)::int AS held_count
    FROM reservation
    WHERE status = 'HELD'
    GROUP BY shelter_id, population_type
)
SELECT l.shelter_id,
       l.tenant_id,
       l.population_type,
       l.beds_total,
       l.beds_occupied,
       COALESCE(h.held_count, 0)             AS beds_on_hold,
       l.accepting_new_guests,
       clock_timestamp()                     AS snapshot_ts,
       'V40-rca-backfill'                    AS updated_by,
       'reconciliation: drift corrected (V40 backfill)' AS notes,
       COALESCE(l.overflow_beds, 0)          AS overflow_beds
FROM latest l
LEFT JOIN held_counts h
       ON h.shelter_id = l.shelter_id
      AND h.population_type = l.population_type
WHERE l.beds_on_hold <> COALESCE(h.held_count, 0)
ON CONFLICT ON CONSTRAINT uq_bed_avail_shelter_pop_ts DO NOTHING;
