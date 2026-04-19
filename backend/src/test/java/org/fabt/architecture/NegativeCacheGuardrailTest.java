package org.fabt.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase C task 4.7 — negative-cache guardrail. Source-level lint sibling of
 * Family C. NOT a fourth ArchUnit rule — ArchUnit inspects call-site types,
 * not runtime argument values, so it cannot cleanly detect literal {@code null}
 * or {@code Optional.empty()} as a method argument. A source-scan with regex
 * on {@code backend/src/main/java/org/fabt/} catches the literal-syntax cases;
 * the runtime {@code IllegalArgumentException} in
 * {@code TenantScopedCacheService.put} (shipped with task 4.1) catches the
 * variable-null cases.
 *
 * <h2>Per design-c D-C-5 + spec negative-cache-tenant-scoping</h2>
 *
 * FABT uses zero 404-cache call sites today (verified at Phase C kickoff).
 * This guardrail ensures it stays that way: the moment a developer writes
 * {@code cacheService.put(cacheName, key, null, ttl)} or
 * {@code cacheService.put(cacheName, key, Optional.empty(), ttl)} in
 * production code, this test fails with file:line. The correct pattern is
 * {@code TenantScopedCacheService.putNegative(cacheName, key, ttl)}, which
 * applies the tenant prefix + {@code :404:} marker + a typed sentinel
 * value so that cross-tenant negative-cache collisions are impossible by
 * construction.
 *
 * <h2>Path exclusions</h2>
 *
 * Two files literally reference the forbidden patterns in Javadoc (as
 * negative examples of what NOT to write). Excluded by path:
 * <ul>
 *   <li>{@code TenantScopedCacheService.java} — Javadoc at the wrapper's
 *       {@code put} method cross-references the Family C rule.</li>
 *   <li>{@code NegativeCacheGuardrailTest.java} — this file's own Javadoc.</li>
 * </ul>
 *
 * <p>Simpler than a comment-stripping state machine + matches the
 * {@code FamilyCArchitectureTest.EXEMPT_CLASSES} idiom (D-4.7-3 warroom
 * resolution).
 *
 * <h2>PENDING_MIGRATION_SITES variable-null audit</h2>
 *
 * The 10 allowlisted sites in {@code FamilyCArchitectureTest.PENDING_MIGRATION_SITES}
 * bypass Rule C1's annotation-requirement but remain subject to this
 * guardrail. Manual grep across those 10 methods on 2026-04-19 confirmed
 * none writes a variable-null: all pass concrete {@code Map<String, Object>}
 * or non-null collection values (`AnalyticsService.*`, `BedSearchService.doSearch`)
 * or only call {@code evict(...)} which has no value argument
 * (`AvailabilityService.createSnapshot`, `ShelterService.evictTenantShelterCaches`).
 * Re-verify before merging task 4.b in case any migrated site introduces a
 * new variable-null path.
 */
@DisplayName("Phase C task 4.7 — negative-cache guardrail (source-scan)")
class NegativeCacheGuardrailTest {

    /**
     * Matches {@code .put(something, something, null, ...)} where the 3rd
     * argument is a literal {@code null}. Allows whitespace + comments
     * within a single line. Multi-line {@code .put(\n...\n  null,\n...)}
     * patterns are not caught — Spotless-formatted one-arg-per-line style
     * would slip by; accepted cost (warroom D-4.7-Riley: low probability).
     */
    private static final Pattern NULL_VALUE_PUT = Pattern.compile(
            "\\.put\\s*\\(\\s*[^,]+,\\s*[^,]+,\\s*null\\s*,");

    /**
     * Matches {@code .put(something, something, Optional.empty(), ...)}.
     */
    private static final Pattern OPTIONAL_EMPTY_PUT = Pattern.compile(
            "\\.put\\s*\\(\\s*[^,]+,\\s*[^,]+,\\s*Optional\\s*\\.\\s*empty\\s*\\(\\s*\\)\\s*,");

    /**
     * Path-excluded source files — these reference the forbidden patterns
     * in Javadoc (as negative examples) or in this test file itself. Match
     * by filename suffix for OS-path-separator neutrality.
     */
    private static final Set<String> EXCLUDED_FILENAMES = Set.of(
            "TenantScopedCacheService.java",
            "NegativeCacheGuardrailTest.java");

    private static final Path MAIN_SRC_ROOT = Paths.get("src", "main", "java", "org", "fabt");

