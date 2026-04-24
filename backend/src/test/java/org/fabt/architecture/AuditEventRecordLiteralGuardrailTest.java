package org.fabt.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice G-0 (issue #98) — audit-literal guardrail. Source-level lint that
 * prevents the pre-enum bare-string pattern from returning.
 *
 * <p><b>Why this test exists.</b> Slice G-0 migrated
 * {@link org.fabt.shared.audit.AuditEventRecord#action()} from {@code String}
 * to {@link org.fabt.shared.audit.AuditEventType}. The compile-driven
 * discovery surfaced 5 bare-string call sites that an initial grep had missed
 * ({@code CIPHERTEXT_V0_DECRYPT}, {@code CROSS_TENANT_CIPHERTEXT_REJECTED},
 * {@code CROSS_TENANT_JWT_REJECTED}, {@code DV_REFERRAL_ACCEPTED},
 * {@code DV_REFERRAL_REJECTED}). After migration, such sites would no longer
 * compile — but future contributors could reintroduce the pattern if a new
 * overload accepted {@code Object} or a helper took a raw {@code String}
 * and forwarded. This guardrail ensures the bare-string pattern stays out
 * of {@code src/main}.</p>
 *
 * <p><b>Form.</b> Regex scan for
 * {@code new AuditEventRecord(...)} constructor calls whose third positional
 * argument is a literal string of shape {@code "UPPER_SNAKE_CASE"}. A match
 * in production sources is a violation.</p>
 *
 * <p><b>Not ArchUnit.</b> ArchUnit inspects call-site types, not argument
 * values — a literal string argument is source-text pattern-matching, not
 * static class-graph reasoning. Sibling precedent: {@code NegativeCacheGuardrailTest}
 * (Phase C task 4.7).</p>
 *
 * <p><b>Path exclusions.</b> The enum's own Javadoc and this test's Javadoc
 * reference the forbidden pattern as a negative example. Excluded by
 * filename suffix.</p>
 */
@DisplayName("Slice G-0 — audit event record literal guardrail (source-scan)")
class AuditEventRecordLiteralGuardrailTest {

    /**
     * Matches {@code new AuditEventRecord(..., "UPPER_SNAKE", ...)} where
     * the 3rd positional argument is a bare UPPER_SNAKE_CASE string literal.
     *
     * <p>Multiline mode so the pattern spans formatted one-arg-per-line
     * constructor calls. Non-greedy between the arguments so we don't
     * overmatch into later constructor calls.
     */
    private static final Pattern BARE_STRING_AUDIT_ACTION = Pattern.compile(
            "new\\s+AuditEventRecord\\s*\\(\\s*[^,]+,\\s*[^,]+,\\s*\"[A-Z][A-Z0-9_]+\"",
            Pattern.DOTALL);

    /**
     * Path-excluded source files — these reference the forbidden pattern in
     * Javadoc (as a negative example) or in this test file itself. Match by
     * filename suffix for OS-path-separator neutrality. Sibling idiom to
     * {@code NegativeCacheGuardrailTest.EXCLUDED_FILENAMES}.
     */
    private static final Set<String> EXCLUDED_FILENAMES = Set.of(
            "AuditEventType.java",
            "AuditEventRecordLiteralGuardrailTest.java");

    private static final Path MAIN_SRC_ROOT = Paths.get("src", "main", "java", "org", "fabt");

    @Test
    @DisplayName("No production code constructs AuditEventRecord with a bare-string action literal")
    void noBareStringAuditActionLiterals() throws IOException {
        // Silent-empty-guard per feedback_never_skip_silently.md: confirm the
        // scan root exists + contains enough files before asserting the empty
        // violation set. A misconfigured cwd would pass vacuously.
        assertThat(MAIN_SRC_ROOT)
                .as("Source scan root must exist; if absent, cwd is misconfigured "
                        + "and this guardrail would silently pass without scanning anything")
                .isDirectory();

        List<Path> violations = new ArrayList<>();
        int filesScanned = 0;

        try (Stream<Path> paths = Files.walk(MAIN_SRC_ROOT)) {
            List<Path> javaFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !EXCLUDED_FILENAMES.contains(p.getFileName().toString()))
                    .toList();

            for (Path file : javaFiles) {
                filesScanned++;
                String content = Files.readString(file);
                Matcher m = BARE_STRING_AUDIT_ACTION.matcher(content);
                if (m.find()) {
                    violations.add(file);
                }
            }
        }

        // Floor: the scan must encounter a meaningful number of files. If the
        // cwd + exclusion list combined leave <50 files scanned, the
        // guardrail is probably misconfigured rather than legitimately clean.
        assertThat(filesScanned)
                .as("Floor check: scan produced %d files. A healthy scan across "
                        + "org.fabt.* should see hundreds. If <50, the cwd or "
                        + "exclusion list is wrong and this test is vacuous.",
                        filesScanned)
                .isGreaterThanOrEqualTo(50);

        assertThat(violations)
                .as("Production sources must not construct AuditEventRecord with a "
                        + "bare-string action literal. Use AuditEventType.<CASE> — "
                        + "the enum is typo-safe and its wire form is contract-pinned "
                        + "by AuditEventTypeTest. Migration tracked in issue #98 "
                        + "(Slice G-0 preflight for Phase G audit chain hashing). "
                        + "Violations: %s",
                        violations)
                .isEmpty();
    }
}
