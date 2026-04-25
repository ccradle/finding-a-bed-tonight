package org.fabt.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.elements.MethodsShouldConjunction;
import org.fabt.auth.platform.PlatformAdminOnly;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * ArchUnit guards on the {@link PlatformAdminOnly} annotation (Phase G-4.3).
 *
 * <p>Two invariants pinned at build time:
 * <ol>
 *   <li><b>(existing G-4.3 §4.8)</b> Every method tagged
 *       {@code @PlatformAdminOnly} MUST also be tagged
 *       {@code @PreAuthorize("hasRole('PLATFORM_OPERATOR')")} or an
 *       equivalent expression containing {@code PLATFORM_OPERATOR} —
 *       defense-in-depth so the audit aspect can NEVER fire for an
 *       unauthenticated / wrong-role caller.</li>
 *   <li><b>(R4 from G-4.3 design warroom)</b> Every method tagged
 *       {@code @PlatformAdminOnly} MUST be in a {@code ..api..} package —
 *       the audit aspect is a controller-layer concern. The lockout
 *       direct-write hook in {@code PlatformAuthService} is the
 *       documented exception (it does NOT use the annotation).</li>
 * </ol>
 *
 * <p>Scoped to {@code .java} source files only via the
 * {@code DO_NOT_INCLUDE_TESTS} import option — SQL migration files
 * legitimately mention {@code PLATFORM_ADMIN} (e.g. V87 backfill UPDATE)
 * and would otherwise trip the rule.
 */
@DisplayName("@PlatformAdminOnly invariants (G-4.3)")
class PlatformAdminOnlyArchitectureTest {

    private static final JavaClasses MAIN_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("org.fabt");

    @Test
    @DisplayName("every @PlatformAdminOnly method also carries @PreAuthorize for PLATFORM_OPERATOR")
    void platformAdminOnlyRequiresPreAuthorize() {
        ArchRule rule = ((MethodsShouldConjunction)
                methods()
                        .that().areAnnotatedWith(PlatformAdminOnly.class)
                        .should().beAnnotatedWith(PreAuthorize.class))
                .as("Defense-in-depth: a @PlatformAdminOnly method must also "
                        + "carry @PreAuthorize so Spring Security rejects "
                        + "unauthenticated / wrong-role callers BEFORE the audit "
                        + "aspect fires. A method with @PlatformAdminOnly but no "
                        + "@PreAuthorize would let the audit row commit for any "
                        + "caller — wrong attribution at minimum, security hole "
                        + "if @PlatformAdminOnly is the only authorization layer.")
                .allowEmptyShould(true);
        rule.check(MAIN_CLASSES);
    }

    @Test
    @DisplayName("every @PlatformAdminOnly method resides in a ..api.. package")
    void platformAdminOnlyMustBeInApiPackage() {
        ArchRule rule = methods()
                .that().areAnnotatedWith(PlatformAdminOnly.class)
                .should().beDeclaredInClassesThat().resideInAPackage("..api..")
                .as("@PlatformAdminOnly is a controller-layer concern. "
                        + "Service-layer audit annotations are out of scope for "
                        + "the aspect (the lockout direct-write in "
                        + "PlatformAuthService is the documented exception — it "
                        + "uses logLockout, not the annotation).")
                .allowEmptyShould(true);
        rule.check(MAIN_CLASSES);
    }
}
