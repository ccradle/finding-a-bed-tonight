package org.fabt.dataimport.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

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

    /**
     * Import shelters from an HSDS 3.0 JSON file.
     *
     * @param file multipart file upload containing HSDS JSON
     * @return import result with counts and any errors
     */
    @PostMapping("/hsds")
    public ResponseEntity<ImportResultResponse> importHsds(@RequestParam("file") MultipartFile file) {
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

    /**
     * Import shelters from a 211-format CSV file.
     *
     * @param file multipart file upload containing CSV data
     * @return import result with counts and any errors
     */
    @PostMapping("/211")
    public ResponseEntity<ImportResultResponse> importTwoOneOne(@RequestParam("file") MultipartFile file) {
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

    /**
     * Preview column mapping for a 211 CSV. Pass the header line as a query parameter
     * to see which columns will be mapped and which will be ignored.
     *
     * @param headerLine comma-separated header line from the CSV
     * @return column mapping preview
     */
    @GetMapping("/211/preview")
    public ResponseEntity<ColumnMappingResponse> previewCsvMapping(@RequestParam("headerLine") String headerLine) {
        ColumnMapping mapping = twoOneOneImportAdapter.previewMapping(headerLine);
        return ResponseEntity.ok(ColumnMappingResponse.from(mapping));
    }

    /**
     * List import history for the current tenant, ordered by most recent first.
     *
     * @return list of import log entries
     */
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
