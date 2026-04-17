package org.fabt.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit Family A guard for {@code MasterKekProvider.getMasterKekBytes()}.
 * Per A3 D17 + warroom E1: only callers inside the
 * {@code org.fabt.shared.security} package may access the raw master KEK
 * bytes. Any other-package caller is a foot-gun (accidental serializer
 * leaking the master KEK to a log or HTTP response). Build fails on
 * violation.
 *
 * <p>{@link org.fabt.shared.security.KeyDerivationService} is the only
 * legitimate caller today (HKDF-Extract needs raw bytes). Future callers
 * inside the same package — e.g., a Vault Transit adapter for the
 * regulated tier — are also permitted.
 */
@DisplayName("ArchUnit Family A — MasterKekProvider visibility (A3 E1)")
class MasterKekProviderArchitectureTest {

    private static final String SECURITY_PACKAGE = "org.fabt.shared.security";

    @Test
    @DisplayName("getMasterKekBytes() callable only from org.fabt.shared.security")
    void getMasterKekBytes_isPackagePrivate() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.fabt..");

        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage(SECURITY_PACKAGE)
                .should().callMethod(
                        "org.fabt.shared.security.MasterKekProvider",
                        "getMasterKekBytes")
                .because("the master KEK bytes must never escape the "
                        + SECURITY_PACKAGE + " package — accidental "
                        + "serialization to a logger or HTTP response would "
                        + "expose the platform encryption key. Use "
                        + "MasterKekProvider.getPlatformKey() (returns a SecretKey, "
                        + "not raw bytes) instead.");

        rule.check(classes);
    }
}
