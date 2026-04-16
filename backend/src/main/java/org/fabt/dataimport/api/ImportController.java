package org.fabt.dataimport.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.fabt.dataimport.domain.ImportLog;
import org.fabt.dataimport.repository.ImportLogRepository;
import org.fabt.dataimport.service.HsdsImportAdapter;
import org.fabt.dataimport.service.ImportResult;
import org.fabt.dataimport.service.ShelterImportService;
import org.fabt.dataimport.service.ShelterImportService.ShelterImportRow;
import org.fabt.dataimport.service.TwoOneOneImportAdapter;
import org.fabt.dataimport.service.TwoOneOneImportAdapter.ColumnMapping;
import org.fabt.shared.web.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

// TODO: configure max upload size in application.yml (e.g., spring.servlet.multipart.max-file-size=10MB)

@RestController
@RequestMapping("/api/v1/import")
@PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
public class ImportController {

    private final ShelterImportService shelterImportService;
    private final HsdsImportAdapter hsdsImportAdapter;
    private final TwoOneOneImportAdapter twoOneOneImportAdapter;
    private final ImportLogRepository importLogRepository;

    public ImportController(ShelterImportService shelterImportService,
                            HsdsImportAdapter hsdsImportAdapter,
                            TwoOneOneImportAdapter twoOneOneImportAdapter,
                            ImportLogRepository importLogRepository) {
        this.shelterImportService = shelterImportService;
        this.hsdsImportAdapter = hsdsImportAdapter;
        this.twoOneOneImportAdapter = twoOneOneImportAdapter;
        this.importLogRepository = importLogRepository;
    }

    @Operation(
            summary = "Import shelters from an HSDS 3.0 JSON file",
            description = "Accepts a multipart file upload containing shelter data in HSDS 3.0 " +
                    "(Human Services Data Specification) JSON format and imports the organizations " +
                    "as shelters within the caller's tenant. The import is upsert-based — shelters " +
                    "are matched by name, and existing records are updated rather than duplicated. " +
                    "The response includes counts of created, updated, and skipped records, plus " +
                    "any row-level errors. An import log entry is recorded for audit. Returns 400 " +
                    "if the file is empty or contains no importable organizations. Returns 500 if " +
                    "the file cannot be parsed as valid HSDS JSON. " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @PostMapping("/hsds")
    public ResponseEntity<ImportResultResponse> importHsds(
            @Parameter(description = "HSDS 3.0 JSON file containing organizations to import")
            @RequestParam("file") MultipartFile file) throws Exception {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not set");
        }

        validateJsonMimeType(file);
        String jsonContent = readFileContent(file);
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "hsds-import.json";

        List<ShelterImportRow> rows = hsdsImportAdapter.parseHsds(jsonContent);

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("HSDS file contains no importable organizations");
        }

