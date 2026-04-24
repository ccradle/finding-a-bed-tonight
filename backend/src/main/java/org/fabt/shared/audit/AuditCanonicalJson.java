package org.fabt.shared.audit;

import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase G slice G-2 — canonical JSON form for audit-chain hashing
 * (multi-tenant-production-readiness §8.6a).
 *
 * <h2>Why this exists</h2>
 *
 * <p>The Phase G-1 chain writer ({@link AuditChainHasher}) computes
 * {@code row_hash = SHA-256(prev_hash || canonical_json(row))} at audit
 * INSERT time. The G-2 verifier ({@code AuditChainVerifierJobConfig}) must
 * reproduce the identical hash over the row read back from the database.
 *
 * <p>The problem surfaced during G-1 testing:</p>
 *
 * <ul>
 *   <li><b>Write path</b> — {@link AuditEventService} serialises the details
 *       payload with Jackson using <em>insertion-order</em> keys and compact
 *       (no-whitespace) output: {@code {"k":"v","other":1}}.</li>
 *   <li><b>PostgreSQL JSONB storage</b> — the column is typed {@code JSONB},
 *       which normalises on write: keys are stored <em>alphabetically
 *       sorted</em>, and the {@code ::text} cast emits them with a
 *       {@code ": "} separator: {@code {"k": "v", "other": 1}}.</li>
 *   <li><b>Verify path</b> — the verifier reads {@code details::text} from
 *       the DB and gets the JSONB-canonical form, which differs from the
 *       Jackson output the writer hashed.</li>
 * </ul>
 *
 * <p>This canonicaliser bridges the gap. It accepts EITHER form and produces
 * a single stable representation: <b>alphabetically-sorted keys + compact
 * whitespace</b>. Applied identically at writer AND verifier, both ends
 * produce byte-identical input to SHA-256.
 *
 * <h2>Stability contract</h2>
 *
 * <p>Once the first audit row is hashed with this canonicaliser, the output
 * form is permanent. A future change to field ordering, escape rules, or
 * number rendering would invalidate every historical {@code row_hash} in the
 * DB and every external anchor (Phase G-3). Any change requires recomputing
 * hashes for every affected row AND re-signing the external anchor.
 *
 * <h2>Implementation</h2>
 *
 * <p>The canonical form is produced by a dedicated {@link JsonMapper}
 * configured with {@link MapperFeature#SORT_PROPERTIES_ALPHABETICALLY} and
 * {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS}. JSON is parsed
 * into an {@code Object} (nested {@code Map}/{@code List}/primitives), then
 * re-serialised — whitespace is stripped by Jackson's default compact output
 * and keys land in alphabetical order at every nesting level.
 *
 * <p>Stateless + thread-safe. The shared mapper instance has no per-call
 * state.
 */
public final class AuditCanonicalJson {

    /**
     * Mapper configured for canonical output: sorted keys, compact (no
     * pretty-print). Jackson's default compact serialisation produces
     * exactly the {@code {"a":1,"b":2}} form we want — no additional
     * feature flag needed for whitespace suppression.
     */
    private static final ObjectMapper CANONICAL_MAPPER = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private AuditCanonicalJson() {}

    /**
     * Transform an arbitrary JSON text into the canonical form: sorted keys
     * + compact whitespace. Idempotent — canonicalising canonical input
     * returns byte-identical output.
     *
     * @param jsonText any valid JSON text (Jackson-produced or PG-JSONB-cast
     *                 or already-canonical). {@code null} input returns
     *                 {@code null}.
     * @return canonical JSON text, or {@code null} if input was {@code null}.
     * @throws IllegalArgumentException if {@code jsonText} is not valid JSON
     */
    public static String canonicalize(String jsonText) {
        if (jsonText == null) return null;
        try {
            Object parsed = CANONICAL_MAPPER.readValue(jsonText, Object.class);
            return CANONICAL_MAPPER.writeValueAsString(parsed);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "canonicalize received invalid JSON (len=" + jsonText.length()
                    + "): " + e.getMessage(), e);
        }
    }
}
