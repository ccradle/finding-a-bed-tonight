package org.fabt.tenant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.fabt.shared.config.JsonString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link Tenant#isDvPolicyEnabled(JsonString, ObjectMapper)} — the
 * read helper that gates per-shelter {@code dv_shelter=true} writes (see
 * dv-policy-tenant-flag OpenSpec change).
 *
 * <p>Conservative-read invariant: any input that is null/blank, malformed,
 * missing the key, or non-boolean MUST return {@code false}. A {@code true}
 * return authorizes a security-relevant write; ambiguity defaults to deny.
 */
class TenantDvPolicyHelperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("returns true when dv_policy_enabled key is true literal")
    void returnsTrueOnTrueLiteral() {
        JsonString config = JsonString.of("{\"dv_policy_enabled\":true}");
        assertThat(Tenant.isDvPolicyEnabled(config, objectMapper)).isTrue();
    }

    @Test
    @DisplayName("returns false when dv_policy_enabled key is false literal")
    void returnsFalseOnFalseLiteral() {
        JsonString config = JsonString.of("{\"dv_policy_enabled\":false}");
        assertThat(Tenant.isDvPolicyEnabled(config, objectMapper)).isFalse();
    }

    @Test
    @DisplayName("returns false when key is absent")
    void returnsFalseWhenKeyAbsent() {
        JsonString config = JsonString.of("{\"hold_duration_minutes\":120}");
        assertThat(Tenant.isDvPolicyEnabled(config, objectMapper)).isFalse();
    }

    @Test
    @DisplayName("returns false on empty config object")
    void returnsFalseOnEmptyConfig() {
        JsonString config = JsonString.empty();
        assertThat(Tenant.isDvPolicyEnabled(config, objectMapper)).isFalse();
    }

    @Test
    @DisplayName("returns false on null JsonString input")
    void returnsFalseOnNull() {
        assertThat(Tenant.isDvPolicyEnabled(null, objectMapper)).isFalse();
    }

    @Test
    @DisplayName("returns false on blank value")
    void returnsFalseOnBlankValue() {
        JsonString config = new JsonString("   ");
        assertThat(Tenant.isDvPolicyEnabled(config, objectMapper)).isFalse();
    }

    @Test
    @DisplayName("returns false on malformed JSON — does not throw")
    void returnsFalseOnMalformedJson() {
        JsonString config = new JsonString("{not valid json");
        assertThat(Tenant.isDvPolicyEnabled(config, objectMapper)).isFalse();
    }

    @Test
    @DisplayName("returns false when value is non-boolean (string)")
    void returnsFalseOnNonBooleanString() {
        JsonString config = JsonString.of("{\"dv_policy_enabled\":\"true\"}");
        assertThat(Tenant.isDvPolicyEnabled(config, objectMapper)).isFalse();
    }

    @Test
    @DisplayName("returns false when value is non-boolean (number)")
    void returnsFalseOnNonBooleanNumber() {
        JsonString config = JsonString.of("{\"dv_policy_enabled\":1}");
        assertThat(Tenant.isDvPolicyEnabled(config, objectMapper)).isFalse();
    }

    @Test
    @DisplayName("preserves other config keys — read does not mutate")
    void coexistsWithOtherKeys() {
        JsonString config = JsonString.of(
                "{\"hold_duration_minutes\":120,\"dv_policy_enabled\":true,\"default_locale\":\"en\"}");
        assertThat(Tenant.isDvPolicyEnabled(config, objectMapper)).isTrue();
    }
}
