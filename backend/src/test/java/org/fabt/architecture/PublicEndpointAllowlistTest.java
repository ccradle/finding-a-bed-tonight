package org.fabt.architecture;

import java.util.Set;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * info-email-contact §4 warroom round 1 M3-Alex — ArchUnit guard preventing
 * future endpoints from quietly landing under {@code /api/v1/public/**}
 * without warroom approval.
 *
 * <p><b>Why this rule.</b> SecurityConfig declares
 * {@code requestMatchers(GET, "/api/v1/public/**").permitAll()} (added with
 * §4 to support the public contact-info endpoint). The wildcard establishes
 * a convention — any future {@code @RestController @RequestMapping("/api/v1/public/...")}
 * class is automatically anonymous-accessible. That convention is
 * intentional but needs a guard so a future PR cannot drop a new public
 * surface in by accident.
 *
 * <p><b>How it works.</b> The allowlist below enumerates every
 * controller class permitted to declare a {@code /api/v1/public/...}
 * mapping. Adding a new public endpoint requires (a) a code change here
 * adding the class to the allowlist AND (b) a paired warroom review
 * documented in the OpenSpec change. The two-place edit forces the
 * decision into the review surface.
 *
 * <p><b>Trigger to relax.</b> If the public namespace ever needs to be
 * tightened (e.g. specific paths instead of a wildcard), this guard can
 * be retired in the same change that narrows the SecurityConfig matcher.
 */
@DisplayName("info-email-contact §4 — /api/v1/public/** controllers are allowlisted")
class PublicEndpointAllowlistTest {

    /**
     * Fully-qualified class names of controllers permitted to declare a
     * {@code @RequestMapping} value starting with {@code /api/v1/public}.
     * Every entry corresponds to a controller surface reviewed in a
     * warroom and approved as anonymous-accessible.
     */
    private static final Set<String> ALLOWED_PUBLIC_CONTROLLERS = Set.of(
            "org.fabt.tenant.api.ContactInfoController" // info-email-contact §4
    );

    private static final JavaClasses MAIN_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("org.fabt");

    @Test
    @DisplayName("every /api/v1/public/** controller class is in the allowlist")
    void publicControllersMustBeAllowlisted() {
        ArchRuleDefinition.classes()
                .that().areAnnotatedWith(RequestMapping.class)
                .should(beAllowlistedIfPublic())
                .as("Any class declaring @RequestMapping(\"/api/v1/public/...\") MUST be "
                        + "explicitly allowlisted in PublicEndpointAllowlistTest.ALLOWED_PUBLIC_CONTROLLERS. "
                        + "Adding a new entry requires warroom approval — a public endpoint is anonymous-"
                        + "accessible by default via SecurityConfig's /api/v1/public/** permitAll matcher.")
                .allowEmptyShould(true)
                .check(MAIN_CLASSES);
    }

    private static ArchCondition<com.tngtech.archunit.core.domain.JavaClass> beAllowlistedIfPublic() {
        return new ArchCondition<>("be allowlisted if it maps under /api/v1/public") {
            @Override
            public void check(com.tngtech.archunit.core.domain.JavaClass clazz, ConditionEvents events) {
                // Canonical pattern from NoPlatformAdminPreauthorizeTest:92 —
                // getRawType().getName() returns the FQN string of the
                // annotation's class.
                clazz.getAnnotations().stream()
                        .filter(a -> a.getRawType().getName().equals(RequestMapping.class.getName()))
                        .forEach(annotation -> {
                            Object rawValue = annotation.get("value").orElse(null);
                            if (rawValue == null) return;
                            String[] paths = (String[]) rawValue;
                            for (String path : paths) {
                                if (path != null && path.startsWith("/api/v1/public")) {
                                    if (!ALLOWED_PUBLIC_CONTROLLERS.contains(clazz.getFullName())) {
                                        events.add(SimpleConditionEvent.violated(
                                                clazz,
                                                "Class " + clazz.getFullName()
                                                        + " maps under " + path
                                                        + " but is NOT in PublicEndpointAllowlistTest"
                                                        + ".ALLOWED_PUBLIC_CONTROLLERS — public endpoints "
                                                        + "require warroom approval before landing."));
                                    }
                                    return; // one violation per class is enough
                                }
                            }
                        });
            }
        };
    }
}
