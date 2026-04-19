package org.fabt.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lint rules over Flyway migration files. Complements the ArchUnit rules
 * on Java code (see {@code TenantContextTransactionalRuleTest},
 * {@code TenantPredicateCoverageTest}) with similar enforcement for SQL.
 *
 * <p>Task 3.14 (v0.45 task #165): forbid {@code SECURITY DEFINER} on
 * functions created by Flyway migrations. Rationale:
 * {@code SECURITY DEFINER} functions execute with the privileges of the
 * FUNCTION OWNER, not the calling role. In FABT's Phase B regime the
 * owner is {@code fabt} (superuser-adjacent; BYPASSRLS) and the caller
 * is {@code fabt_app} (RLS-enforced). A {@code SECURITY DEFINER} function
 * owned by {@code fabt} would bypass every RLS policy on the seven
 * regulated tables — exactly the owner-bypass vector Phase B's FORCE RLS
 * (V69) is designed to block.
 *
 * <p>When a future migration legitimately needs {@code SECURITY DEFINER}
 * (e.g., a superuser-only operation like {@code CREATE EXTENSION}), add
 * the migration's filename to {@link #SECURITY_DEFINER_ALLOWLIST} with
 * a comment explaining the justification. The owner-security-model
 * trade-off must be documented in the warroom / design doc first.
 *
 * <p>This is a FABT-specific SQL lint; ArchUnit has no native support
 * for SQL AST traversal, so a file-scan test is the right tool.
 */
@DisplayName("Flyway migration lint — task 3.14 (SECURITY DEFINER ban)")
class MigrationLintTest {

    private static final Path SQL_MIGRATIONS =
            Paths.get("src", "main", "resources", "db", "migration");
    private static final Path JAVA_MIGRATIONS =
            Paths.get("src", "main", "java", "db", "migration");

    /**
     * Allowlist of migration filenames that may use {@code SECURITY DEFINER}.
     * Additions MUST be approved in a warroom review + reference a
     * design-doc section. Empty today — Phase B's Flyway migrations all
     * run under {@code fabt} owner for DDL but define functions without
     * the {@code SECURITY DEFINER} attribute.
     */
    private static final List<String> SECURITY_DEFINER_ALLOWLIST = List.of(
            // No entries as of v0.45.0. Add here with a comment:
            // "VNN__migration_name.sql  // warroom ref: ..."
    );

    private static final Pattern SECURITY_DEFINER = Pattern.compile(
            "\\bSECURITY\\s+DEFINER\\b", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("No Flyway migration uses SECURITY DEFINER (task 3.14)")
    void noMigrationUsesSecurityDefiner() throws IOException {
        List<String> violations = new ArrayList<>();
        collectViolations(SQL_MIGRATIONS, violations);
        collectViolations(JAVA_MIGRATIONS, violations);

        assertThat(violations)
                .as("SECURITY DEFINER functions bypass RLS on the owning role. "
                        + "Phase B FORCE RLS (V69) depends on every write path running as fabt_app, "
                        + "not the fabt owner — a SECURITY DEFINER function owned by fabt would "
                        + "reintroduce owner-bypass. If a new migration genuinely needs SECURITY "
                        + "DEFINER (e.g. wrapping CREATE EXTENSION), add its filename to "
                        + "SECURITY_DEFINER_ALLOWLIST with a warroom/design citation.")
                .isEmpty();
    }

    private void collectViolations(Path dir, List<String> violations) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("V")
                                && (name.endsWith(".sql") || name.endsWith(".java"));
                    })
                    .forEach(p -> scanFile(p, violations));
        }
    }

    private void scanFile(Path file, List<String> violations) {
        String name = file.getFileName().toString();
        if (SECURITY_DEFINER_ALLOWLIST.contains(name)) {
            return;
        }
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            violations.add(file + " — could not read: " + e.getMessage());
            return;
        }
        // Strip SQL line comments so a -- "avoid SECURITY DEFINER" comment
        // doesn't itself trigger the rule.
        String stripped = content.replaceAll("(?m)--.*$", "");
        // Strip block comments.
        stripped = stripped.replaceAll("(?s)/\\*.*?\\*/", "");

        Matcher m = SECURITY_DEFINER.matcher(stripped);
        while (m.find()) {
            violations.add(name + " — contains SECURITY DEFINER at offset " + m.start());
        }
    }
}
