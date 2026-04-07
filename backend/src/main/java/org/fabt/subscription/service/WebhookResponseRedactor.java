package org.fabt.subscription.service;

import java.util.regex.Pattern;

/**
 * Redacts sensitive data from webhook response bodies before storing in delivery log.
 * Uses regex patterns for common secret types. Not a comprehensive PII scanner —
 * for HIPAA-level PII detection, evaluate Phileas (ai.philterd:phileas).
 *
 * Applied before persistence (in recordDelivery), not at query time.
 */
public final class WebhookResponseRedactor {

    private WebhookResponseRedactor() {}

    private static final String REDACTED = "[REDACTED]";

    // Order matters: more specific patterns first
    private static final Pattern[] PATTERNS = {
            // Bearer tokens
            Pattern.compile("Bearer\\s+[A-Za-z0-9\\-._~+/]+=*", Pattern.CASE_INSENSITIVE),
            // AWS access keys
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            // Generic API key/token/secret values (key=value or "key":"value" patterns)
            Pattern.compile("(?i)(api[_-]?key|token|secret|password|authorization)[\"']?\\s*[:=]\\s*[\"']?[A-Za-z0-9\\-._~+/]{16,}[\"']?"),
            // Email addresses
            Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}"),
            // US SSN
            Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
            // Credit card numbers (Visa, MC, Amex)
            Pattern.compile("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13})\\b"),
    };

    /**
     * Redact sensitive patterns from the response body.
     * Returns null if input is null.
     */
    public static String redact(String responseBody) {
        if (responseBody == null) return null;

        String result = responseBody;
        for (Pattern pattern : PATTERNS) {
            result = pattern.matcher(result).replaceAll(REDACTED);
        }
        return result;
    }
}
