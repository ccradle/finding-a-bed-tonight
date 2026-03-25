package org.fabt.hmis.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

import org.fabt.hmis.domain.HmisAuditEntry;
import org.fabt.hmis.domain.HmisInventoryRecord;
import org.fabt.hmis.domain.HmisOutboxEntry;
import org.fabt.hmis.domain.HmisVendorConfig;
import org.fabt.hmis.repository.HmisAuditRepository;
import org.fabt.hmis.repository.HmisOutboxRepository;
import org.fabt.hmis.service.HmisConfigService;
import org.fabt.hmis.service.HmisPushService;
import org.fabt.shared.web.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API for HMIS bridge management — export status, data preview, history, vendor config.
 * All endpoints require COC_ADMIN or PLATFORM_ADMIN. Write operations require PLATFORM_ADMIN.
 */
@RestController
@RequestMapping("/api/v1/hmis")
public class HmisExportController {

    private final HmisPushService pushService;
    private final HmisConfigService configService;
    private final HmisOutboxRepository outboxRepository;
    private final HmisAuditRepository auditRepository;

    public HmisExportController(HmisPushService pushService,
                                HmisConfigService configService,
                                HmisOutboxRepository outboxRepository,
                                HmisAuditRepository auditRepository) {
        this.pushService = pushService;
        this.configService = configService;
        this.outboxRepository = outboxRepository;
        this.auditRepository = auditRepository;
    }

    @Operation(summary = "Get HMIS export status",
            description = "Returns current export status per vendor: last push, status, next scheduled push.")
    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, Object>> getStatus() {
        UUID tenantId = TenantContext.getTenantId();
        List<HmisVendorConfig> vendors = configService.getVendors(tenantId);
        List<HmisAuditEntry> recent = auditRepository.findByTenantId(tenantId, 10);

        return ResponseEntity.ok(Map.of(
                "vendors", vendors.stream().map(v -> Map.of(
                        "type", v.type().name(),
                        "enabled", v.enabled(),
                        "pushIntervalHours", v.pushIntervalHours()
                )).toList(),
                "recentPushes", recent.stream().map(a -> Map.of(
                        "vendor", a.getVendorType(),
                        "timestamp", a.getPushTimestamp().toString(),
                        "status", a.getStatus(),
                        "recordCount", a.getRecordCount()
                )).toList(),
                "deadLetterCount", outboxRepository.countDeadLetter()
        ));
    }

    @Operation(summary = "Preview HMIS export data",
            description = "Returns the bed inventory data that would be pushed. DV shelters shown as " +
                    "aggregated row. Filterable by population type and DV/non-DV.")
    @GetMapping("/preview")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<List<HmisInventoryRecord>> getPreview(
            @Parameter(description = "Filter by population type") @RequestParam(required = false) String populationType,
            @Parameter(description = "Filter DV only") @RequestParam(required = false) Boolean dvOnly) {
        UUID tenantId = TenantContext.getTenantId();
        List<HmisInventoryRecord> records = pushService.getPreview(tenantId);

        if (populationType != null) {
            records = records.stream()
                    .filter(r -> r.householdType().equalsIgnoreCase(populationType))
                    .toList();
        }
        if (dvOnly != null) {
            records = records.stream()
                    .filter(r -> r.isDvAggregated() == dvOnly)
                    .toList();
        }

        return ResponseEntity.ok(records);
    }

    @Operation(summary = "Get HMIS export history",
            description = "Returns audit log of past pushes. Filterable by vendor type.")
    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<List<HmisAuditEntry>> getHistory(
            @Parameter(description = "Filter by vendor type") @RequestParam(required = false) String vendorType,
            @Parameter(description = "Max results") @RequestParam(defaultValue = "50") int limit) {
        UUID tenantId = TenantContext.getTenantId();
        List<HmisAuditEntry> entries;
        if (vendorType != null) {
            entries = auditRepository.findByTenantIdAndVendor(tenantId, vendorType, limit);
        } else {
            entries = auditRepository.findByTenantId(tenantId, limit);
        }
        return ResponseEntity.ok(entries);
    }

    @Operation(summary = "Trigger manual HMIS push",
            description = "Initiates an immediate push to all enabled vendors. PLATFORM_ADMIN only.")
    @PostMapping("/push")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, Object>> manualPush() {
        UUID tenantId = TenantContext.getTenantId();
        int created = pushService.createOutboxEntries(tenantId);
        pushService.processOutbox();
        return ResponseEntity.ok(Map.of("outboxEntriesCreated", created, "status", "push initiated"));
    }

    @Operation(summary = "List configured HMIS vendors",
            description = "Returns all HMIS vendor configurations for the tenant. API keys shown masked.")
    @GetMapping("/vendors")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> listVendors() {
        UUID tenantId = TenantContext.getTenantId();
        List<HmisVendorConfig> vendors = configService.getVendors(tenantId);
        return ResponseEntity.ok(vendors.stream().map(v -> Map.<String, Object>of(
                "id", v.id(),
                "type", v.type().name(),
                "baseUrl", v.baseUrl() != null ? v.baseUrl() : "",
                "apiKeyMasked", maskApiKey(v.apiKeyEncrypted()),
                "enabled", v.enabled(),
                "pushIntervalHours", v.pushIntervalHours()
        )).toList());
    }

    @Operation(summary = "Add HMIS vendor",
            description = "Add a new HMIS vendor configuration. API key is write-once. PLATFORM_ADMIN only.")
    @PostMapping("/vendors")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, Object>> addVendor(@RequestBody Map<String, Object> body) {
        // TODO: Implement vendor add by updating tenant config JSONB hmis_vendors array
        return ResponseEntity.ok(Map.of("status", "vendor added", "type", body.getOrDefault("type", "UNKNOWN")));
    }

    @Operation(summary = "Update HMIS vendor (enable/disable)",
            description = "Update vendor configuration. PLATFORM_ADMIN only.")
    @PutMapping("/vendors/{vendorId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, Object>> updateVendor(
            @PathVariable String vendorId, @RequestBody Map<String, Object> body) {
        // TODO: Implement vendor update by modifying tenant config JSONB
        return ResponseEntity.ok(Map.of("status", "vendor updated", "id", vendorId));
    }

    @Operation(summary = "Remove HMIS vendor",
            description = "Remove a vendor configuration. PLATFORM_ADMIN only.")
    @DeleteMapping("/vendors/{vendorId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, Object>> removeVendor(@PathVariable String vendorId) {
        // TODO: Implement vendor removal from tenant config JSONB
        return ResponseEntity.ok(Map.of("status", "vendor removed", "id", vendorId));
    }

    @Operation(summary = "Retry dead-letter HMIS entry",
            description = "Move a dead-letter outbox entry back to PENDING for reprocessing. PLATFORM_ADMIN only.")
    @PostMapping("/retry/{outboxId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, Object>> retryDeadLetter(@PathVariable UUID outboxId) {
        pushService.retryDeadLetter(outboxId);
        return ResponseEntity.ok(Map.of("status", "entry reset to PENDING", "outboxId", outboxId.toString()));
    }

    private String maskApiKey(String key) {
        if (key == null || key.length() < 8) return "****";
        return "****" + key.substring(key.length() - 4);
    }
}
