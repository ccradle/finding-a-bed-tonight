package org.fabt.dataimport.api;

import java.util.List;

import org.fabt.dataimport.service.ImportResult;

/**
 * Import result matching the frontend contract:
 * { created, updated, skipped, errors: string[] }
 */
public record ImportResultResponse(
        int created,
        int updated,
        int skipped,
        List<String> errors
) {

    public static ImportResultResponse from(ImportResult result) {
        List<String> formattedErrors = result.errorDetails().stream()
                .map(e -> "Row " + e.row() + ": " + e.field() + " — " + e.message())
                .toList();
        return new ImportResultResponse(
                result.created(),
                result.updated(),
                result.skipped(),
                formattedErrors
        );
    }
}
