package org.fabt.dataimport.service;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.fabt.dataimport.service.ShelterImportService.ShelterImportRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Parses 211-format CSV files into shelter import rows.
 * Uses Apache Commons CSV for robust parsing (BOM handling, escaped quotes, embedded newlines).
 * Supports fuzzy column header matching for common synonyms.
 */
@Service
public class TwoOneOneImportAdapter {

    private static final Logger log = LoggerFactory.getLogger(TwoOneOneImportAdapter.class);

    /**
     * Mapping from recognized header synonyms (lowercase) to canonical FABT field names.
     */
    private static final Map<String, String> HEADER_SYNONYMS = new LinkedHashMap<>();

    static {
        // name
        HEADER_SYNONYMS.put("name", "name");
        HEADER_SYNONYMS.put("agency_name", "name");
        HEADER_SYNONYMS.put("agency name", "name");
        HEADER_SYNONYMS.put("organization", "name");
        HEADER_SYNONYMS.put("organization_name", "name");
        HEADER_SYNONYMS.put("shelter_name", "name");
        HEADER_SYNONYMS.put("shelter name", "name");

        // addressStreet
        HEADER_SYNONYMS.put("address", "addressStreet");
        HEADER_SYNONYMS.put("street", "addressStreet");
        HEADER_SYNONYMS.put("street_address", "addressStreet");
        HEADER_SYNONYMS.put("street address", "addressStreet");
        HEADER_SYNONYMS.put("address_1", "addressStreet");
        HEADER_SYNONYMS.put("address1", "addressStreet");

        // addressCity
        HEADER_SYNONYMS.put("city", "addressCity");
        HEADER_SYNONYMS.put("address_city", "addressCity");
        HEADER_SYNONYMS.put("address city", "addressCity");

        // addressState
        HEADER_SYNONYMS.put("state", "addressState");
        HEADER_SYNONYMS.put("address_state", "addressState");
        HEADER_SYNONYMS.put("address state", "addressState");
        HEADER_SYNONYMS.put("province", "addressState");
        HEADER_SYNONYMS.put("state_province", "addressState");

        // addressZip
        HEADER_SYNONYMS.put("zip", "addressZip");
        HEADER_SYNONYMS.put("zipcode", "addressZip");
        HEADER_SYNONYMS.put("zip_code", "addressZip");
        HEADER_SYNONYMS.put("zip code", "addressZip");
        HEADER_SYNONYMS.put("postal_code", "addressZip");
        HEADER_SYNONYMS.put("postal code", "addressZip");

        // phone
        HEADER_SYNONYMS.put("phone", "phone");
        HEADER_SYNONYMS.put("telephone", "phone");
        HEADER_SYNONYMS.put("phone_number", "phone");
        HEADER_SYNONYMS.put("phone number", "phone");

        // latitude
        HEADER_SYNONYMS.put("lat", "latitude");
        HEADER_SYNONYMS.put("latitude", "latitude");

        // longitude
        HEADER_SYNONYMS.put("lng", "longitude");
        HEADER_SYNONYMS.put("lon", "longitude");
        HEADER_SYNONYMS.put("longitude", "longitude");
    }

    /**
     * Result of previewing column mapping: which CSV columns map to which FABT fields,
     * and which columns were not recognized.
     */
    public record ColumnMapping(Map<String, String> mapped, List<String> unmapped) {
    }

    /**
     * Parse CSV content into shelter import rows.
     * Uses Apache Commons CSV with BOM detection and RFC 4180 compliance.
     */
    public List<ShelterImportRow> parseCsv(String csvContent) {
        if (csvContent == null || csvContent.isBlank()) {
            throw new IllegalArgumentException("CSV content is empty");
        }

        // Strip UTF-8 BOM if present
        String clean = stripBom(csvContent);

        List<CSVRecord> records;
        String[] headers;
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build()
                .parse(new StringReader(clean))) {

            headers = parser.getHeaderNames().toArray(new String[0]);
            records = parser.getRecords();
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse CSV: " + e.getMessage());
        }

        if (records.isEmpty()) {
            return List.of();
        }

        ColumnMapping mapping = resolveMapping(headers);

        // Build reverse map: canonical field name -> header name
        Map<String, String> fieldToHeader = new HashMap<>();
        for (var entry : mapping.mapped().entrySet()) {
            fieldToHeader.putIfAbsent(entry.getValue(), entry.getKey());
        }

        if (!mapping.unmapped().isEmpty()) {
            log.info("CSV import: unmapped columns will be ignored: {}", mapping.unmapped());
        }

        List<ShelterImportRow> rows = new ArrayList<>();

