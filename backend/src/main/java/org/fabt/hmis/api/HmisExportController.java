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
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.audit.AuditEventType;
import org.fabt.shared.web.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

    private static final Logger log = LoggerFactory.getLogger(HmisExportController.class);

    private final HmisPushService pushService;
    private final HmisConfigService configService;
    private final HmisOutboxRepository outboxRepository;
    private final HmisAuditRepository auditRepository;
    private final ApplicationEventPublisher eventPublisher;

    public HmisExportController(HmisPushService pushService,
                                HmisConfigService configService,
                                HmisOutboxRepository outboxRepository,
                                HmisAuditRepository auditRepository,
                                ApplicationEventPublisher eventPublisher) {
        this.pushService = pushService;
        this.configService = configService;
        this.outboxRepository = outboxRepository;
        this.auditRepository = auditRepository;
        this.eventPublisher = eventPublisher;
    }

    @Operation(summary = "Get HMIS export status",
            description = "Returns current export status per vendor: last push, status, next scheduled push.")
    @GetMapping("/status")
    @PreAuthorize("hasRole('COC_ADMIN')")
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
    @PreAuthorize("hasRole('COC_ADMIN')")
    public ResponseEntity<List<HmisInventoryRecord>> getPreview(
            @Parameter(description = "Filter by population type") @RequestParam(required = false) String populationType,
            @Parameter(description = "Filter DV only") @RequestParam(required = false) Boolean dvOnly) throws Exception {
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
    @PreAuthorize("hasRole('COC_ADMIN')")
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
            description = "Initiates an immediate push to all enabled vendors. COC_ADMIN only — "
                    + "tenant-scoped operation that exports the caller's tenant data. "
                    + "Requires X-Confirm-HMIS-Push: CONFIRM header to prevent accidental triggers. "
                    + "Writes a tenant-scoped HMIS_EXPORT_TRIGGERED audit_event row with the "
                    + "caller's userId, the vendor list, and the outbox entry count.")
    @PostMapping("/push")
    @PreAuthorize("hasRole('COC_ADMIN')")
    public ResponseEntity<Map<String, Object>> manualPush(
            @RequestHeader(value = "X-Confirm-HMIS-Push", required = false) String confirmHeader)
            throws Exception {
        // G-4.4 §F16 mitigation (Marcus/Jordan HIGH):
        //
        // (1) The HMIS push endpoint reverted from @PlatformAdminOnly to
        //     COC_ADMIN — its service contract reads TenantContext, which
        //     a platform-operator JWT does not populate. The revert
        //     broadened authority (CoC admins who never had PLATFORM_ADMIN
        //     are now authorized to trigger an outbound bed-inventory
        //     export to a 3rd-party HMIS vendor) and dropped the
        //     platform_admin_access_log audit trail G-4.3 attached.
        //
        // (2) Confirm-header gate: requiring X-Confirm-HMIS-Push: CONFIRM
        //     prevents accidental triggers (e.g., a misclick on a future
        //     admin UI button or a copy-paste curl). Mirrors the
        //     X-Confirm-Policy-Change pattern on TenantController and the
        //     X-Confirm-Reset pattern on TestResetController.
        //
        // (3) Audit event: per-tenant HMIS_EXPORT_TRIGGERED audit_event
        //     row replaces the lost PAL row. Captures actor identity,
        //     vendor list, and outbox count. Goes through the standard
        //     ApplicationEventPublisher → AuditEventService onAuditEvent
        //     pipeline so it lands on the tenant's audit chain like every
        //     other tenant-scoped action.
        //
        // (4) F14 (separate /api/v1/admin/tenants/{id}/hmis/push for
        //     cross-tenant platform-operator use) remains deferred to
        //     G-4.5 / a dedicated micro-change post-v0.53.

        if (!"CONFIRM".equals(confirmHeader)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "missing_confirmation",
                    "message", "Missing or invalid X-Confirm-HMIS-Push header. Send "
                            + "'CONFIRM' to proceed. HMIS push is irreversible — every "
                            + "vendor configured for this tenant will receive the current "
                            + "bed inventory snapshot."));
        }

        UUID tenantId = TenantContext.getTenantId();
        UUID actorUserId = TenantContext.getUserId();
        int created = pushService.createOutboxEntriesForCurrentTenant();
        pushService.processOutbox();

        // Audit row: tenant-scoped, action=HMIS_EXPORT_TRIGGERED. Detail blob
        // captures vendor type list + outbox count for compliance review.
        // No vendor secrets / API keys land in the row (configService.getVendors
        // returns the type enum + flags only when projected this way).
        List<String> vendorTypes = configService.getVendors(tenantId).stream()
                .filter(HmisVendorConfig::enabled)
                .map(v -> v.type().name())
                .toList();
        try {
            eventPublisher.publishEvent(new AuditEventRecord(
                    actorUserId,
                    null,
                    AuditEventType.HMIS_EXPORT_TRIGGERED,
                    Map.of(
                            "vendorTypes", vendorTypes,
                            "outboxEntriesCreated", created
                    ),
                    null
            ));
        } catch (RuntimeException e) {
            // Don't fail the push because the audit row failed — log loud
            // and continue. Operators MUST notice missing audit rows via
            // the fabt.audit.system_insert.count alert (already wired in
            // G-1) so a silent regression is impossible.
            log.error("HMIS push audit event publish failed — push completed but "
                    + "audit row may be missing. tenant={} actor={}", tenantId, actorUserId, e);
        }

        return ResponseEntity.ok(Map.of("outboxEntriesCreated", created, "status", "push initiated"));
    }

    @Operation(summary = "List configured HMIS vendors",
            description = "Returns all HMIS vendor configurations for the tenant. API keys shown masked.")
    @GetMapping("/vendors")
    @PreAuthorize("hasRole('COC_ADMIN')")
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

    @Deprecated(forRemoval = true)
    @Operation(summary = "[STUB] Add HMIS vendor — not yet implemented",
            description = "Placeholder endpoint. Returns hardcoded response. Will be implemented in platform-hardening change.")
    @PostMapping("/vendors")
    @PreAuthorize("hasRole('COC_ADMIN')")
    public ResponseEntity<Map<String, Object>> addVendor(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(501).body(Map.of("error", "not_implemented",
                "message", "HMIS vendor management is not yet implemented"));
    }

    @Deprecated(forRemoval = true)
    @Operation(summary = "[STUB] Update HMIS vendor — not yet implemented",
            description = "Placeholder endpoint. Returns 501. Will be implemented in platform-hardening change.")
    @PutMapping("/vendors/{vendorId}")
    @PreAuthorize("hasRole('COC_ADMIN')")
    public ResponseEntity<Map<String, Object>> updateVendor(
            @PathVariable String vendorId, @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(501).body(Map.of("error", "not_implemented",
                "message", "HMIS vendor management is not yet implemented"));
    }

    @Deprecated(forRemoval = true)
    @Operation(summary = "[STUB] Remove HMIS vendor — not yet implemented",
            description = "Placeholder endpoint. Returns 501. Will be implemented in platform-hardening change.")
    @DeleteMapping("/vendors/{vendorId}")
    @PreAuthorize("hasRole('COC_ADMIN')")
    public ResponseEntity<Map<String, Object>> removeVendor(@PathVariable String vendorId) {
        return ResponseEntity.status(501).body(Map.of("error", "not_implemented",
                "message", "HMIS vendor management is not yet implemented"));
    }

    @Operation(summary = "Retry dead-letter HMIS entry",
            description = "Move a dead-letter outbox entry back to PENDING for reprocessing. PLATFORM_OPERATOR only.")
    @PostMapping("/retry/{outboxId}")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @org.fabt.auth.platform.PlatformAdminOnly(
            reason = "Retry of a dead-letter HMIS export entry — platform authority required because it re-engages an outbound integration that previously failed",
            emits = org.fabt.shared.audit.AuditEventType.PLATFORM_HMIS_EXPORTED)
    public ResponseEntity<Map<String, Object>> retryDeadLetter(@PathVariable UUID outboxId) {
        pushService.retryDeadLetter(outboxId);
        return ResponseEntity.ok(Map.of("status", "entry reset to PENDING", "outboxId", outboxId.toString()));
    }

    private String maskApiKey(String key) {
        if (key == null || key.length() < 8) return "****";
        return "****" + key.substring(key.length() - 4);
    }
}
