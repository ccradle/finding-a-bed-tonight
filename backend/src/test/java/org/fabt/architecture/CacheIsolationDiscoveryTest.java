package org.fabt.architecture;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

import org.fabt.BaseIntegrationTest;
import org.fabt.shared.cache.CacheNames;
import org.fabt.shared.cache.TenantScopedCacheService;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase C task 4.6 — automated discovery of every cache name in the FABT
 * inventory plus a legitimate-isolation round-trip per cache.
 *
 * <h2>What this test exists to catch</h2>
 *
 * Task 4.b drained {@code PENDING_MIGRATION_SITES} by hand-migrating 9
 * call sites. The next regression is NOT another forgotten migration — it
 * is a <em>new</em> cache name added to {@link CacheNames} without
 * wrapper wiring OR without a matching isolation contract. That regression
 * would slip past {@code FamilyCArchitectureTest}'s rules (which check
 * call-site annotations, not per-cache-name isolation) and past the
 * hand-written matrices in {@code Task4bCacheHitRateTest} and
 * {@code Tenant4bMigrationCrossTenantAttackTest} (which enumerate 7 and
 * 8 specific cache names respectively).
 *
 * <p>This test discovers every {@code CacheNames} constant via reflection
 * and, for each, asserts the legitimate-isolation triple:
 * <ol>
 *   <li>{@code tenantA.put(k, v)}</li>
 *   <li>{@code tenantA.get(k)} → HIT (precondition — wrapper actually stored it)</li>
 *   <li>{@code tenantB.get(k)} → MISS (isolation — tenantB's prefix slot is empty)</li>
 * </ol>
 *
 * <p>A new {@code CacheNames} constant added without a matching
 * wrapper-write path will pass row-3 (empty MISS on both sides) but fail
 * row-2 (nothing stored) — the test surfaces the gap loudly.
 *
 * <h2>Why "reflection" = ArchUnit, not {@code org.reflections}</h2>
 *
 * The task spec says "reflection-driven discovery" to mean "automated
 * discovery that adapts to future additions." Java's
 * {@code java.lang.reflect.*} API covers field/method/annotation
 * discovery but not bytecode-level call-site graphs. ArchUnit's
 * {@code JavaMethodCall} primitives do that — and the project already
 * depends on ArchUnit (see {@code FamilyCArchitectureTest}). This test
 * uses plain {@code Class.getFields()} for {@code CacheNames} constant
 * discovery (sufficient — it's field discovery, not call-site) and
 * ArchUnit for the zero-{@code @Cacheable} companion assertion (the
 * positive-discovery mirror of Family C rule C2).
 *
 * <h2>Silent-empty guard</h2>
 *
 * Per {@code feedback_never_skip_silently.md} + design-c D-C-7: a
 * classloader misconfiguration can cause discovery to silently return
 * an empty set, which a naive "iterate and assert each" test passes
 * vacuously. The {@link #discoverySatisfiesFloor()} test asserts a
 * minimum count (8) below which we are confident the discovery itself
 * broke. The "exactly the expected count" pin is at 11 (the
 * {@code CacheNames} inventory at Phase C kickoff); the floor is 8 so
 * a partial-discovery failure mode is loud without being brittle to
 * legitimate future additions.
 *
 * <h2>Warroom authority</h2>
 *
 * Plan warroom 2026-04-19 night (Alex + Marcus + Sam + Riley + Jordan +
 * Casey + Elena): 5 GREEN / 2 YELLOW verdicts; implementation picks —
 * ArchUnit over {@code org.reflections}, count = {@code CacheNames}
 * constants (not caller methods — legitimate refactors would churn that
 * number), {@code @ParameterizedTest + @MethodSource} over
 * {@code @TestFactory} (which silent-passes on empty iterables).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Phase C task 4.6 — cache-isolation discovery across every CacheNames constant")
class CacheIsolationDiscoveryTest extends BaseIntegrationTest {

    /**
     * Silent-empty floor per design-c D-C-7. Below this the discovery
     * itself is suspect — 11 constants in {@link CacheNames} today,
     * 8 floor accommodates a legitimate removal-of-one while still
     * detecting a half-scan classloader misconfig.
     */
    private static final int DISCOVERY_FLOOR = 8;

    /**
     * Exact count at Phase C kickoff (post-4.b). Any change — add or
     * remove — requires bumping this pin AND updating per-cache tests
     * in {@code Task4bCacheHitRateTest} +
     * {@code Tenant4bMigrationCrossTenantAttackTest}. Exact equality
     * (not {@code >=}) is deliberate: the JavaDoc on
     * {@link #discoveryMatchesExpectedMinimum} promises the pin fires as
     * a reminder on additions; a {@code >=} assertion silently accepts
     * new constants and defeats that promise.
     */
    private static final int EXPECTED_SITES = 11;

    private static final UUID TENANT_A = UUID.fromString("4c000000-a000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("4c000000-b000-0000-0000-000000000002");

    private static List<String> discoveredCacheNames;

    @Autowired
    private TenantScopedCacheService wrapper;

    @BeforeAll
    void discoverCacheNames() throws IllegalAccessException {
        List<String> names = new ArrayList<>();
        for (Field f : CacheNames.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())
                    && Modifier.isPublic(f.getModifiers())
                    && String.class.equals(f.getType())) {
                names.add((String) f.get(null));
            }
        }
        discoveredCacheNames = List.copyOf(names);
    }

    static Stream<String> cacheNameRows() {
        // Belt-and-suspenders: if @BeforeAll hasn't run (classloader skew) or
        // the list is empty, JUnit throws PreconditionViolationException when
        // @MethodSource returns an empty stream — loud failure. The explicit
        // guard test below catches partial-discovery (e.g. half the fields
        // loaded).
        return discoveredCacheNames == null ? Stream.empty() : discoveredCacheNames.stream();
    }

    @Test
    @DisplayName("Discovery floor — at least 8 CacheNames constants found (silent-empty guard)")
    void discoverySatisfiesFloor() {
        assertThat(discoveredCacheNames)
                .as("CacheNames reflection discovery must find at least %d constants. "
                        + "Fewer means either (a) the reflection mechanism broke "
                        + "(classloader misconfig, Reflections library skew) OR "
                        + "(b) someone removed half the cache-name inventory without "
                        + "updating DISCOVERY_FLOOR. Either way, fail loud — "
                        + "feedback_never_skip_silently.md.",
                        DISCOVERY_FLOOR)
                .hasSizeGreaterThanOrEqualTo(DISCOVERY_FLOOR);
    }

    @Test
    @DisplayName("Expected-count pin — CacheNames inventory matches Phase C kickoff exactly")
    void discoveryMatchesExpectedCount() {
        // Exact equality, NOT >=, per Riley post-commit warroom. The pin
        // fires BOTH directions: a reduction needs design-c doc update; an
        // addition requires bumping EXPECTED_SITES + adding per-cache tests
        // to Task4bCacheHitRateTest + Tenant4bMigrationCrossTenantAttackTest.
        // A >= assertion would silently accept new constants without the
        // reminder, defeating the promise in the class-level Javadoc.
        assertThat(discoveredCacheNames)
                .as("CacheNames inventory must match Phase C kickoff pin of %d "
                        + "exactly. If the count changed: (a) verify intent with "
                        + "the team, (b) update EXPECTED_SITES to the new value, "
                        + "(c) add a matching row to Task4bCacheHitRateTest and "
                        + "Tenant4bMigrationCrossTenantAttackTest for each new "
                        + "constant (or remove rows for removed constants).",
                        EXPECTED_SITES)
                .hasSize(EXPECTED_SITES);
    }

    @Test
    @DisplayName("Positive mirror of C2 — zero @Cacheable methods in org.fabt")
    void zeroSpringCacheableMethodsInOrgFabt() {
        // Positive-discovery companion to FamilyCArchitectureTest rule C2
        // (which is a `noMethods().should().beAnnotatedWith(...)` block — a
        // negative rule). This runs the affirmative scan: actively enumerate
        // any @Cacheable method and count them. Zero today; the C2 rule
        // keeps it at zero, and this test pins the count from the positive
        // side so an ArchUnit rule-regression (e.g., scope package typo)
        // doesn't silently re-admit @Cacheable.
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.fabt");

        // If import returned empty, something is badly wrong.
        assertThat(classes.size())
                .as("ArchUnit import of org.fabt returned zero classes — "
                        + "classpath / classloader misconfig.")
                .isGreaterThan(50);

        ArchRuleDefinition.noMethods()
                .that().areDeclaredInClassesThat().resideInAPackage("org.fabt..")
                .should().beAnnotatedWith("org.springframework.cache.annotation.Cacheable")
                .because("Family C rule C2 forbids Spring @Cacheable; this test "
                        + "is the positive-discovery mirror so a rule-scope typo "
                        + "(e.g., resideInAPackage with a bad glob) cannot silently "
                        + "re-admit @Cacheable without tripping a second test.")
                .check(classes);
    }

    @ParameterizedTest(name = "Cache isolation on {0}: A writes → A hits → B misses")
    @MethodSource("cacheNameRows")
    void cacheIsolationRoundTrip(String cacheName) {
        // Per-cache isolation contract:
        //   1. Tenant A put
        //   2. Tenant A get → HIT (precondition — wrapper actually stored it)
        //   3. Tenant B get (same logical key) → MISS (isolation — B's prefix slot empty)
        //
        // A new CacheNames constant added without wrapper-compatible behaviour
        // fails step 2 (empty Optional when the test expected .isPresent()).
        // A wrapper isolation regression fails step 3 (B unexpectedly sees A's
        // entry).
        String key = "isolation-probe-" + cacheName;
        String payload = "probe-value-for-" + cacheName;

        // Step 1 + 2: tenant A round-trip.
        TenantContext.runWithContext(TENANT_A, true, () -> {
            wrapper.put(cacheName, key, payload, Duration.ofSeconds(60));

            var hit = wrapper.get(cacheName, key, String.class);
            assertThat(hit)
                    .as("%s: tenant A's own read after put must be a HIT. "
                            + "Miss = wrapper key-stability regression (put-key vs "
                            + "get-key drift) OR wrapper short-circuit that skipped "
                            + "the put.", cacheName)
                    .isPresent()
                    .hasValue(payload);
        });

        // Step 3: tenant B under same logical key.
        TenantContext.runWithContext(TENANT_B, true, () -> {
            var miss = wrapper.get(cacheName, key, String.class);
            assertThat(miss)
                    .as("%s: tenant B reading the SAME logical key must be a MISS. "
                            + "Hit = cross-tenant leak — wrapper's prefix is no longer "
                            + "per-tenant. Load-bearing 4.b isolation invariant.",
                            cacheName)
                    .isEmpty();
        });

        // Cleanup — evict tenant A's entry so parametrized rows don't interfere.
        TenantContext.runWithContext(TENANT_A, true, () ->
                wrapper.evict(cacheName, key));
    }
}
