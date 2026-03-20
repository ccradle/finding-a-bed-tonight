package org.fabt.shared.web;

import java.time.Instant;
import java.util.Map;

/**
 * MCP-ready error response (REQ-MCP-2). AI agents must be able to reason about
 * errors and take corrective action without human intervention.
 *
 * @param error     Snake_case error code (e.g., "not_found", "validation_failed", "duplicate_slug")
 * @param message   Human-readable description
 * @param status    HTTP status code
 * @param timestamp When the error occurred
 * @param context   Domain-specific details (e.g., field_errors, nearest_partial_match, constraints_applied)
 */
public record ErrorResponse(
        String error,
        String message,
        int status,
        Instant timestamp,
        Map<String, Object> context
) {
    public ErrorResponse(String error, String message, int status) {
        this(error, message, status, Instant.now(), Map.of());
    }

    public ErrorResponse(String error, String message, int status, Map<String, Object> context) {
        this(error, message, status, Instant.now(), context);
    }
}
