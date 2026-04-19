package org.fabt.analytics.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.fabt.analytics.repository.BedSearchLogRepository;
import org.fabt.analytics.repository.DailyUtilizationSummaryRepository;
import org.fabt.analytics.repository.DailyUtilizationSummaryRepository.UtilizationSummaryRow;
import org.fabt.shared.cache.CacheNames;
import org.fabt.shared.cache.TenantScopedCacheService;
import org.fabt.shared.web.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Orchestrates all analytics queries using the analytics DataSource (Design D10).
 *
 * All responses are cached with 5-minute TTL — utilization data doesn't change
 * second-by-second, so stale-by-minutes is acceptable.
 *
 * DV data follows D4 aggregation rules: minimum cell size 5, no individual shelters.
 */
@Service
public class AnalyticsService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int DV_MIN_CELL_SIZE = 5;
    private static final int DV_MIN_SHELTER_COUNT = 3;

    private final DailyUtilizationSummaryRepository summaryRepository;
    private final BedSearchLogRepository searchLogRepository;
    private final JdbcTemplate analyticsJdbc;
    private final JdbcTemplate primaryJdbc;
    private final TenantScopedCacheService cacheService;

    public AnalyticsService(
            DailyUtilizationSummaryRepository summaryRepository,
            BedSearchLogRepository searchLogRepository,
            @Qualifier("analyticsDataSource") DataSource analyticsDataSource,
            JdbcTemplate primaryJdbc,
            TenantScopedCacheService cacheService) {
        this.summaryRepository = summaryRepository;
        this.searchLogRepository = searchLogRepository;
        this.analyticsJdbc = new JdbcTemplate(analyticsDataSource);
        this.primaryJdbc = primaryJdbc;
        this.cacheService = cacheService;
    }

    /**
     * Utilization rates over time from daily_utilization_summary (not raw snapshots).
     */
    public Map<String, Object> getUtilization(UUID tenantId, LocalDate from, LocalDate to, String granularity) {
        String cacheKey = from + ":" + to + ":" + granularity;
        @SuppressWarnings("unchecked")
        Optional<Map<String, Object>> cached = cacheService.get(
                CacheNames.ANALYTICS_UTILIZATION, cacheKey, (Class<Map<String, Object>>) (Class<?>) Map.class);
        if (cached.isPresent()) return cached.get();

        List<UtilizationSummaryRow> rows = summaryRepository.findByTenantAndDateRange(tenantId, from, to);

        // Compute overall utilization and per-shelter breakdown
        double totalUtilization = rows.isEmpty() ? 0 :
                rows.stream().mapToDouble(UtilizationSummaryRow::avgUtilization).average().orElse(0);

        Map<String, Object> result = new HashMap<>();
        result.put("from", from.toString());
        result.put("to", to.toString());
        result.put("granularity", granularity);
        result.put("avgUtilization", Math.round(totalUtilization * 1000.0) / 1000.0);
        result.put("dataPoints", rows.size());
        result.put("details", rows);

        cacheService.put(CacheNames.ANALYTICS_UTILIZATION, cacheKey, result, CACHE_TTL);
        return result;
    }

    /**
     * Demand signals: reservation conversion/expiry rates and zero-result search counts.
     */
    public Map<String, Object> getDemand(UUID tenantId, LocalDate from, LocalDate to) {
        String cacheKey = from + ":" + to;
        @SuppressWarnings("unchecked")
        Optional<Map<String, Object>> cached = cacheService.get(
                CacheNames.ANALYTICS_DEMAND, cacheKey, (Class<Map<String, Object>>) (Class<?>) Map.class);
        if (cached.isPresent()) return cached.get();

        Instant fromInstant = from.atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        Instant toInstant = to.plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC);

        long totalSearches = searchLogRepository.countByTenantAndPeriod(tenantId, fromInstant, toInstant);
        long zeroResults = searchLogRepository.countZeroResultsByPeriod(tenantId, fromInstant, toInstant);

        // Reservation stats from the reservation table
        Map<String, Object> reservationStats = getReservationStats(tenantId, fromInstant, toInstant);

        Map<String, Object> result = new HashMap<>();
        result.put("from", from.toString());
        result.put("to", to.toString());
        result.put("totalSearches", totalSearches);
        result.put("zeroResultSearches", zeroResults);
        result.put("zeroResultRate", totalSearches > 0 ? (double) zeroResults / totalSearches : 0);
        result.put("reservations", reservationStats);

        cacheService.put(CacheNames.ANALYTICS_DEMAND, cacheKey, result, CACHE_TTL);
        return result;
    }

    /**
     * Total system capacity over time, beds added/removed.
     */
    public Map<String, Object> getCapacity(UUID tenantId, LocalDate from, LocalDate to) {
        String cacheKey = from + ":" + to;
        @SuppressWarnings("unchecked")
        Optional<Map<String, Object>> cached = cacheService.get(
                CacheNames.ANALYTICS_CAPACITY, cacheKey, (Class<Map<String, Object>>) (Class<?>) Map.class);
        if (cached.isPresent()) return cached.get();

        // Current total beds from latest snapshots
        List<Map<String, Object>> capacityByDate = analyticsJdbc.queryForList(
                """
                SELECT DATE(snapshot_ts) AS snapshot_date,
                       SUM(beds_total) AS total_beds,
                       SUM(beds_occupied) AS total_occupied,
                       SUM(beds_on_hold) AS total_on_hold
                FROM (
                    SELECT DISTINCT ON (shelter_id, population_type, DATE(snapshot_ts))
                           shelter_id, population_type, beds_total, beds_occupied,
                           beds_on_hold, snapshot_ts
                    FROM bed_availability
                    WHERE tenant_id = ? AND snapshot_ts BETWEEN ?::date AND (?::date + INTERVAL '1 day')
                    ORDER BY shelter_id, population_type, DATE(snapshot_ts), snapshot_ts DESC
                ) latest
                GROUP BY DATE(snapshot_ts)
                ORDER BY DATE(snapshot_ts)
                """,
                tenantId, from, to);

        Map<String, Object> result = new HashMap<>();
        result.put("from", from.toString());
        result.put("to", to.toString());
        result.put("dailyCapacity", capacityByDate);

        cacheService.put(CacheNames.ANALYTICS_CAPACITY, cacheKey, result, CACHE_TTL);
        return result;
    }

    /**
     * Aggregated DV shelter stats with minimum cell size 5 suppression (Design D4).
     */
    public Map<String, Object> getDvSummary(UUID tenantId) throws Exception {
        String cacheKey = "latest";
        @SuppressWarnings("unchecked")
        Optional<Map<String, Object>> cached = cacheService.get(
                CacheNames.ANALYTICS_DV_SUMMARY, cacheKey, (Class<Map<String, Object>>) (Class<?>) Map.class);
        if (cached.isPresent()) return cached.get();

        // Must have DV access — elevate within existing tenant scope
        return TenantContext.callWithContext(TenantContext.getTenantId(), true, () -> {
            // Aggregated DV bed counts — never individual shelters
            Map<String, Object> dvStats = analyticsJdbc.queryForMap(
                    """
                    SELECT COUNT(DISTINCT ba.shelter_id) AS dv_shelter_count,
                           COALESCE(SUM(ba.beds_total), 0) AS dv_total_beds,
                           COALESCE(SUM(ba.beds_occupied), 0) AS dv_occupied,
                           COALESCE(SUM(ba.beds_total - ba.beds_occupied - ba.beds_on_hold), 0) AS dv_available
                    FROM (
                        SELECT DISTINCT ON (ba.shelter_id, ba.population_type)
                               ba.shelter_id, ba.beds_total, ba.beds_occupied, ba.beds_on_hold
                        FROM bed_availability ba
                        JOIN shelter s ON s.id = ba.shelter_id
                        WHERE ba.tenant_id = ? AND s.dv_shelter = true
                        ORDER BY ba.shelter_id, ba.population_type, ba.snapshot_ts DESC
                    ) ba
                    """,
                    tenantId);

            Map<String, Object> result = new HashMap<>(dvStats);

            // Dual threshold suppression (Design D18):
            // 1. Minimum distinct shelters — prevents "aggregate = individual" when CoC has 1 DV shelter
            // 2. Minimum bed count — prevents small-n inference from tiny aggregates
            long dvShelterCount = ((Number) dvStats.getOrDefault("dv_shelter_count", 0L)).longValue();
            long dvTotalBeds = ((Number) dvStats.getOrDefault("dv_total_beds", 0L)).longValue();
            if (dvShelterCount < DV_MIN_SHELTER_COUNT) {
                result.put("suppressed", true);
                result.put("reason", "Insufficient DV shelters for safe aggregation");
                result.remove("dv_total_beds");
                result.remove("dv_occupied");
                result.remove("dv_available");
            } else if (dvTotalBeds < DV_MIN_CELL_SIZE) {
                result.put("suppressed", true);
                result.put("reason", "Below minimum cell size of " + DV_MIN_CELL_SIZE);
                result.remove("dv_total_beds");
                result.remove("dv_occupied");
                result.remove("dv_available");
            } else {
                result.put("suppressed", false);
            }

            cacheService.put(CacheNames.ANALYTICS_DV_SUMMARY, cacheKey, result, CACHE_TTL);
            return result;
        });
    }

    /**
     * Shelter locations with utilization data. DV shelters excluded (Design D4).
     */
    public List<Map<String, Object>> getGeographic(UUID tenantId) {
        String cacheKey = "latest";
        @SuppressWarnings("unchecked")
        Optional<List<Map<String, Object>>> cached = cacheService.get(
                CacheNames.ANALYTICS_GEOGRAPHIC, cacheKey, (Class<List<Map<String, Object>>>) (Class<?>) List.class);
        if (cached.isPresent()) return cached.get();

        List<Map<String, Object>> shelters = analyticsJdbc.queryForList(
                """
                SELECT s.id, s.name, s.address_city, s.address_state, s.address_zip,
                       s.latitude, s.longitude,
                       COALESCE(SUM(ba.beds_total), 0) AS total_beds,
                       COALESCE(SUM(ba.beds_occupied), 0) AS total_occupied,
                       CASE WHEN SUM(ba.beds_total) > 0
                           THEN ROUND(CAST(SUM(ba.beds_occupied) AS NUMERIC) / SUM(ba.beds_total), 3)
                           ELSE 0 END AS utilization
                FROM shelter s
                LEFT JOIN LATERAL (
                    SELECT DISTINCT ON (population_type)
                           beds_total, beds_occupied
                    FROM bed_availability
                    WHERE shelter_id = s.id
                    ORDER BY population_type, snapshot_ts DESC
                ) ba ON true
                WHERE s.tenant_id = ? AND s.dv_shelter = false
                GROUP BY s.id, s.name, s.address_city, s.address_state, s.address_zip,
                         s.latitude, s.longitude
                """,
                tenantId);

        cacheService.put(CacheNames.ANALYTICS_GEOGRAPHIC, cacheKey, shelters, CACHE_TTL);
        return shelters;
    }

    /**
     * HMIS push success/failure rates from hmis_audit_log.
     */
    public Map<String, Object> getHmisHealth(UUID tenantId) {
        String cacheKey = "latest";
        @SuppressWarnings("unchecked")
        Optional<Map<String, Object>> cached = cacheService.get(
                CacheNames.ANALYTICS_HMIS_HEALTH, cacheKey, (Class<Map<String, Object>>) (Class<?>) Map.class);
        if (cached.isPresent()) return cached.get();

        // Overall push stats
        List<Map<String, Object>> pushStats = analyticsJdbc.queryForList(
                """
                SELECT status, COUNT(*) AS count
                FROM hmis_audit_log
                WHERE tenant_id = ?
                GROUP BY status
                """,
                tenantId);

        // Last push per vendor
        List<Map<String, Object>> lastPush = analyticsJdbc.queryForList(
                """
                SELECT DISTINCT ON (vendor_type)
                       vendor_type, push_timestamp, status, record_count
                FROM hmis_audit_log
                WHERE tenant_id = ?
                ORDER BY vendor_type, push_timestamp DESC
                """,
                tenantId);

        // Dead letter count
        Long deadLetterCount = primaryJdbc.queryForObject(
                "SELECT COUNT(*) FROM hmis_outbox WHERE tenant_id = ? AND status = 'DEAD_LETTER'",
                Long.class, tenantId);

        Map<String, Object> result = new HashMap<>();
        result.put("pushStats", pushStats);
        result.put("lastPushPerVendor", lastPush);
        result.put("deadLetterCount", deadLetterCount != null ? deadLetterCount : 0);

        cacheService.put(CacheNames.ANALYTICS_HMIS_HEALTH, cacheKey, result, CACHE_TTL);
        return result;
    }

    private Map<String, Object> getReservationStats(UUID tenantId, Instant from, Instant to) {
        List<Map<String, Object>> stats = primaryJdbc.queryForList(
                """
                SELECT status, COUNT(*) AS count
                FROM reservation
                WHERE tenant_id = ? AND created_at BETWEEN ? AND ?
                GROUP BY status
                """,
                tenantId, Timestamp.from(from), Timestamp.from(to));

        Map<String, Object> result = new HashMap<>();
        long total = 0;
        long confirmed = 0;
        long expired = 0;
        for (Map<String, Object> row : stats) {
            String status = (String) row.get("status");
            long count = ((Number) row.get("count")).longValue();
            result.put(status, count);
            total += count;
            if ("CONFIRMED".equals(status)) confirmed = count;
            if ("EXPIRED".equals(status)) expired = count;
        }
        result.put("total", total);
        result.put("conversionRate", total > 0 ? (double) confirmed / total : 0.0);
        result.put("expiryRate", total > 0 ? (double) expired / total : 0.0);

        return result;
    }
}
