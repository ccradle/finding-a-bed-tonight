package org.fabt.dataimport.api;

import java.time.Instant;
import java.util.UUID;

import org.fabt.dataimport.domain.ImportLog;

public record ImportLogResponse(
        UUID id,
        String importType,
        String filename,
        int createdCount,
        int updatedCount,
        int skippedCount,
        int errorCount,
        Instant createdAt
) {

    public static ImportLogResponse from(ImportLog importLog) {
        return new ImportLogResponse(
                importLog.getId(),
                importLog.getImportType(),
                importLog.getFilename(),
                importLog.getCreatedCount(),
                importLog.getUpdatedCount(),
                importLog.getSkippedCount(),
                importLog.getErrorCount(),
                importLog.getCreatedAt()
        );
    }
}
