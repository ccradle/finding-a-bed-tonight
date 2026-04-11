package org.fabt.availability.repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import org.fabt.availability.domain.BedAvailability;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * JdbcTemplate-based repository for bed_availability. Append-only — no UPDATE or DELETE.
 * Uses DISTINCT ON for latest-snapshot queries with the descending index.
 */
@Repository
public class BedAvailabilityRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<BedAvailability> ROW_MAPPER = (rs, rowNum) -> {
        BedAvailability ba = new BedAvailability();
        ba.setId(rs.getObject("id", UUID.class));
        ba.setShelterId(rs.getObject("shelter_id", UUID.class));
        ba.setTenantId(rs.getObject("tenant_id", UUID.class));
        ba.setPopulationType(rs.getString("population_type"));
        ba.setBedsTotal(rs.getInt("beds_total"));
        ba.setBedsOccupied(rs.getInt("beds_occupied"));
        ba.setBedsOnHold(rs.getInt("beds_on_hold"));
        ba.setAcceptingNewGuests(rs.getBoolean("accepting_new_guests"));
        Timestamp ts = rs.getTimestamp("snapshot_ts");
        ba.setSnapshotTs(ts != null ? ts.toInstant() : null);
        ba.setUpdatedBy(rs.getString("updated_by"));
        ba.setNotes(rs.getString("notes"));
        ba.setOverflowBeds(rs.getObject("overflow_beds", Integer.class));
        return ba;
    };

    public BedAvailabilityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Latest snapshot per (shelter_id, population_type) for a tenant.
     *
     * Uses a recursive CTE to emulate a skip scan for distinct (shelter_id,
     * population_type) combos, then a LATERAL join to fetch the latest row
     * for each combo. PostgreSQL lacks native skip scan, so SELECT DISTINCT
     * reads all matching rows (875K at NYC scale → 249ms). The recursive CTE
     * hops across the idx_bed_avail_tenant_latest index with Index Only Scans,
     * touching only one entry per combo (826 combos → 10ms). 24x speedup
     * verified via EXPLAIN ANALYZE at NYC scale (2.4M rows).
     */
    public List<BedAvailability> findLatestByTenantId(UUID tenantId) {
        return jdbcTemplate.query(
                """
                WITH RECURSIVE combos AS (
                    (SELECT shelter_id, population_type
                     FROM bed_availability
                     WHERE tenant_id = ?
                     ORDER BY shelter_id, population_type
                     LIMIT 1)
                    UNION ALL
                    (SELECT ba.shelter_id, ba.population_type
                     FROM combos c, LATERAL (
                         SELECT shelter_id, population_type
                         FROM bed_availability
                         WHERE tenant_id = ?
                           AND (shelter_id, population_type) > (c.shelter_id, c.population_type)
                         ORDER BY shelter_id, population_type
                         LIMIT 1
                     ) ba)
                )
                SELECT ba.*
                FROM combos
                CROSS JOIN LATERAL (
                    SELECT *
                    FROM bed_availability ba
                    WHERE ba.tenant_id = ?
                      AND ba.shelter_id = combos.shelter_id
                      AND ba.population_type = combos.population_type
                    ORDER BY ba.snapshot_ts DESC
                    LIMIT 1
                ) ba
                """,
                ROW_MAPPER,
                tenantId, tenantId, tenantId
        );
    }

    /**
     * Latest snapshot per population_type for a single shelter.
     */
    public List<BedAvailability> findLatestByShelterId(UUID shelterId) {
        return jdbcTemplate.query(
                """
                SELECT DISTINCT ON (population_type) *
                FROM bed_availability
                WHERE shelter_id = ?
                ORDER BY population_type, snapshot_ts DESC
                """,
                ROW_MAPPER,
                shelterId
        );
    }

    /**
     * Append-only insert. Uses clock_timestamp() for the snapshot_ts to ensure each
     * insert within the same transaction gets a distinct timestamp (unlike NOW() which
     * returns the transaction start time). ON CONFLICT DO NOTHING handles the rare case
     * where two coordinator updates collide at the same microsecond — one is silently
     * dropped. For system-driven writes (reservations), the advisory lock serializes
     * transactions so clock_timestamp() guarantees distinct timestamps.
     */
    public BedAvailability insert(BedAvailability ba) {
        List<BedAvailability> results = jdbcTemplate.query(
                """
                INSERT INTO bed_availability
                    (shelter_id, tenant_id, population_type, beds_total, beds_occupied,
                     beds_on_hold, accepting_new_guests, snapshot_ts, updated_by, notes, overflow_beds)
                VALUES (?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?, ?, ?)
                ON CONFLICT ON CONSTRAINT uq_bed_avail_shelter_pop_ts DO NOTHING
                RETURNING *
                """,
                ROW_MAPPER,
                ba.getShelterId(), ba.getTenantId(), ba.getPopulationType(),
                ba.getBedsTotal(), ba.getBedsOccupied(), ba.getBedsOnHold(),
                ba.isAcceptingNewGuests(), ba.getUpdatedBy(), ba.getNotes(),
                ba.getOverflowBeds() != null ? ba.getOverflowBeds() : 0
        );
        if (results.isEmpty()) {
            // Concurrent insert — silently dropped per HSDS spec. Return the input as-is.
            return ba;
        }
        return results.get(0);
    }

    /**
     * Drift row returned by {@link #findDriftedRows()}: shelter+population pairs where the
     * latest {@code bed_availability} snapshot's {@code beds_on_hold} disagrees with the
     * actual count of {@code HELD} reservations from the source-of-truth reservation table.
     *
     * <p>The record lives here (in the availability repository package) so the
     * reconciliation tasklet can consume it without needing to import any reservation
     * domain types — the SQL JOIN is the only place reservation data crosses into
     * availability code.</p>
     */
    public record DriftRow(UUID tenantId, UUID shelterId, String populationType,
                           int snapshotValue, int actualCount) {
        public int delta() { return actualCount - snapshotValue; }
    }

    /**
     * Find every (shelter_id, population_type) pair where the latest snapshot's
     * {@code beds_on_hold} value differs from the actual count of HELD reservations
     * for that pair. Drives the bed-holds reconciliation tasklet (Issue #102 RCA).
     *
     * <p>Implemented as a CTE that picks the latest snapshot per pair via
     * {@code DISTINCT ON}, joins against a {@code COUNT(*)} of HELD reservations
     * grouped by the same key, and filters to mismatches. The query touches both
     * tables at the SQL level — the Java code does not import any reservation
     * domain or repository types, preserving the ArchUnit module boundary.</p>
     *
     * <p><b>NYC-scale TODO:</b> the {@code DISTINCT ON} variant reads every
     * {@code bed_availability} row to find the latest per pair (250ms at NYC's
     * 2.4M-row scale, per the {@link #findLatestByTenantId} benchmarks). For
     * the demo/single-CoC scale this is fine; for a multi-CoC NYC pilot, swap
     * the {@code latest} CTE for the recursive skip-scan pattern used in
     * {@link #findLatestByTenantId} (24x speedup verified).</p>
     */
    public List<DriftRow> findDriftedRows() {
        return jdbcTemplate.query(
                """
                WITH latest AS (
                    SELECT DISTINCT ON (shelter_id, population_type)
                           tenant_id, shelter_id, population_type, beds_on_hold
                    FROM bed_availability
                    ORDER BY shelter_id, population_type, snapshot_ts DESC
                ),
                held_counts AS (
                    SELECT shelter_id, population_type, COUNT(*)::int AS held_count
                    FROM reservation
                    WHERE status = 'HELD'
                    GROUP BY shelter_id, population_type
                )
                SELECT l.tenant_id,
                       l.shelter_id,
                       l.population_type,
                       l.beds_on_hold                    AS snapshot_value,
                       COALESCE(h.held_count, 0)::int    AS actual_count
                FROM latest l
                LEFT JOIN held_counts h
                       ON h.shelter_id = l.shelter_id
                      AND h.population_type = l.population_type
                WHERE l.beds_on_hold <> COALESCE(h.held_count, 0)
                """,
                (rs, rowNum) -> new DriftRow(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("shelter_id", UUID.class),
                        rs.getString("population_type"),
                        rs.getInt("snapshot_value"),
                        rs.getInt("actual_count")
                )
        );
    }

    /**
     * Count all snapshots for a shelter + population type (for testing append-only semantics).
     */
    public int countByShelterId(UUID shelterId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bed_availability WHERE shelter_id = ?",
                Integer.class,
                shelterId
        );
        return count != null ? count : 0;
    }
}