        for (CSVRecord record : records) {
            int rowNum = (int) record.getRecordNumber();
            String name = CsvSanitizer.sanitize(getField(record, fieldToHeader, "name"), rowNum, "name");
            String addressStreet = CsvSanitizer.sanitize(getField(record, fieldToHeader, "addressStreet"), rowNum, "addressStreet");
            String addressCity = CsvSanitizer.sanitize(getField(record, fieldToHeader, "addressCity"), rowNum, "addressCity");
            String addressState = CsvSanitizer.sanitize(getField(record, fieldToHeader, "addressState"), rowNum, "addressState");
            String addressZip = CsvSanitizer.sanitize(getField(record, fieldToHeader, "addressZip"), rowNum, "addressZip");
            String phone = CsvSanitizer.sanitize(getField(record, fieldToHeader, "phone"), rowNum, "phone");
            Double latitude = getDoubleField(record, fieldToHeader, "latitude");
            Double longitude = getDoubleField(record, fieldToHeader, "longitude");

            ShelterImportRow row = new ShelterImportRow(
                    name, addressStreet, addressCity, addressState, addressZip,
                    phone, latitude, longitude,
                    null, null, null, null, null, null, null, null, null, null
            );

            rows.add(row);
        }

        return rows;
    }

    /**
     * Preview the column mapping for a CSV header line.
     */
    public ColumnMapping previewMapping(String csvHeaderLine) {
        if (csvHeaderLine == null || csvHeaderLine.isBlank()) {
            throw new IllegalArgumentException("Header line is empty");
        }
        String clean = stripBom(csvHeaderLine);
        // Parse as a single CSV row to get header values
        try (CSVParser parser = CSVFormat.DEFAULT.parse(new StringReader(clean))) {
            CSVRecord record = parser.iterator().next();
            String[] headers = new String[record.size()];
            for (int i = 0; i < record.size(); i++) {
                headers[i] = record.get(i);
            }
            return resolveMapping(headers);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse header: " + e.getMessage());
        }
    }

    /**
     * Preview with full CSV content — returns mapping plus parsed rows for sample extraction.
     */
    public record PreviewResult(ColumnMapping mapping, List<String[]> allRows) {}

    public PreviewResult previewFull(String csvContent) {
        if (csvContent == null || csvContent.isBlank()) {
            throw new IllegalArgumentException("CSV content is empty");
        }
        String clean = stripBom(csvContent);

        List<String[]> allRows = new ArrayList<>();
        try (CSVParser parser = CSVFormat.DEFAULT
                .builder().setIgnoreEmptyLines(true).setTrim(true).build()
                .parse(new StringReader(clean))) {
            for (CSVRecord record : parser) {
                String[] row = new String[record.size()];
                for (int i = 0; i < record.size(); i++) {
                    row[i] = record.get(i);
                }
                allRows.add(row);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse CSV: " + e.getMessage());
        }

        if (allRows.isEmpty()) {
            throw new IllegalArgumentException("CSV contains no data");
        }
        ColumnMapping mapping = resolveMapping(allRows.get(0));
        return new PreviewResult(mapping, allRows);
    }

    private ColumnMapping resolveMapping(String[] headers) {
        Map<String, String> mapped = new LinkedHashMap<>();
        List<String> unmapped = new ArrayList<>();
        Set<String> alreadyMappedFields = new HashSet<>();

        for (String header : headers) {
            String trimmed = header.trim();
            String normalized = trimmed.toLowerCase();
            String canonical = HEADER_SYNONYMS.get(normalized);

            if (canonical != null && !alreadyMappedFields.contains(canonical)) {
                mapped.put(trimmed, canonical);
                alreadyMappedFields.add(canonical);
            } else {
                unmapped.add(trimmed);
            }
        }

        return new ColumnMapping(mapped, unmapped);
    }

    private String getField(CSVRecord record, Map<String, String> fieldToHeader, String fieldName) {
        String header = fieldToHeader.get(fieldName);
        if (header == null) return null;
        try {
            String value = record.get(header).trim();
            return value.isEmpty() ? null : value;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Double getDoubleField(CSVRecord record, Map<String, String> fieldToHeader, String fieldName) {
        String raw = getField(record, fieldToHeader, fieldName);
        if (raw == null) return null;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            log.warn("CSV import: could not parse '{}' as number for field '{}'", raw, fieldName);
            return null;
        }
    }

    /**
     * Strip UTF-8 BOM (byte order mark) if present.
     * Excel on Windows exports CSV with BOM by default, which causes the first column
     * header to be mismatched if not stripped.
     */
    private String stripBom(String content) {
        if (content != null && content.length() > 0 && content.charAt(0) == '\uFEFF') {
            return content.substring(1);
        }
        return content;
    }
}
