package org.fabt.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * G-4.4 task §5.6 — ArchUnit guard preventing future re-introduction of
 * {@code @PreAuthorize("...PLATFORM_ADMIN...")} on controller methods.
 *
 * <p><b>Why this rule.</b> The G-4.4 endpoint migration replaced
 * {@code hasRole('PLATFORM_ADMIN')} with either {@code hasRole('COC_ADMIN')}
 * (tenant-scoped) or {@code hasRole('PLATFORM_OPERATOR')} +
 * {@code @PlatformAdminOnly} (platform-scoped). The {@code Role.PLATFORM_ADMIN}
 * enum value is {@code @Deprecated(forRemoval=true, since="0.53.0")} and
 * scheduled for removal in the cleanup release. A future PR that
 * re-introduces a {@code @PreAuthorize} expression mentioning
 * {@code PLATFORM_ADMIN} would silently re-attach the deprecated role to a
 * production endpoint — exactly the regression we want to prevent.
 *
 * <p><b>Scope.</b> Only checks methods in {@code org.fabt.*.api} packages
 * (controller layer). Comments / Javadoc / migration SQL files
 * legitimately mention PLATFORM_ADMIN and are excluded by the
 * import-option restriction. Test classes are also excluded — IT helpers
 * may construct historical-state scenarios that reference the deprecated
 * role.
 *
 * <p><b>Trigger to relax.</b> The cleanup release that removes
 * {@code Role.PLATFORM_ADMIN} entirely should ALSO remove this rule (or
 * at minimum re-purpose it as a literal-string scan, since the enum
 * itself will be gone and Java compilation enforces the absence).
 */
@DisplayName("G-4.4 §5.6 — no @PreAuthorize references PLATFORM_ADMIN")
class NoPlatformAdminPreauthorizeTest {

    private static final JavaClasses MAIN_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("org.fabt");

    @Test
    @DisplayName("no controller method's @PreAuthorize value contains PLATFORM_ADMIN")
    void noControllerPreauthorizeReferencesPlatformAdmin() {
        ArchRule rule = methods()
                .that().areDeclaredInClassesThat().resideInAPackage("..api..")
                .and().areAnnotatedWith(PreAuthorize.class)
                .should(notReferencePlatformAdmin())
                .as("@PreAuthorize on controller methods MUST NOT mention PLATFORM_ADMIN. "
                        + "Migrated to COC_ADMIN (tenant-scoped) or PLATFORM_OPERATOR + "
                        + "@PlatformAdminOnly (platform-scoped) per G-4.4. The deprecated "
                        + "Role.PLATFORM_ADMIN enum value will be removed in the cleanup release.")
                .allowEmptyShould(true);
        rule.check(MAIN_CLASSES);
    }

    private static com.tngtech.archunit.lang.ArchCondition<com.tngtech.archunit.core.domain.JavaMethod>
            notReferencePlatformAdmin() {
        return new com.tngtech.archunit.lang.ArchCondition<>(
                "not reference PLATFORM_ADMIN in @PreAuthorize value") {
            @Override
            public void check(com.tngtech.archunit.core.domain.JavaMethod method,
                              com.tngtech.archunit.lang.ConditionEvents events) {
                method.getAnnotations().stream()
                        .filter(byTypeName(PreAuthorize.class.getName()))
                        .forEach(a -> {
                            Object value = a.get("value").orElse(null);
                            if (value != null && value.toString().contains("PLATFORM_ADMIN")) {
                                events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(
                                        method,
                                        "Method " + method.getFullName()
                                                + " has @PreAuthorize(\"" + value
                                                + "\") which mentions PLATFORM_ADMIN — migrate to "
                                                + "COC_ADMIN or PLATFORM_OPERATOR per G-4.4."));
                            }
                        });
            }
        };
    }

    private static DescribedPredicate<JavaAnnotation<?>> byTypeName(String fqn) {
        return new DescribedPredicate<>("annotation type " + fqn) {
            @Override
            public boolean test(JavaAnnotation<?> a) {
                return a.getRawType().getName().equals(fqn);
            }
        };
    }
}
