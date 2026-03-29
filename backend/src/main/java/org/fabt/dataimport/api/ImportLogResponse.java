package org.fabt.dataimport.api;

import java.time.Instant;
import java.util.UUID;

import org.fabt.dataimport.domain.ImportLog;

/**
 * Import history entry matching the frontend contract:
 * { id, importType, filename, created, updated, skipped, errors, createdAt }
 */
public record ImportLogResponse(
        UUID id,
        String importType,
        String filename,
        int created,
        int updated,
        int skipped,
        int errors,
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
