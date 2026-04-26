package org.fabt.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Phase G-4.4 §5.13 prereq (warroom Pre-spec 2 Marcus HIGH) — every
 * controller whose name starts with {@code Test} (test-only fixture
 * controllers) MUST carry a {@code @Profile} annotation that excludes
 * production profiles. The fixture pattern is to gate on
 * {@code @Profile("dev | test")} so Spring never instantiates the bean
 * outside dev/test contexts.
 *
 * <p><b>Why this rule.</b> Test-only controllers expose mutation
 * surface area (test reset, account unlock, snapshot backdate) that
 * MUST never be reachable in production. Profile gating is the
 * security boundary — there is no auth check on the endpoint itself
 * because the bean does not exist outside dev/test. A missing or
 * mistyped {@code @Profile} annotation (e.g. {@code @Profile("default")}
 * or no annotation) would leave the endpoint hot in production. This
 * rule pins the contract.
 *
 * <p><b>Triggered by.</b> Adding a new {@code Test*Controller} class
 * without a profile gate, or adding a profile gate whose value omits
 * the dev/test discriminator. The error message tells you what to add.
 *
 * <p><b>Pattern reference.</b> See:
 * <ul>
 *   <li>{@code TestResetController} (shared.api) — {@code @Profile("dev | test")}</li>
 *   <li>{@code TestDataController} (availability.api) — {@code @Profile("test")}</li>
 *   <li>{@code TestPlatformUnlockController} (auth.platform.api) — {@code @Profile("dev | test")}</li>
 * </ul>
 */
@DisplayName("Test-only controllers must carry a non-prod @Profile gate")
class TestControllerProfileGuardTest {

    private static final JavaClasses MAIN_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("org.fabt");

    @Test
    @DisplayName("every Test*Controller has @Profile that includes 'dev' or 'test'")
    void testControllersAreProfileGated() {
        ArchRule rule = classes()
                .that().haveSimpleNameStartingWith("Test")
                .and().haveSimpleNameEndingWith("Controller")
                .should(carryDevOrTestProfileGate())
                .as("Every test-only controller (class name starting with 'Test' and "
                        + "ending with 'Controller') MUST carry a @Profile annotation "
                        + "whose expression includes 'dev' or 'test'. The profile gate "
                        + "is the security boundary that keeps test-only mutation "
                        + "surface area out of production. See TestResetController "
                        + "+ TestDataController for the canonical patterns.")
                .allowEmptyShould(true);
        rule.check(MAIN_CLASSES);
    }

    private static ArchCondition<com.tngtech.archunit.core.domain.JavaClass>
            carryDevOrTestProfileGate() {
        return new ArchCondition<>(
                "carry @Profile(\"...\") expression containing 'dev' or 'test'") {
            @Override
            public void check(com.tngtech.archunit.core.domain.JavaClass clazz,
                              ConditionEvents events) {
                if (!clazz.isAnnotatedWith(Profile.class)) {
                    events.add(SimpleConditionEvent.violated(
                            clazz,
                            "Class " + clazz.getName() + " is a Test*Controller but "
                                    + "carries NO @Profile annotation — it would be active "
                                    + "in every Spring profile including prod. Add "
                                    + "@Profile(\"dev | test\")."));
                    return;
                }
                Profile profile = clazz.getAnnotationOfType(Profile.class);
                String[] values = profile.value();
                if (values == null || values.length == 0) {
                    events.add(SimpleConditionEvent.violated(
                            clazz,
                            "Class " + clazz.getName() + " has @Profile() with empty value."));
                    return;
                }
                boolean ok = false;
                for (String expr : values) {
                    if (expr == null) continue;
                    String lower = expr.toLowerCase();
                    if (lower.contains("dev") || lower.contains("test")) {
                        ok = true;
                        break;
                    }
                }
                if (!ok) {
                    events.add(SimpleConditionEvent.violated(
                            clazz,
                            "Class " + clazz.getName() + " has @Profile but its expression "
                                    + "does NOT include 'dev' or 'test'. Found: "
                                    + java.util.Arrays.toString(values)
                                    + ". Test-only controllers MUST gate on dev or test profile "
                                    + "so they are never instantiated in production."));
                }
            }
        };
    }
}
