package org.fabt.dataimport.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

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
            @RequestParam("file") MultipartFile file) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not set");
        }

        String jsonContent = readFileContent(file);
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "hsds-import.json";

        List<ShelterImportRow> rows = hsdsImportAdapter.parseHsds(jsonContent);

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("HSDS file contains no importable organizations");
        }

        ImportResult result = shelterImportService.importShelters(tenantId, "HSDS", filename, rows);
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
            @RequestParam("file") MultipartFile file) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not set");
        }

        String csvContent = readFileContent(file);
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "211-import.csv";

        List<ShelterImportRow> rows = twoOneOneImportAdapter.parseCsv(csvContent);

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("CSV file contains no data rows");
        }

        ImportResult result = shelterImportService.importShelters(tenantId, "211_CSV", filename, rows);
        return ResponseEntity.status(HttpStatus.OK).body(ImportResultResponse.from(result));
    }

    @Operation(
            summary = "Preview column mapping for a 2-1-1 CSV before importing",
            description = "Accepts a comma-separated header line from a 2-1-1 CSV file and returns " +
                    "the column mapping the importer will use. The response shows which CSV columns " +
                    "map to which shelter fields and which columns will be ignored. Use this before " +
                    "calling POST /api/v1/import/211 to verify that the CSV format is compatible " +
                    "and to communicate any unmapped columns to the user. This is a read-only, " +
                    "side-effect-free operation — no data is imported. " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @GetMapping("/211/preview")
    public ResponseEntity<ColumnMappingResponse> previewCsvMapping(
            @Parameter(description = "Comma-separated header line from the CSV file (e.g., 'Name,Address,City,State,Zip,Phone')")
            @RequestParam("headerLine") String headerLine) {
        ColumnMapping mapping = twoOneOneImportAdapter.previewMapping(headerLine);
        return ResponseEntity.ok(ColumnMappingResponse.from(mapping));
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
}
