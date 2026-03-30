package org.fabt.dataimport.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sanitizes imported string values to prevent CSV injection (CWE-1236).
 *
 * <p>When imported data is later exported as CSV and opened in spreadsheet software,
 * cells starting with {@code =}, {@code +}, or {@code @} can trigger formula execution.
 * This sanitizer strips dangerous leading characters while preserving legitimate data
 * patterns common in shelter records (phone numbers, addresses).</p>
 *
 * <p><strong>Scope:</strong> This sanitizer is designed for shelter data fields
 * (name, address, city, state, zip, phone). The {@code @}-stripping rule assumes
 * {@code @} is never a valid leading character. If the import format ever includes
 * email fields, a field-type-aware sanitization mode would be needed.</p>
 *
 * @see <a href="https://owasp.org/www-community/attacks/CSV_Injection">OWASP CSV Injection</a>
 * @see <a href="https://cwe.mitre.org/data/definitions/1236.html">CWE-1236</a>
 */
public final class CsvSanitizer {

    private static final Logger log = LoggerFactory.getLogger(CsvSanitizer.class);

    private CsvSanitizer() {}

    /**
     * Sanitize a single field value for CSV injection prevention.
     *
     * <ul>
     *   <li>{@code =} stripped when followed by a non-digit (preserves {@code =5})</li>
     *   <li>{@code +} stripped when followed by a non-digit (preserves {@code +1-919-555-0100})</li>
     *   <li>{@code @} stripped always (never valid as leading char in shelter data)</li>
     *   <li>Tab ({@code \t}) and carriage return ({@code \r}) stripped throughout</li>
     *   <li>{@code -} preserved (common in addresses and phone numbers)</li>
     * </ul>
     *
     * @param value the raw field value (may be null)
     * @param rowNum row number for logging (1-based)
     * @param fieldName field name for logging context
     * @return sanitized value, or null if input was null
     */
    public static String sanitize(String value, int rowNum, String fieldName) {
        if (value == null) {
            return null;
        }

        String result = value;

        // Strip tab and carriage return characters throughout
        if (result.indexOf('\t') >= 0 || result.indexOf('\r') >= 0) {
            result = result.replace("\t", "").replace("\r", "");
            log.warn("Import row {}, field '{}': stripped tab/CR characters", rowNum, fieldName);
        }

        if (result.isEmpty()) {
            return result;
        }

        char first = result.charAt(0);
        boolean hasNextChar = result.length() > 1;
        boolean nextIsDigit = hasNextChar && Character.isDigit(result.charAt(1));

        if (first == '=' && !nextIsDigit) {
            result = result.substring(1);
            log.warn("Import row {}, field '{}': stripped leading '=' (CSV injection prevention)", rowNum, fieldName);
        } else if (first == '+' && !nextIsDigit) {
            result = result.substring(1);
            log.warn("Import row {}, field '{}': stripped leading '+' (CSV injection prevention)", rowNum, fieldName);
        } else if (first == '@') {
            result = result.substring(1);
            log.warn("Import row {}, field '{}': stripped leading '@' (CSV injection prevention)", rowNum, fieldName);
        }

        return result;
    }

    /**
     * Convenience overload without row/field context (for unit testing or non-import use).
     */
    public static String sanitize(String value) {
        return sanitize(value, 0, "unknown");
    }
}
