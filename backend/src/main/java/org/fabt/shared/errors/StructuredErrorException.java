package org.fabt.shared.errors;

import java.util.Map;

/**
 * Bad-request exception that carries a structured error code in addition to
 * the human-readable message. The {@link org.fabt.shared.web.GlobalExceptionHandler}
 * surfaces the code in the 400 response's {@code context.errorCode} field,
 * letting the frontend programmatically distinguish a known rejection class
 * (e.g., to render a specific UX with the inventory link) from a generic
 * bad-request.
 *
 * <p>Extends {@link IllegalArgumentException} so the existing 400-mapping
 * catch-all in the global handler still applies for callers that don't
 * inspect the code — an upgrade-in-place rather than a parallel handler.
 *
 * <p>Optional {@link #context()} carries additional structured fields the
 * frontend may want (e.g., {@code remaining_dv_shelter_count} for the
 * disable-rejection error). Returns an empty map when not provided.
 */
public class StructuredErrorException extends IllegalArgumentException {

    private final String errorCode;
    private final Map<String, Object> context;

    public StructuredErrorException(String errorCode, String message) {
        this(errorCode, message, Map.of());
    }

    public StructuredErrorException(String errorCode, String message, Map<String, Object> context) {
        super(message);
        this.errorCode = errorCode;
        this.context = context == null ? Map.of() : Map.copyOf(context);
    }

    public String errorCode() {
        return errorCode;
    }

    public Map<String, Object> context() {
        return context;
    }
}
