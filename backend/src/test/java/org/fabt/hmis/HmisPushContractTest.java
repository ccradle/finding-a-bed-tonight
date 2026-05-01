package org.fabt.hmis;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.hmis.domain.HmisInventoryRecord;
import org.fabt.hmis.service.HmisTransformer;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.55.1-O1: contract test asserting hold-attribution PII NEVER leaks into HMIS push payloads.
 *
 * <p>Doc claim ({@code hmisindex.html} post-§12 + AsyncAPI scope sentence per §3.1) says HMIS
 * push carries no client PII. This test grounds that claim in code so a future projection-layer
 * regression that adds an inappropriate JOIN or selects a PII column gets caught by CI rather
 * than by an audit.
 *
 * <p>Per warroom B2 + Q4 (thorough form), the assertion is multi-layered:
 *
 * <ol>
 *   <li><b>Schema-absent at projection record</b>: {@link HmisInventoryRecord} has no fields
 *       matching hold-attribution PII patterns (verified via reflection on record components).</li>
 *   <li><b>Schema-absent at outbox table</b>: {@code hmis_outbox} table has no PII columns
 *       (verified via {@code information_schema.columns} query).</li>
 *   <li><b>Serialized-payload absent</b>: with PII seeded on a reservation, the transformer's
 *       output for that tenant — when serialized exactly as the push pipeline serializes it
 *       (via {@link ObjectMapper#writeValueAsString}) — contains no trace of the seeded PII
 *       values (substring search). Sanity-controlled by asserting the seeded reservation row
 *       still has the encrypted PII (without that control, a silent seed failure would also
 *       pass).</li>
 *   <li><b>Both reentryMode states</b>: parameterized via {@code @ValueSource} to prove the
 *       gate is unconditional regardless of tenant flag. Different forms across reentryMode
 *       states would itself be a regression (the projection layer doesn't read the flag).</li>
 * </ol>
 *
 * <p><b>Scope:</b> this test covers the canonical {@code hmis-push} projection layer
 * ({@link HmisTransformer#buildInventory}). Tenant-specific custom adapters (per the
 * {@code hmis-vendor-adapters} capability) are out of scope; if a tenant ever configures a
 * custom adapter that touches the {@code reservation} table directly, that adapter gets its
 * own contract test.
 *
 * <p>The thorough assertion shape (raw {@link JdbcTemplate} reads on the reservation sanity
 * check + reflection on the record class + {@code information_schema} on the outbox table) is
 * per v0.55.1 warroom B2: a single {@code assertThat(value).isNull()} would let a projection
 * regression emitting empty strings or a renamed column slip through. This test doesn't go
 * through any DTO that might {@code @JsonInclude(Include.NON_NULL)}-coerce nulls or hide
 * present-but-blank values.
 */
class HmisPushContractTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private HmisTransformer transformer;

    @Autowired
    private ObjectMapper objectMapper;

    private HttpHeaders adminHeaders;
    private HttpHeaders outreachHeaders;
    private UUID tenantId;
    private UUID shelterId;

    /**
     * Hold-attribution PII column names (pulled from V90 / `reservation` migration).
     * Used by the schema-absence assertions to prove the projection table lacks them.
     */
    private static final List<String> PII_COLUMN_NAMES = List.of(
            "held_for_client_name_encrypted",
            "held_for_client_dob_encrypted",
            "hold_notes_encrypted"
    );

    /**
     * Lower-case substrings that PII-flavored field names would contain. Used by the
     * record-component reflection assertion (catches any future field added to the
     * projection record that carries PII semantics).
     */
    private static final List<String> PII_FIELD_NAME_PATTERNS = List.of(
            "heldforclient",
            "clientname",
            "clientdob",
            "holdnotes"
    );

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        authHelper.setupOutreachWorkerUser();

        var dvAdmin = authHelper.setupUserWithDvAccess(
                "dvadmin-hmis-contract@test.fabt.org", "DV Admin HMIS Contract",
                new String[]{"PLATFORM_ADMIN", "COC_ADMIN"});
        adminHeaders = authHelper.headersForUser(dvAdmin);
        outreachHeaders = authHelper.outreachWorkerHeaders();

        tenantId = authHelper.getTestTenantId();
        TenantContext.runWithContext(tenantId, true, () -> {
            shelterId = createNonDvShelter("HMIS Contract Test Shelter");
        });
    }

    @Test
    @DisplayName("Schema-absent at projection record: HmisInventoryRecord has no PII-flavored fields")
    void hmisInventoryRecord_hasNoPiiFlavoredFields() {
        RecordComponent[] components = HmisInventoryRecord.class.getRecordComponents();
        assertThat(components)
                .as("HmisInventoryRecord must remain a record (this assertion structure depends on it)")
                .isNotEmpty();

        for (RecordComponent component : components) {
            String lower = component.getName().toLowerCase();
            for (String pattern : PII_FIELD_NAME_PATTERNS) {
                assertThat(lower)
                        .as("HmisInventoryRecord field '%s' contains PII-flavored substring '%s' — "
                                + "possible leak path; review whether this field is required for HMIS"
                                + " export and consider renaming or removing.",
                                component.getName(), pattern)
                        .doesNotContain(pattern);
            }
        }
    }

    @Test
    @DisplayName("Schema-absent at outbox table: hmis_outbox has no PII columns")
    void hmisOutboxEntryTable_hasNoPiiColumns() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                + "WHERE table_name = 'hmis_outbox' AND table_schema = 'public'",
                String.class);

        assertThat(columns)
                .as("hmis_outbox must exist and have columns (test prerequisite)")
                .isNotEmpty();

        for (String column : columns) {
            String lower = column.toLowerCase();
            assertThat(PII_COLUMN_NAMES)
                    .as("Column '%s' in hmis_outbox matches a known PII column name — "
                            + "the outbox table must be projection-only, not a join target for "
                            + "reservation PII.", column)
                    .noneMatch(piiName -> lower.equals(piiName));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Serialized-payload absent: HMIS transformer output (the canonical pipeline payload) "
            + "carries no trace of seeded hold-attribution PII regardless of reentryMode")
    void holdAttributionPii_absentFromTransformerSerializedPayload(boolean reentryMode) throws Exception {
        // ---- Seed reservation with PII via the existing API (uses the encryption pipeline) ----
        String clientName = "Probe-Person-" + UUID.randomUUID();
        String clientDob = "1985-06-15";
        String holdNotes = "Probe-Notes-" + UUID.randomUUID() + "-must-not-leak";

        UUID reservationId = postHoldWithPii(clientName, clientDob, holdNotes);

        // ---- Sanity: the encrypted PII landed in reservation. Without this control, a silent
        // seed failure (e.g., the API rejected the body) would also pass the leak-test.
        Map<String, Object> reservationRow = jdbcTemplate.queryForMap(
                "SELECT held_for_client_name_encrypted, held_for_client_dob_encrypted, "
                + "hold_notes_encrypted FROM reservation WHERE id = ?", reservationId);
        assertThat(reservationRow.get("held_for_client_name_encrypted"))
                .as("Sanity: encrypted PII must be present on reservation row to make the leak-test "
                        + "meaningful (reentryMode=%s)", reentryMode)
                .isNotNull();

        // ---- Flip reentryMode flag for this branch of the parameterized test ----
        jdbcTemplate.update(
                "UPDATE tenant SET config = jsonb_set(config, '{features,reentryMode}', ?::jsonb, true) "
                + "WHERE id = ?",
                String.valueOf(reentryMode), tenantId);

        // ---- Run the canonical projection layer ----
        List<HmisInventoryRecord> records = transformer.buildInventory(tenantId);
        assertThat(records)
                .as("Transformer should produce at least one inventory record for the seeded shelter "
                        + "(reentryMode=%s)", reentryMode)
                .isNotEmpty();

        // ---- Serialize exactly as HmisPushService.createOutboxEntriesForTenant does ----
        String payload = objectMapper.writeValueAsString(records);

        // ---- Thorough payload-absence: substring-search the JSON for the seeded PII. Any hit
        // means the projection layer somehow pulled reservation-row PII into the inventory. ----
        assertThat(payload)
                .as("HMIS push payload must not contain seeded client name (reentryMode=%s)", reentryMode)
                .doesNotContain(clientName);
        assertThat(payload)
                .as("HMIS push payload must not contain seeded DOB (reentryMode=%s)", reentryMode)
                .doesNotContain(clientDob);
        assertThat(payload)
                .as("HMIS push payload must not contain seeded hold notes (reentryMode=%s)", reentryMode)
                .doesNotContain(holdNotes);

        // ---- Defense-in-depth: the encrypted blob itself must also not appear (would catch a
        // serializer that pulled the encrypted column rather than decrypting + re-encrypting) ----
        String nameEnc = (String) reservationRow.get("held_for_client_name_encrypted");
        if (nameEnc != null && !nameEnc.isEmpty()) {
            assertThat(payload)
                    .as("HMIS push payload must not contain encrypted client-name blob "
                            + "(reentryMode=%s)", reentryMode)
                    .doesNotContain(nameEnc);
        }

        // ---- Defense-in-depth: payload must not contain any of the PII column names as JSON
        // keys (would catch a future projection that includes the column name even with null
        // values, e.g., from a `SELECT *`) ----
        for (String piiCol : PII_COLUMN_NAMES) {
            assertThat(payload.toLowerCase())
                    .as("HMIS push payload must not mention PII column name '%s' (reentryMode=%s)",
                            piiCol, reentryMode)
                    .doesNotContain(piiCol);
            // Also check camelCase variant (Jackson default field-naming for record components)
            String camelCase = toCamelCase(piiCol);
            assertThat(payload)
                    .as("HMIS push payload must not mention PII field name '%s' (reentryMode=%s)",
                            camelCase, reentryMode)
                    .doesNotContain(camelCase);
        }
    }

    // -------------------- helpers --------------------

    /**
     * Convert snake_case to camelCase. {@code held_for_client_name_encrypted} →
     * {@code heldForClientNameEncrypted}.
     */
    private static String toCamelCase(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private UUID createNonDvShelter(String name) {
        String body = String.format("""
                {
                  "name": "%s",
                  "addressStreet": "100 HMIS Contract Test St",
                  "addressCity": "Raleigh",
                  "addressState": "NC",
                  "addressZip": "27601",
                  "dvShelter": false,
                  "constraints": { "populationTypesServed": ["SINGLE_ADULT"] },
                  "capacities": [{"populationType": "SINGLE_ADULT", "bedsTotal": 10}]
                }
                """, name);
        ResponseEntity<Map> resp = restTemplate.exchange(
                "/api/v1/shelters", HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders), Map.class);
        if (resp.getStatusCode() != HttpStatus.CREATED) {
            throw new AssertionError(
                    "POST /shelters returned " + resp.getStatusCode() + " — body: " + resp.getBody());
        }
        return UUID.fromString((String) resp.getBody().get("id"));
    }

    private UUID postHoldWithPii(String name, String dob, String notes) {
        String body = String.format("""
                {
                  "shelterId": "%s",
                  "populationType": "SINGLE_ADULT",
                  "notes": "operator-side notes",
                  "heldForClientName": "%s",
                  "heldForClientDob": "%s",
                  "holdNotes": "%s"
                }
                """, shelterId, name, dob, notes);
        ResponseEntity<Map> resp = restTemplate.exchange(
                "/api/v1/reservations", HttpMethod.POST,
                new HttpEntity<>(body, outreachHeaders), Map.class);
        if (resp.getStatusCode() != HttpStatus.CREATED) {
            throw new AssertionError(
                    "POST /reservations returned " + resp.getStatusCode()
                    + " — body: " + resp.getBody());
        }
        return UUID.fromString((String) resp.getBody().get("id"));
    }
}
