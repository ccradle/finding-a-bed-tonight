package org.fabt.dataimport.api;

import java.util.List;

import org.fabt.dataimport.service.ImportResult;

public record ImportResultResponse(
        int created,
        int updated,
        int skipped,
        int errors,
        List<ImportErrorDto> errorDetails
) {

    public static ImportResultResponse from(ImportResult result) {
        List<ImportErrorDto> errorDtos = result.errorDetails().stream()
                .map(ImportErrorDto::from)
                .toList();
        return new ImportResultResponse(
                result.created(),
                result.updated(),
                result.skipped(),
                result.errors(),
                errorDtos
        );
    }
}