    @Test
    @DisplayName("No production code writes null or Optional.empty as a cache value")
    void noNullOrEmptyOptionalAsCacheValue() throws IOException {
        // Silent-empty-guard per feedback_never_skip_silently.md: confirm the
        // scan root actually exists before asserting violation-set. A misconfigured
        // cwd (e.g., test launched from repo root instead of backend/) would
        // otherwise return empty violations and pass vacuously — false confidence.
        assertThat(Files.isDirectory(MAIN_SRC_ROOT))
                .as("Guardrail must scan the real production tree, not vacuously pass. "
                        + "Expected " + MAIN_SRC_ROOT.toAbsolutePath()
                        + " to exist; run `mvn test` from backend/ directory.")
                .isTrue();

        ScanResult result = scanForViolations(MAIN_SRC_ROOT);

        // Sanity: the scan must traverse real files. org.fabt has 250+ .java
        // sources in production today; a floor of 50 is generous + catches any
        // future path-resolution regression without tracking exact file counts.
        assertThat(result.filesScanned())
                .as("Scan must actually traverse production .java files (found zero). "
                        + "Path resolution broke — check MAIN_SRC_ROOT against actual cwd.")
                .isGreaterThan(50);

        assertThat(result.violations())
                .as("Negative-cache guardrail (Phase C task 4.7, design-c D-C-5): "
                        + "production code must NOT write null or Optional.empty() as a "
                        + "cache value. Use TenantScopedCacheService.putNegative(cacheName, "
                        + "key, ttl) for tenant-scoped-by-construction 404 caching.")
                .isEmpty();
    }

    /**
     * Result of a source-tree scan: how many files were visited + what
     * violations were found. The {@code filesScanned} count exists so the
     * positive test can assert the scan actually traversed the tree (not
     * vacuously passed against a non-existent root).
     */
    record ScanResult(int filesScanned, List<String> violations) {}

    /**
     * Scan the provided source tree and return a {@link ScanResult} with
     * the file count + violation descriptions formatted
     * {@code <path>:<line>  <matched-text>}. Visible to negative-test
     * methods so they can assert the scan fires on a fixture sub-tree.
     */
    static ScanResult scanForViolations(Path root) throws IOException {
        List<String> violations = new ArrayList<>();
        int[] filesScanned = {0};
        if (!Files.isDirectory(root)) {
            return new ScanResult(0, violations);
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> !EXCLUDED_FILENAMES.contains(p.getFileName().toString()))
                    .forEach(p -> {
                        filesScanned[0]++;
                        scanFile(p, violations);
                    });
        }
        return new ScanResult(filesScanned[0], violations);
    }

    private static void scanFile(Path file, List<String> violations) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + file, e);
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (NULL_VALUE_PUT.matcher(line).find()) {
                violations.add(file + ":" + (i + 1) + "  " + line.trim()
                        + "   — null value in .put(...); use TenantScopedCacheService.putNegative");
            }
            if (OPTIONAL_EMPTY_PUT.matcher(line).find()) {
                violations.add(file + ":" + (i + 1) + "  " + line.trim()
                        + "   — Optional.empty() value in .put(...); use TenantScopedCacheService.putNegative");
            }
        }
    }

    // -------------------------------------------------------------------
    // Negative tests — assert the scan fires against the fixture package
    // -------------------------------------------------------------------

    private static final Path FIXTURES_ROOT = Paths.get(
            "src", "test", "java", "org", "fabt", "architecture", "fixtures", "cache");

    @Test
    @DisplayName("Negative: NullPutFixture (literal null value) triggers scan violation")
    void negative_nullPutFixtureTriggersViolation() throws IOException {
        ScanResult result = scanForViolations(FIXTURES_ROOT);
        assertThat(result.filesScanned())
                .as("Fixture tree must exist for negative tests to be meaningful")
                .isGreaterThan(0);
        assertThat(result.violations())
                .as("Scan must find NullPutFixture's intentional literal-null .put(...)")
                .anyMatch(v -> v.contains("NullPutFixture.java") && v.contains("null value"));
    }

    @Test
    @DisplayName("Negative: OptionalEmptyPutFixture (Optional.empty() value) triggers scan violation")
    void negative_optionalEmptyPutFixtureTriggersViolation() throws IOException {
        ScanResult result = scanForViolations(FIXTURES_ROOT);
        assertThat(result.filesScanned())
                .as("Fixture tree must exist for negative tests to be meaningful")
                .isGreaterThan(0);
        assertThat(result.violations())
                .as("Scan must find OptionalEmptyPutFixture's intentional Optional.empty() .put(...)")
                .anyMatch(v -> v.contains("OptionalEmptyPutFixture.java") && v.contains("Optional.empty()"));
    }
}
