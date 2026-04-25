package org.fabt.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the post-Phase-G-4 / issue-#141 role taxonomy:
 *
 * <ul>
 *   <li>Exactly five enum values</li>
 *   <li>{@link Role#COC_ADMIN}, {@link Role#COORDINATOR}, {@link Role#OUTREACH_WORKER},
 *       and {@link Role#PLATFORM_OPERATOR} are present and NOT deprecated</li>
 *   <li>{@link Role#PLATFORM_ADMIN} is present BUT carries
 *       {@code @Deprecated(forRemoval = true, since = "0.53.0")} — it survives
 *       the v0.53 deprecation window but is removed in the cleanup release</li>
 * </ul>
 *
 * <p>If a future change adds, removes, or re-names a role value, this test
 * fails — forcing the author to update both the enum and any out-of-band
 * documentation that pins the role taxonomy.
 */
class RoleEnumTest {

    @Test
    @DisplayName("exactly five role values exist")
    void exactlyFiveRoleValues() {
        assertThat(Role.values()).hasSize(5);
    }

    @Test
    @DisplayName("expected role names present")
    void expectedRoleNamesPresent() {
        Set<String> names = Set.of(
                "COC_ADMIN",
                "COORDINATOR",
                "OUTREACH_WORKER",
                "PLATFORM_OPERATOR",
                "PLATFORM_ADMIN");
        assertThat(Arrays.stream(Role.values()).map(Enum::name).toList())
                .containsExactlyInAnyOrderElementsOf(names);
    }

    @Test
    @DisplayName("PLATFORM_ADMIN is annotated @Deprecated(forRemoval=true, since=0.53.0)")
    void platformAdminIsDeprecatedForRemoval() throws NoSuchFieldException {
        Field platformAdmin = Role.class.getField("PLATFORM_ADMIN");
        Deprecated deprecated = platformAdmin.getAnnotation(Deprecated.class);
        assertThat(deprecated)
                .as("PLATFORM_ADMIN must carry @Deprecated to signal the role split (issue #141)")
                .isNotNull();
        assertThat(deprecated.forRemoval())
                .as("forRemoval=true signals the cleanup release will eliminate the value")
                .isTrue();
        assertThat(deprecated.since())
                .as("since=0.53.0 marks when deprecation began")
                .isEqualTo("0.53.0");
    }

    @Test
    @DisplayName("non-deprecated roles are NOT annotated @Deprecated")
    void nonDeprecatedRolesAreNotAnnotated() throws NoSuchFieldException {
        for (String name : Set.of("COC_ADMIN", "COORDINATOR", "OUTREACH_WORKER", "PLATFORM_OPERATOR")) {
            Field field = Role.class.getField(name);
            assertThat(field.getAnnotation(Deprecated.class))
                    .as("Role." + name + " must NOT be deprecated")
                    .isNull();
        }
    }
}
