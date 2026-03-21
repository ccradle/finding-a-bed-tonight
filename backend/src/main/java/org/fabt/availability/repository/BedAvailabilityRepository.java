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
     */
    public List<BedAvailability> findLatestByTenantId(UUID tenantId) {
        return jdbcTemplate.query(
                """
                SELECT DISTINCT ON (shelter_id, population_type) *
                FROM bed_availability
                WHERE tenant_id = ?
                ORDER BY shelter_id, population_type, snapshot_ts DESC
                """,
                ROW_MAPPER,
                tenantId
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
     * Append-only insert. Uses ON CONFLICT DO NOTHING for concurrent insert safety —
     * if two coordinators submit at the exact same millisecond for the same
     * shelter/population type, one insert is silently dropped.
     */
    public BedAvailability insert(BedAvailability ba) {
        List<BedAvailability> results = jdbcTemplate.query(
                """
                INSERT INTO bed_availability
                    (shelter_id, tenant_id, population_type, beds_total, beds_occupied,
                     beds_on_hold, accepting_new_guests, updated_by, notes, overflow_beds)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
