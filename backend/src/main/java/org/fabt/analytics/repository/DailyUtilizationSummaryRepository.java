package org.fabt.analytics.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Repository for daily_utilization_summary table.
 * Uses the analytics DataSource for reads (separate pool from OLTP).
 * Writes (upsert from batch job) use a separate JdbcTemplate on the primary DataSource.
 */
@Repository
public class DailyUtilizationSummaryRepository {

    private final JdbcTemplate analyticsJdbc;
    private final JdbcTemplate primaryJdbc;

    public DailyUtilizationSummaryRepository(
            @Qualifier("analyticsDataSource") DataSource analyticsDataSource,
            JdbcTemplate primaryJdbc) {
        this.analyticsJdbc = new JdbcTemplate(analyticsDataSource);
        this.primaryJdbc = primaryJdbc;
    }

    /**
     * Upsert a daily utilization summary (called by batch job, uses primary DataSource).
     */
    public void upsert(UUID tenantId, UUID shelterId, String populationType, LocalDate summaryDate,
                        double avgUtilization, int maxOccupied, int minAvailable, int snapshotCount) {
        primaryJdbc.update(
                "INSERT INTO daily_utilization_summary "
                        + "(id, tenant_id, shelter_id, population_type, summary_date, "
                        + "avg_utilization, max_occupied, min_available, snapshot_count) "
                        + "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT ON CONSTRAINT uq_daily_util_tenant_shelter_pop_date "
                        + "DO UPDATE SET avg_utilization = EXCLUDED.avg_utilization, "
                        + "max_occupied = EXCLUDED.max_occupied, "
                        + "min_available = EXCLUDED.min_available, "
                        + "snapshot_count = EXCLUDED.snapshot_count",
                tenantId, shelterId, populationType, summaryDate,
                avgUtilization, maxOccupied, minAvailable, snapshotCount);
    }

    /**
     * Query utilization summaries for a tenant and date range (uses analytics DataSource).
     */
    public List<UtilizationSummaryRow> findByTenantAndDateRange(UUID tenantId, LocalDate from, LocalDate to) {
        return analyticsJdbc.query(
                "SELECT tenant_id, shelter_id, population_type, summary_date, "
                        + "avg_utilization, max_occupied, min_available, snapshot_count "
                        + "FROM daily_utilization_summary "
                        + "WHERE tenant_id = ? AND summary_date BETWEEN ? AND ? "
                        + "ORDER BY summary_date, shelter_id, population_type",
                this::mapRow, tenantId, from, to);
    }

    /**
     * Query utilization summaries filtered by shelter (uses analytics DataSource).
     */
    public List<UtilizationSummaryRow> findByTenantAndShelterAndDateRange(
            UUID tenantId, UUID shelterId, LocalDate from, LocalDate to) {
        return analyticsJdbc.query(
                "SELECT tenant_id, shelter_id, population_type, summary_date, "
                        + "avg_utilization, max_occupied, min_available, snapshot_count "
                        + "FROM daily_utilization_summary "
                        + "WHERE tenant_id = ? AND shelter_id = ? AND summary_date BETWEEN ? AND ? "
                        + "ORDER BY summary_date, population_type",
                this::mapRow, tenantId, shelterId, from, to);
    }

    private UtilizationSummaryRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new UtilizationSummaryRow(
                UUID.fromString(rs.getString("tenant_id")),
                UUID.fromString(rs.getString("shelter_id")),
                rs.getString("population_type"),
                rs.getDate("summary_date").toLocalDate(),
                rs.getDouble("avg_utilization"),
                rs.getInt("max_occupied"),
                rs.getInt("min_available"),
                rs.getInt("snapshot_count")
        );
    }

    public record UtilizationSummaryRow(
            UUID tenantId,
            UUID shelterId,
            String populationType,
            LocalDate summaryDate,
            double avgUtilization,
            int maxOccupied,
            int minAvailable,
            int snapshotCount
    ) {}
}
