package org.fabt.dataimport.api;

import java.util.ArrayList;
import java.util.List;

import org.fabt.dataimport.service.TwoOneOneImportAdapter.ColumnMapping;

/**
 * Preview response matching the frontend contract:
 * { columns: [{sourceColumn, targetField, sampleValues}], totalRows, unmapped }
 */
public record ColumnMappingResponse(
        List<ColumnMappingEntry> columns,
        int totalRows,
        List<String> unmapped
) {

    public record ColumnMappingEntry(
            String sourceColumn,
            String targetField,
            List<String> sampleValues
    ) {}

    public static ColumnMappingResponse from(ColumnMapping mapping, List<String[]> dataRows) {
        List<ColumnMappingEntry> columns = new ArrayList<>();

        // Build header index for sample value extraction
        // mapping.mapped() is LinkedHashMap: CSV header → canonical field name
        for (var entry : mapping.mapped().entrySet()) {
            String sourceColumn = entry.getKey();
            String targetField = entry.getValue();

            // Find column index in original headers for sample extraction
            List<String> samples = new ArrayList<>();
            // dataRows includes header as first row; skip it for samples
            // We need the header row to find the column index
            if (!dataRows.isEmpty()) {
                String[] headers = dataRows.get(0);
                int colIndex = -1;
                for (int i = 0; i < headers.length; i++) {
                    if (headers[i].trim().equals(sourceColumn)) {
                        colIndex = i;
                        break;
                    }
                }
                if (colIndex >= 0) {
                    // Extract up to 3 sample values from data rows (skip header at index 0)
                    for (int r = 1; r < Math.min(dataRows.size(), 4); r++) {
                        String[] row = dataRows.get(r);
                        if (colIndex < row.length) {
                            String val = row[colIndex].trim();
                            if (!val.isEmpty()) {
                                samples.add(val);
                            }
                        }
                    }
                }
            }

            columns.add(new ColumnMappingEntry(sourceColumn, targetField, samples));
        }

        // totalRows = all rows minus header
        int totalRows = Math.max(0, dataRows.size() - 1);

        return new ColumnMappingResponse(columns, totalRows, mapping.unmapped());
    }
}
