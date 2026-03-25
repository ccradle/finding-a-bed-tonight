package org.fabt.analytics.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.analytics.service.AnalyticsService;
import org.fabt.analytics.service.HicPitExportService;
import org.fabt.shared.web.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CoC Analytics API endpoints (Design D3).
 * All endpoints require COC_ADMIN or PLATFORM_ADMIN.
 * Returns aggregate data — no PII.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics", description = "CoC-level analytics and reporting")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final HicPitExportService exportService;

    public AnalyticsController(AnalyticsService analyticsService, HicPitExportService exportService) {
        this.analyticsService = analyticsService;
        this.exportService = exportService;
    }

    @GetMapping("/utilization")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(summary = "Utilization rates over time",
            description = "Returns utilization rates from pre-aggregated daily summaries, filterable by date range and granularity.")
    public ResponseEntity<Map<String, Object>> getUtilization(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "daily") String granularity) {
        UUID tenantId = TenantContext.getTenantId();
        return ResponseEntity.ok(analyticsService.getUtilization(tenantId, from, to, granularity));
    }

    @GetMapping("/demand")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(summary = "Demand signals",
            description = "Reservation conversion/expiry rates and zero-result search counts.")
    public ResponseEntity<Map<String, Object>> getDemand(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID tenantId = TenantContext.getTenantId();
        return ResponseEntity.ok(analyticsService.getDemand(tenantId, from, to));
    }

    @GetMapping("/capacity")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(summary = "System capacity trends",
            description = "Total beds over time, add/remove deltas.")
    public ResponseEntity<Map<String, Object>> getCapacity(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID tenantId = TenantContext.getTenantId();
        return ResponseEntity.ok(analyticsService.getCapacity(tenantId, from, to));
    }

    @GetMapping("/dv-summary")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(summary = "DV shelter aggregate statistics",
            description = "Aggregated DV shelter stats with minimum cell size 5 suppression. Requires dvAccess.")
    public ResponseEntity<Map<String, Object>> getDvSummary() {
        UUID tenantId = TenantContext.getTenantId();
        if (!TenantContext.getDvAccess()) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(analyticsService.getDvSummary(tenantId));
    }

    @GetMapping("/geographic")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(summary = "Geographic shelter view",
            description = "Shelter locations with utilization data. DV shelters excluded from map.")
    public ResponseEntity<List<Map<String, Object>>> getGeographic() {
        UUID tenantId = TenantContext.getTenantId();
        return ResponseEntity.ok(analyticsService.getGeographic(tenantId));
    }

    @GetMapping("/hic")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(summary = "HIC export (CSV)",
            description = "Housing Inventory Count export in HUD format.")
    public ResponseEntity<String> getHicExport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        UUID tenantId = TenantContext.getTenantId();
        LocalDate exportDate = date != null ? date : LocalDate.now();
        String csv = exportService.generateHic(tenantId, exportDate);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=hic-" + exportDate + ".csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }

    @GetMapping("/pit")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(summary = "PIT export (CSV)",
            description = "Sheltered Point-in-Time count export in HUD format.")
    public ResponseEntity<String> getPitExport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        UUID tenantId = TenantContext.getTenantId();
        LocalDate exportDate = date != null ? date : LocalDate.now();
        String csv = exportService.generatePit(tenantId, exportDate);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=pit-" + exportDate + ".csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }

    @GetMapping("/hmis-health")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    @Operation(summary = "HMIS push health",
            description = "HMIS push success/failure rates, last push per vendor, dead letter count.")
    public ResponseEntity<Map<String, Object>> getHmisHealth() {
        UUID tenantId = TenantContext.getTenantId();
        return ResponseEntity.ok(analyticsService.getHmisHealth(tenantId));
    }
}
