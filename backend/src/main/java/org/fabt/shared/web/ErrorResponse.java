package org.fabt.shared.web;

import java.time.Instant;

public record ErrorResponse(
        String message,
        int status,
        Instant timestamp
) {
    public ErrorResponse(String message, int status) {
        this(message, status, Instant.now());
    }
}
