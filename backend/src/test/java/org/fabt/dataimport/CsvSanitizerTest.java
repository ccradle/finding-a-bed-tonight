package org.fabt.dataimport;

import org.fabt.dataimport.service.CsvSanitizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CsvSanitizerTest {

    @ParameterizedTest(name = "sanitize(\"{0}\") → \"{1}\"")
    @CsvSource({
            // Formula injection — dangerous prefix stripped
            "=CMD('calc'),CMD('calc')",
            "=1+2,=1+2",                         // = followed by digit → preserved
            "+cmd|'/C calc',cmd|'/C calc'",
            "+1-919-555-0100,+1-919-555-0100",    // + followed by digit → preserved
            "@SUM(A1:A10),SUM(A1:A10)",

            // Dash preserved (common in addresses and phone numbers)
            "-123 Main St,-123 Main St",

            // Clean values unchanged
            "Downtown Warming Station,Downtown Warming Station",
            "123 Main St,123 Main St",
            "919-555-0100,919-555-0100",
            "NC,NC",
            "27601,27601",
    })
    void sanitize_handlesInjectionAndPreservesLegitimate(String input, String expected) {
        assertThat(CsvSanitizer.sanitize(input, 1, "test")).isEqualTo(expected);
    }

    @Test
    void sanitize_nullReturnsNull() {
        assertThat(CsvSanitizer.sanitize(null, 1, "test")).isNull();
    }

    @Test
    void sanitize_emptyReturnsEmpty() {
        assertThat(CsvSanitizer.sanitize("", 1, "test")).isEmpty();
    }

    @Test
    void sanitize_stripsTabCharacters() {
        assertThat(CsvSanitizer.sanitize("Hello\tWorld", 1, "test")).isEqualTo("HelloWorld");
    }

    @Test
    void sanitize_stripsCarriageReturn() {
        assertThat(CsvSanitizer.sanitize("Hello\rWorld", 1, "test")).isEqualTo("HelloWorld");
    }

    @Test
    void sanitize_stripsTabAndCrThenChecksPrefix() {
        // Tab stripped first, then leading = checked
        assertThat(CsvSanitizer.sanitize("\t=CMD('calc')", 1, "test")).isEqualTo("CMD('calc')");
    }

    @Test
    void sanitize_atSignAlwaysStripped() {
        // Even @1 is stripped (@ is never valid as leading char in shelter data)
        assertThat(CsvSanitizer.sanitize("@1", 1, "test")).isEqualTo("1");
    }

    @Test
    void sanitize_singleDangerousCharOnly() {
        assertThat(CsvSanitizer.sanitize("=", 1, "test")).isEmpty();
        assertThat(CsvSanitizer.sanitize("+", 1, "test")).isEmpty();
        assertThat(CsvSanitizer.sanitize("@", 1, "test")).isEmpty();
    }
}
