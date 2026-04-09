package org.fabt.shelter.repository;

import java.util.List;
import java.util.UUID;

import org.fabt.shelter.api.ShelterSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JdbcTemplate-based repository for coordinator_assignment. The table has a
 * composite primary key (user_id, shelter_id).
 */
@Repository
public class CoordinatorAssignmentRepository {

    private final JdbcTemplate jdbcTemplate;

    public CoordinatorAssignmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void assign(UUID userId, UUID shelterId) {
        jdbcTemplate.update(
                """
                INSERT INTO coordinator_assignment (user_id, shelter_id)
                VALUES (?, ?)
                ON CONFLICT (user_id, shelter_id) DO NOTHING
                """,
                userId, shelterId
        );
    }

    public void unassign(UUID userId, UUID shelterId) {
        jdbcTemplate.update(
                "DELETE FROM coordinator_assignment WHERE user_id = ? AND shelter_id = ?",
                userId, shelterId
        );
    }

    public boolean isAssigned(UUID userId, UUID shelterId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coordinator_assignment WHERE user_id = ? AND shelter_id = ?",
                Integer.class,
                userId, shelterId
        );
        return count != null && count > 0;
    }

    public List<UUID> findUserIdsByShelterId(UUID shelterId) {
        return jdbcTemplate.query(
                "SELECT user_id FROM coordinator_assignment WHERE shelter_id = ?",
                (rs, rowNum) -> rs.getObject("user_id", UUID.class),
                shelterId
        );
    }

    public List<UUID> findShelterIdsByUserId(UUID userId) {
        return jdbcTemplate.query(
                "SELECT shelter_id FROM coordinator_assignment WHERE user_id = ?",
                (rs, rowNum) -> rs.getObject("shelter_id", UUID.class),
                userId
        );
    }

    public List<ShelterSummary> findShelterSummariesByUserId(UUID userId, UUID tenantId) {
        return jdbcTemplate.query(
                """
                SELECT s.id, s.name
                FROM coordinator_assignment ca
                JOIN shelter s ON s.id = ca.shelter_id
                WHERE ca.user_id = ? AND s.tenant_id = ?
                ORDER BY s.name
                """,
                (rs, rowNum) -> new ShelterSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("name")
                ),
                userId, tenantId
        );
    }
}
