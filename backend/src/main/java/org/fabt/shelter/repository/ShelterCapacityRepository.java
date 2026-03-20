package org.fabt.shelter.repository;

import java.util.List;
import java.util.UUID;

import org.fabt.shelter.domain.ShelterCapacity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JdbcTemplate-based repository for ShelterCapacity. The shelter_capacity table
 * has a composite primary key (shelter_id, population_type) which Spring Data JDBC
 * does not natively support.
 */
@Repository
public class ShelterCapacityRepository {

    private final JdbcTemplate jdbcTemplate;

    public ShelterCapacityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ShelterCapacity> findByShelterId(UUID shelterId) {
        return jdbcTemplate.query(
                "SELECT shelter_id, population_type, beds_total FROM shelter_capacity WHERE shelter_id = ?",
                (rs, rowNum) -> new ShelterCapacity(
                        rs.getObject("shelter_id", UUID.class),
                        rs.getString("population_type"),
                        rs.getInt("beds_total")
                ),
                shelterId
        );
    }

    /**
     * UPSERT a capacity row. Inserts if no row exists for (shelterId, populationType),
     * otherwise updates beds_total.
     */
    public void save(UUID shelterId, String populationType, int bedsTotal) {
        jdbcTemplate.update(
                """
                INSERT INTO shelter_capacity (shelter_id, population_type, beds_total)
                VALUES (?, ?, ?)
                ON CONFLICT (shelter_id, population_type)
                DO UPDATE SET beds_total = EXCLUDED.beds_total
                """,
                shelterId, populationType, bedsTotal
        );
    }

    public void deleteByShelterId(UUID shelterId) {
        jdbcTemplate.update("DELETE FROM shelter_capacity WHERE shelter_id = ?", shelterId);
    }
}
