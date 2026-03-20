package org.fabt.dataimport.api;

import org.fabt.dataimport.service.ImportResult;

public record ImportErrorDto(int row, String field, String message) {

    public static ImportErrorDto from(ImportResult.ImportError error) {
        return new ImportErrorDto(error.row(), error.field(), error.message());
    }
}
