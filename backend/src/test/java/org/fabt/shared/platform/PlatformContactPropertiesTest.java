package org.fabt.shared.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for the canonical-constructor behavior of
 * {@link PlatformContactProperties}. Spring's {@code @ConfigurationProperties}
 * binding with the {@code ${FABT_PLATFORM_CONTACT_EMAIL:}} default in
 * application.yml is well-covered by Spring itself; this test pins only
 * the record's local null-handling contract so a future refactor can't
 * silently change the meaning of "unset" from empty-string to null.
 */
@DisplayName("PlatformContactProperties — canonical constructor null-handling")
class PlatformContactPropertiesTest {

    @Test
    @DisplayName("null contactEmail normalizes to empty string")
    void nullNormalizesToEmpty() {
        PlatformContactProperties props = new PlatformContactProperties(null);
        assertThat(props.contactEmail()).isEqualTo("");
    }

    @Test
    @DisplayName("empty-string contactEmail is preserved")
    void emptyPreserved() {
        PlatformContactProperties props = new PlatformContactProperties("");
        assertThat(props.contactEmail()).isEqualTo("");
    }

    @Test
    @DisplayName("non-empty contactEmail is preserved verbatim (no trim, no normalization)")
    void nonEmptyPreserved() {
        // The canonical constructor MUST NOT trim or otherwise normalize the
        // value — the validation pipeline runs at the controller boundary
        // (typed DTO with @Email + @Size), not at the property level.
        PlatformContactProperties props = new PlatformContactProperties("info@example.org");
        assertThat(props.contactEmail()).isEqualTo("info@example.org");
    }
}
