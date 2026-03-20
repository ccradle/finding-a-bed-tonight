package org.fabt.dataimport.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.fabt.dataimport.service.ShelterImportService.ShelterImportRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Parses 211-format CSV files into shelter import rows.
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
     * First row is treated as headers. Fuzzy matching is applied to column names.
     *
     * @param csvContent raw CSV string
     * @return list of shelter import rows
     * @throws IllegalArgumentException if CSV is empty or has no data rows
     */
    public List<ShelterImportRow> parseCsv(String csvContent) {
        if (csvContent == null || csvContent.isBlank()) {
            throw new IllegalArgumentException("CSV content is empty");
        }

        List<String[]> allRows = parseCsvLines(csvContent);
        if (allRows.isEmpty()) {
            throw new IllegalArgumentException("CSV contains no data");
        }

        String[] headers = allRows.get(0);
        ColumnMapping mapping = resolveMapping(headers);

        // Build reverse map: canonical field name -> column index
        Map<String, Integer> fieldToIndex = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            String canonical = mapping.mapped().get(headers[i].trim());
            if (canonical != null && !fieldToIndex.containsKey(canonical)) {
                fieldToIndex.put(canonical, i);
            }
        }

        if (!mapping.unmapped().isEmpty()) {
            log.info("CSV import: unmapped columns will be ignored: {}", mapping.unmapped());
        }

        List<ShelterImportRow> rows = new ArrayList<>();

        for (int rowIdx = 1; rowIdx < allRows.size(); rowIdx++) {
            String[] values = allRows.get(rowIdx);

            // Skip completely empty rows
            if (isEmptyRow(values)) {
                continue;
            }

            String name = getField(values, fieldToIndex, "name");
            String addressStreet = getField(values, fieldToIndex, "addressStreet");
            String addressCity = getField(values, fieldToIndex, "addressCity");
            String addressState = getField(values, fieldToIndex, "addressState");
            String addressZip = getField(values, fieldToIndex, "addressZip");
            String phone = getField(values, fieldToIndex, "phone");
            Double latitude = getDoubleField(values, fieldToIndex, "latitude");
            Double longitude = getDoubleField(values, fieldToIndex, "longitude");

            ShelterImportRow row = new ShelterImportRow(
                    name,
                    addressStreet,
                    addressCity,
                    addressState,
                    addressZip,
                    phone,
                    latitude,
                    longitude,
                    null,   // dvShelter
                    null,   // sobrietyRequired
                    null,   // idRequired
                    null,   // referralRequired
                    null,   // petsAllowed
                    null,   // wheelchairAccessible
                    null,   // curfewTime
                    null,   // maxStayDays
                    null,   // populationTypesServed
                    null    // capacityByType
            );

            rows.add(row);
        }

        return rows;
    }

    /**
     * Preview the column mapping for a CSV header line.
     * Returns which columns would be mapped and which would be ignored.
     *
     * @param csvHeaderLine the first line of the CSV (comma-separated headers)
     * @return column mapping result
     */
    public ColumnMapping previewMapping(String csvHeaderLine) {
        if (csvHeaderLine == null || csvHeaderLine.isBlank()) {
            throw new IllegalArgumentException("Header line is empty");
        }
        String[] headers = parseCsvRow(csvHeaderLine);
        return resolveMapping(headers);
    }

    private ColumnMapping resolveMapping(String[] headers) {
        Map<String, String> mapped = new LinkedHashMap<>();
        List<String> unmapped = new ArrayList<>();
        Set<String> alreadyMappedFields = new java.util.HashSet<>();

        for (String header : headers) {
            String trimmed = header.trim();
            String normalized = trimmed.toLowerCase();
            String canonical = HEADER_SYNONYMS.get(normalized);

            if (canonical != null && !alreadyMappedFields.contains(canonical)) {
                mapped.put(trimmed, canonical);
                alreadyMappedFields.add(canonical);
            } else {
                // No match, or duplicate mapping — treat as unmapped
                unmapped.add(trimmed);
            }
        }

        return new ColumnMapping(mapped, unmapped);
    }

    private List<String[]> parseCsvLines(String csvContent) {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(csvContent))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    rows.add(parseCsvRow(line));
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read CSV: " + e.getMessage());
        }
        return rows;
    }

    /**
     * Simple CSV row parser that handles quoted fields with commas.
     * Does not handle escaped quotes within quoted fields for simplicity.
     */
    private String[] parseCsvRow(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    // Escaped quote inside quoted field
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());

        return fields.toArray(new String[0]);
    }

    private String getField(String[] values, Map<String, Integer> fieldToIndex, String fieldName) {
        Integer index = fieldToIndex.get(fieldName);
        if (index == null || index >= values.length) {
            return null;
        }
        String value = values[index].trim();
        return value.isEmpty() ? null : value;
    }

    private Double getDoubleField(String[] values, Map<String, Integer> fieldToIndex, String fieldName) {
        String raw = getField(values, fieldToIndex, fieldName);
        if (raw == null) {
            return null;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            log.warn("CSV import: could not parse '{}' as number for field '{}'", raw, fieldName);
            return null;
        }
    }

    private boolean isEmptyRow(String[] values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
