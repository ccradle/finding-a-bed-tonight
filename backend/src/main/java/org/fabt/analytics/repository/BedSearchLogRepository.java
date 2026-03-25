package org.fabt.analytics.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Repository for bed_search_log table.
 * Uses the primary (OLTP) DataSource because inserts must be fast and transactional.
 * Read queries for analytics use the analytics DataSource via AnalyticsService.
 */
@Repository
public class BedSearchLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public BedSearchLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(UUID tenantId, String populationType, int resultsCount) {
        jdbcTemplate.update(
                "INSERT INTO bed_search_log (id, tenant_id, population_type, results_count, search_ts) "
                        + "VALUES (gen_random_uuid(), ?, ?, ?, NOW())",
                tenantId, populationType, resultsCount);
    }

    public long countByTenantAndPeriod(UUID tenantId, Instant from, Instant to) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bed_search_log WHERE tenant_id = ? AND search_ts BETWEEN ? AND ?",
                Long.class, tenantId, Timestamp.from(from), Timestamp.from(to));
        return count != null ? count : 0;
    }

    public long countZeroResultsByPeriod(UUID tenantId, Instant from, Instant to) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bed_search_log WHERE tenant_id = ? AND results_count = 0 AND search_ts BETWEEN ? AND ?",
                Long.class, tenantId, Timestamp.from(from), Timestamp.from(to));
        return count != null ? count : 0;
    }
}
