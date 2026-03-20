package org.fabt.dataimport.api;

import java.util.List;
import java.util.Map;

import org.fabt.dataimport.service.TwoOneOneImportAdapter.ColumnMapping;

public record ColumnMappingResponse(
        Map<String, String> mapped,
        List<String> unmapped
) {

    public static ColumnMappingResponse from(ColumnMapping mapping) {
        return new ColumnMappingResponse(mapping.mapped(), mapping.unmapped());
    }
}
