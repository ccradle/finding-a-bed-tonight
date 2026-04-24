package org.fabt.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ArchUnit Family F — defends the F-6.0 crypto-shred property by pinning
 * the two privileged call-sites that, if misused, would reopen the gap
 * the warroom signed off on closing.
 *
 * <h2>Rule 7.8h — {@code deriveKekWrappingKey} caller pin</h2>
 *
 * {@link org.fabt.shared.security.KeyDerivationService#deriveKekWrappingKey}
 * returns the AES-KWP wrapping key that sits at the top of the shred
 * surface — anyone who can call this method and read a
 * {@code tenant_dek.wrapped_dek} row can recover the plaintext DEK.
 * Access is package-private; this rule additionally forbids any class
 * outside {@link org.fabt.shared.security.TenantDekService} from calling
 * it, preventing "I just needed a per-tenant key for my unrelated feature"
 * privilege drift.
 *
 * <h2>Rule 7.8j — {@code fabt.shred_in_progress} GUC caller pin</h2>
 *
 * The V82 {@code tenant_dek_shred_guard} trigger on {@code tenant_dek}
 * DELETEs gates on a session-local GUC named {@code fabt.shred_in_progress}.
 * Only {@link org.fabt.tenant.service.TenantLifecycleService#hardDelete}
 * and the {@code db.migration.V83__*} rotation-probe flip-back may set it.
 * Any other caller with the ability to write that GUC defeats the trigger
 * guard and re-enables ad-hoc {@code tenant_dek} DELETEs. ArchUnit cannot
 * match against SQL string contents inside a JDBC call, so this rule
 * scans production source files directly (same technique as
 * {@link MigrationLintTest}).
 */
@DisplayName("ArchUnit Family F — crypto-shred call-site pins (F-6.0 tasks 7.8h + 7.8j)")
class CryptoShredArchitectureTest {

    // ──────────────────────────────────────────────────────────────────
    // Rule 7.8h — deriveKekWrappingKey caller pin
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("7.8h — only TenantDekService may call KeyDerivationService.deriveKekWrappingKey")
    void deriveKekWrappingKey_onlyCallableFromTenantDekService() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.fabt..", "db.migration..");

        ArchRule rule = noClasses()
                .that().doNotHaveFullyQualifiedName(
                        "org.fabt.shared.security.TenantDekService")
                .should().callMethod(
                        "org.fabt.shared.security.KeyDerivationService",
                        "deriveKekWrappingKey",
                        "java.util.UUID")
                .because("the AES-KWP wrapping key is the privileged output "
                        + "of KeyDerivationService — anyone who can call this "
                        + "method and read a tenant_dek.wrapped_dek row can "
                        + "recover the plaintext DEK. TenantDekService is the "
                        + "SINGLE legitimate caller. If a new caller emerges "
                        + "(e.g., a Vault Transit adapter for the regulated "
                        + "tier), add it to this rule's doNotHaveFullyQualifiedName "
                        + "list AND document the warroom approval that allowed it.");

        rule.check(classes);
    }

    // ──────────────────────────────────────────────────────────────────
    // Rule 7.8j — fabt.shred_in_progress GUC caller pin (source scan)
    // ──────────────────────────────────────────────────────────────────

    private static final Path JAVA_MAIN =
            Paths.get("src", "main", "java");

    /**
     * Allowlist of fully-qualified class names that may reference the
     * {@code fabt.shred_in_progress} GUC. Additions require a warroom
     * citation + design-doc reference in the comment.
     *
     * <ul>
     *   <li>{@code TenantLifecycleService} — hardDelete binds the GUC
     *       before the CASCADE {@code DELETE FROM tenant} fires so the
     *       V82 trigger guard lets the tenant_dek CASCADE through. Sole
     *       production caller (design-f6-real-cryptoshred §5, Q-F6-6
     *       warroom pass-2).</li>
     *   <li>{@code V83__reencrypt_v1_envelopes_under_tenant_dek} — the
     *       V83 migration's rotation-readiness probe's flip-back step
     *       DELETEs the probe's gen=2 row, which fires the trigger.
     *       Legitimate: the migration IS the privileged context here.</li>
     * </ul>
     */
    private static final Set<String> SHRED_GUC_ALLOWLIST = Set.of(
            "TenantLifecycleService.java",
            "V83__reencrypt_v1_envelopes_under_tenant_dek.java"
    );

    /**
     * Pattern that matches a literal occurrence of the GUC name in
     * CODE (not comments). Comments get stripped first so a class
     * that merely documents the GUC in its javadoc (e.g.,
     * {@code TenantDekService}) isn't flagged — only actual writes
     * via {@code set_config('fabt.shred_in_progress', ...)} are.
     */
    private static final Pattern SHRED_GUC = Pattern.compile(
            "fabt\\.shred_in_progress");

    /** Line comments: {@code // ... EOL}. */
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
    /** Block + Javadoc comments: {@code /* ... *&#47;}. */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    @Test
    @DisplayName("7.8j — only TenantLifecycleService and V83 may reference fabt.shred_in_progress")
    void shredInProgressGuc_onlyReferencedByAllowlist() throws IOException {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(JAVA_MAIN)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> scanForShredGuc(p, violations));
        }

        assertThat(violations)
                .as("Only TenantLifecycleService.hardDelete and V83's rotation "
                    + "probe may write the fabt.shred_in_progress GUC. Any "
                    + "other caller defeats the V82 tenant_dek_shred_guard "
                    + "trigger. If a new legitimate caller emerges, add its "
                    + "file name to SHRED_GUC_ALLOWLIST with a warroom "
                    + "citation — do NOT just silence the rule.")
                .isEmpty();
    }

    private void scanForShredGuc(Path file, List<String> violations) {
        String name = file.getFileName().toString();
        if (SHRED_GUC_ALLOWLIST.contains(name)) {
            return;
        }
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            violations.add(file + " — could not read: " + e.getMessage());
            return;
        }
        // Strip comments before scanning — documenting the GUC in a
        // javadoc is not a violation, only actual code writes are.
        String stripped = BLOCK_COMMENT.matcher(content).replaceAll("");
        stripped = LINE_COMMENT.matcher(stripped).replaceAll("");

        Matcher m = SHRED_GUC.matcher(stripped);
        while (m.find()) {
            violations.add(name + " — references fabt.shred_in_progress in CODE "
                    + "(comments stripped before scan)");
        }
    }
}
