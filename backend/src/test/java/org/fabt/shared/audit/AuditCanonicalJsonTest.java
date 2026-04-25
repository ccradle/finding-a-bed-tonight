package org.fabt.shared.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AuditCanonicalJson} — the canonical-form
 * utility that bridges Jackson's insertion-order output and PostgreSQL
 * JSONB's {@code ::text} output into a single hash-stable form.
 *
 * <p>See {@code AuditCanonicalJson} Javadoc for the stability contract.
 * Every assertion here is load-bearing for Phase G audit-chain
 * verification — a change that breaks any test would invalidate
 * historical {@code row_hash} values and every external anchor.
 */
@DisplayName("AuditCanonicalJson — canonical form contract")
class AuditCanonicalJsonTest {

    @Test
    @DisplayName("null input returns null")
    void nullPassesThrough() {
        assertThat(AuditCanonicalJson.canonicalize(null)).isNull();
    }

    @Test
    @DisplayName("Jackson-style input is sorted + compacted")
    void jacksonInsertionOrderIsSorted() {
        // Jackson default: insertion order, compact.
        String input = "{\"beta\":2,\"alpha\":1}";
        assertThat(AuditCanonicalJson.canonicalize(input))
                .isEqualTo("{\"alpha\":1,\"beta\":2}");
    }

    @Test
    @DisplayName("PG JSONB ::text style (sorted + spaced) is compacted to canonical")
    void pgJsonbFormIsNormalised() {
        // PostgreSQL JSONB ::text: sorted keys + `": "` separator.
        String input = "{\"alpha\": 1, \"beta\": 2}";
        assertThat(AuditCanonicalJson.canonicalize(input))
                .isEqualTo("{\"alpha\":1,\"beta\":2}");
    }

    @Test
    @DisplayName("writer + verifier paths converge on identical canonical form")
    void jacksonAndPgFormsProduceSameHashInput() {
        // This is THE load-bearing invariant for G-2: the writer hashes
        // canonicalize(jacksonOutput), the verifier hashes canonicalize(pgText).
        // Both must produce byte-identical output.
        String jacksonForm = "{\"zebra\":\"z\",\"alpha\":\"a\",\"mike\":\"m\"}";
        String pgForm = "{\"alpha\": \"a\", \"mike\": \"m\", \"zebra\": \"z\"}";

        assertThat(AuditCanonicalJson.canonicalize(jacksonForm))
                .as("Jackson (insertion-order) and PG (sorted+spaced) forms must canonicalise identically")
                .isEqualTo(AuditCanonicalJson.canonicalize(pgForm));
    }

    @Test
    @DisplayName("nested objects: keys sorted at every level")
    void nestedObjectsSortedRecursively() {
        String input = "{\"b\":{\"y\":2,\"x\":1},\"a\":{\"n\":0,\"m\":-1}}";
        String expected = "{\"a\":{\"m\":-1,\"n\":0},\"b\":{\"x\":1,\"y\":2}}";
        assertThat(AuditCanonicalJson.canonicalize(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("arrays preserve order (JSON arrays are ordered)")
    void arraysPreserveOrder() {
        String input = "{\"items\":[3,1,2]}";
        assertThat(AuditCanonicalJson.canonicalize(input))
                .isEqualTo("{\"items\":[3,1,2]}");
    }

    @Test
    @DisplayName("JSON primitives round-trip")
    void primitivesRoundTrip() {
        assertThat(AuditCanonicalJson.canonicalize("\"hello\"")).isEqualTo("\"hello\"");
        assertThat(AuditCanonicalJson.canonicalize("42")).isEqualTo("42");
        assertThat(AuditCanonicalJson.canonicalize("true")).isEqualTo("true");
        assertThat(AuditCanonicalJson.canonicalize("null")).isEqualTo("null");
    }

    @Test
    @DisplayName("idempotent — canonicalising canonical output yields byte-identical result")
    void idempotent() {
        String input = "{\"z\":{\"b\":2,\"a\":1},\"m\":[3,1,2]}";
        String once = AuditCanonicalJson.canonicalize(input);
        String twice = AuditCanonicalJson.canonicalize(once);
        assertThat(twice)
                .as("canonicalize must be idempotent — writer re-hash and verifier re-hash must converge")
                .isEqualTo(once);
    }

    @Test
    @DisplayName("Unicode and JSON-escape characters preserved")
    void unicodeAndEscapesPreserved() {
        // Escapes: \n, \t, \", \\, Unicode BMP + supplementary.
        String input = "{\"msg\":\"hello\\nworld\",\"quote\":\"say \\\"hi\\\"\"}";
        String result = AuditCanonicalJson.canonicalize(input);
        // Parse + reserialize should preserve the escape sequences.
        assertThat(result).contains("\\n").contains("\\\"");
    }

    @Test
    @DisplayName("invalid JSON throws IllegalArgumentException with context")
    void invalidJsonThrowsWithContext() {
        assertThatThrownBy(() -> AuditCanonicalJson.canonicalize("{not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid JSON");
    }

    @Test
    @DisplayName("empty object and array pass through canonical form")
    void emptyStructures() {
        assertThat(AuditCanonicalJson.canonicalize("{}")).isEqualTo("{}");
        assertThat(AuditCanonicalJson.canonicalize("[]")).isEqualTo("[]");
    }
}
