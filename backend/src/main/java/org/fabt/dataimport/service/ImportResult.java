package org.fabt.dataimport.service;

import java.util.List;

public record ImportResult(
        int created,
        int updated,
        int skipped,
        int errors,
        List<ImportError> errorDetails
) {

    public record ImportError(int row, String field, String message) {
    }
}