        ImportResult result = shelterImportService.importShelters("HSDS", filename, rows);
        return ResponseEntity.status(HttpStatus.OK).body(ImportResultResponse.from(result));
    }

    @Operation(
            summary = "Import shelters from a 2-1-1 format CSV file",
            description = "Accepts a multipart file upload containing shelter data in 2-1-1 CSV " +
                    "format (the format used by United Way 2-1-1 referral systems) and imports " +
                    "the rows as shelters within the caller's tenant. Column mapping is automatic " +
                    "based on header names — use GET /api/v1/import/211/preview to preview the " +
                    "mapping before importing. The import is upsert-based. The response includes " +
                    "counts of created, updated, and skipped records, plus row-level errors. " +
                    "Returns 400 if the file is empty or contains no data rows. " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @PostMapping("/211")
    public ResponseEntity<ImportResultResponse> importTwoOneOne(
            @Parameter(description = "CSV file in 2-1-1 format containing shelter data to import")
            @RequestParam("file") MultipartFile file) throws Exception {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not set");
        }

        validateCsvMimeType(file);
        String csvContent = readFileContent(file);
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "211-import.csv";

        List<ShelterImportRow> rows = twoOneOneImportAdapter.parseCsv(csvContent);

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("CSV file contains no data rows");
        }

        ImportResult result = shelterImportService.importShelters("211_CSV", filename, rows);
        return ResponseEntity.status(HttpStatus.OK).body(ImportResultResponse.from(result));
    }

    @Operation(
            summary = "Preview column mapping for a 2-1-1 CSV before importing",
            description = "Accepts a multipart CSV file and returns the column mapping the importer " +
                    "will use, plus sample values from the first rows. The response shows which CSV " +
                    "columns map to which shelter fields and which columns will be ignored. Use this " +
                    "before calling POST /api/v1/import/211 to verify that the CSV format is " +
                    "compatible. This is a read-only, side-effect-free operation — no data is imported. " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @PostMapping("/211/preview")
    public ResponseEntity<ColumnMappingResponse> previewCsvMapping(
            @Parameter(description = "CSV file in 2-1-1 format to preview column mapping")
            @RequestParam("file") MultipartFile file) {
        validateCsvMimeType(file);
        String csvContent = readFileContent(file);
        TwoOneOneImportAdapter.PreviewResult preview = twoOneOneImportAdapter.previewFull(csvContent);
        return ResponseEntity.ok(ColumnMappingResponse.from(preview.mapping(), preview.allRows()));
    }

    @Operation(
            summary = "Preview import outcome (dry run) for a 2-1-1 CSV",
            description = "Validates the CSV and counts how many shelters will be created vs. " +
                    "updated, WITHOUT committing any changes. Shows per-row validation errors " +
                    "and DV flag change safety notices. Use this after column mapping preview " +
                    "and before the final POST /api/v1/import/211 commit. " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @PostMapping("/211/preview-import")
    public ResponseEntity<ImportResultResponse> previewImport(
            @Parameter(description = "CSV file in 2-1-1 format to preview import outcome")
            @RequestParam("file") MultipartFile file) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not set");
        }

        validateCsvMimeType(file);
        String csvContent = readFileContent(file);
        List<ShelterImportRow> rows = twoOneOneImportAdapter.parseCsv(csvContent);

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("CSV file contains no data rows");
        }

        ImportResult preview = shelterImportService.previewImport(tenantId, rows);
        return ResponseEntity.ok(ImportResultResponse.from(preview));
    }

    @Operation(
            summary = "List import history for the authenticated tenant",
            description = "Returns all import log entries for the caller's tenant, ordered by most " +
                    "recent first. Each entry includes the import id, source format (HSDS or " +
                    "211_CSV), original filename, row counts (created, updated, skipped, errored), " +
                    "and the timestamp of the import. Use this to audit past imports and track " +
                    "data provenance. The list is unpaginated. " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @GetMapping("/history")
    public ResponseEntity<List<ImportLogResponse>> history() {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not set");
        }

        List<ImportLog> logs = importLogRepository.findByTenantId(tenantId);
        List<ImportLogResponse> response = logs.stream()
                .map(ImportLogResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    private String readFileContent(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read uploaded file: " + e.getMessage());
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ImportController.class);

    private static final Set<String> CSV_MIME_TYPES = Set.of(
            "text/csv", "text/plain", "application/csv", "application/octet-stream");
    private static final Set<String> JSON_MIME_TYPES = Set.of(
            "application/json", "text/plain", "application/octet-stream");

    private void validateCsvMimeType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null) {
            log.warn("Import file '{}' has no content type — accepting but logging for monitoring",
                    file.getOriginalFilename());
            return;
        }
        if (!CSV_MIME_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("File must be CSV format (received: " + contentType + ")");
        }
    }

    private void validateJsonMimeType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null) {
            log.warn("Import file '{}' has no content type — accepting but logging for monitoring",
                    file.getOriginalFilename());
            return;
        }
        if (!JSON_MIME_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("File must be JSON format (received: " + contentType + ")");
        }
    }
}
