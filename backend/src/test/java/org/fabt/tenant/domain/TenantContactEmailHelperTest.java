package org.fabt.tenant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.fabt.shared.config.JsonString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link Tenant#readContactEmail(JsonString, ObjectMapper)} —
 * the read helper that surfaces {@code tenant.config.contact.email} to the
 * {@code GET /api/v1/public/contact-info} response body and to
 * {@code ContactEmailController}'s audit {@code old_value} capture.
 *
 * <p>Mirrors {@link TenantDvPolicyHelperTest} in style (info-email-contact §4
 * warroom round 2 N1-Sam). The conservative-on-failure contract MUST be
 * pinned independently of integration tests because it shapes what the read
 * endpoint returns: a parse failure must NEVER surface partial / corrupt
 * data and must NEVER throw — it must resolve to "no override" so the
 * frontend falls back to the platform default.
 */
class TenantContactEmailHelperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("returns email value when contact.email is a valid textual node")
    void returnsValidEmail() {
        JsonString config = JsonString.of("{\"contact\":{\"email\":\"info@example.com\"}}");
        assertThat(Tenant.readContactEmail(config, objectMapper)).isEqualTo("info@example.com");
    }

    @Test
    @DisplayName("returns empty string when contact.email is an empty textual node (hypothetical state)")
    void returnsEmptyStringForEmptyTextualNode() {
        // Pins the helper's behavior for a HYPOTHETICAL state — not part of
        // the API-surface contract. The §3 PATCH endpoint never writes
        // contact.email = "" (empty-string PATCH causes TenantService
        // .setContactEmail to REMOVE the key entirely; cleared state has
        // no contact.email key at all). The only way an empty-string
        // textual node reaches the JSONB is via direct SQL or a future
        // code path that bypasses the PATCH endpoint.
        //
        // Pinning this case so a future contributor who decides "treat
        // empty string as null" has to update the test, which forces them
        // to articulate the change rather than silently shifting semantics.
        JsonString config = JsonString.of("{\"contact\":{\"email\":\"\"}}");
        assertThat(Tenant.readContactEmail(config, objectMapper)).isEqualTo("");
    }

    @Test
    @DisplayName("returns null when contact.email key is absent under contact")
    void returnsNullWhenEmailKeyAbsent() {
        JsonString config = JsonString.of("{\"contact\":{\"phone\":\"555-0100\"}}");
        assertThat(Tenant.readContactEmail(config, objectMapper)).isNull();
    }

    @Test
    @DisplayName("returns null when contact key is absent")
    void returnsNullWhenContactKeyAbsent() {
        JsonString config = JsonString.of("{\"hold_duration_minutes\":120}");
        assertThat(Tenant.readContactEmail(config, objectMapper)).isNull();
    }

    @Test
    @DisplayName("returns null on empty config object")
    void returnsNullOnEmptyConfig() {
        JsonString config = JsonString.empty();
        assertThat(Tenant.readContactEmail(config, objectMapper)).isNull();
    }

    @Test
    @DisplayName("returns null on null JsonString input")
    void returnsNullOnNullInput() {
        assertThat(Tenant.readContactEmail(null, objectMapper)).isNull();
    }

    @Test
    @DisplayName("returns null on blank value (whitespace-only)")
    void returnsNullOnBlankValue() {
        JsonString config = new JsonString("   ");
        assertThat(Tenant.readContactEmail(config, objectMapper)).isNull();
    }

    @Test
    @DisplayName("returns null on malformed JSON — does NOT throw")
    void returnsNullOnMalformedJsonAndDoesNotThrow() {
        // Critical: corrupt config must NOT propagate the JacksonException
        // out of the controller. The endpoint MUST stay 200 and return
        // null tenant.email so the frontend falls back to platform default.
        JsonString config = new JsonString("{not valid json");
        assertThat(Tenant.readContactEmail(config, objectMapper)).isNull();
    }

    @Test
    @DisplayName("returns null when contact is not an object (string)")
    void returnsNullWhenContactIsNotObject() {
        // A regression that allowed contact to be stored as a flat string
        // (instead of {email: "..."}) would otherwise expose the literal
        // string verbatim. Conservative read posture rejects non-object
        // shape and falls back to null.
        JsonString config = JsonString.of("{\"contact\":\"info@example.com\"}");
        assertThat(Tenant.readContactEmail(config, objectMapper)).isNull();
    }

    @Test
    @DisplayName("returns null when contact.email is non-textual (number)")
    void returnsNullWhenEmailIsNonTextual() {
        JsonString config = JsonString.of("{\"contact\":{\"email\":42}}");
        assertThat(Tenant.readContactEmail(config, objectMapper)).isNull();
    }

    @Test
    @DisplayName("preserves other top-level keys — read does not mutate")
    void coexistsWithOtherKeys() {
        // Sanity that the helper is a pure read — it parses but does not
        // modify the config. The real assertion is on the returned email;
        // the test name documents the intent.
        JsonString config = JsonString.of(
                "{\"hold_duration_minutes\":120,"
                        + "\"dv_policy_enabled\":false,"
                        + "\"contact\":{\"email\":\"info@example.com\"},"
                        + "\"default_locale\":\"en\"}");
        assertThat(Tenant.readContactEmail(config, objectMapper)).isEqualTo("info@example.com");
    }
}
