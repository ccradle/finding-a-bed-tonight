package org.fabt.architecture;

import com.github.benmanes.caffeine.cache.Cache;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.fabt.shared.security.TenantScopedByConstruction;
import org.fabt.shared.security.TenantUnscopedCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * ArchUnit Family C — cache-isolation guard. Introduced by Phase C tasks 4.2
 * + 4.3 (basic rule + extended scope to all application-layer Caffeine
 * fields). Per design-c D-C-3 + D-C-4 + D-4.23 warroom resolutions.
 *
 * <h2>Three rules</h2>
 *
 * <ol>
 *   <li><b>C1 — Raw CacheService call-site guard.</b> Direct calls to
 *       {@code CacheService.get/put/evict/evictAll/evictAllByPrefix} or
 *       {@code TieredCacheService.get/put/evict/evictAll/evictAllByPrefix}
 *       from classes in {@code *.service}, {@code *.api}, {@code *.security},
 *       {@code *.auth.*} packages MUST originate from a method annotated
 *       {@link TenantUnscopedCache} OR {@link TenantScopedByConstruction},
 *       OR from the {@code TenantScopedCacheService} wrapper itself (exempt).
 *       Routes all tenant-bearing cache traffic through the wrapper.</li>
 *   <li><b>C2 — Spring cache-abstraction blocked outright.</b>
 *       {@code @Cacheable}, {@code @CacheEvict}, {@code @CachePut} forbidden
 *       in all {@code org.fabt} application classes. FABT uses zero today
 *       (verified at Phase C kickoff); blocking them proactively prevents a
 *       parallel caching pattern with its own CacheManager + CacheResolver
 *       seams that would need a separate tenant-scoping story.</li>
 *   <li><b>C3 — Caffeine field-type guard.</b> Every field typed
 *       {@code com.github.benmanes.caffeine.cache.Cache} declared in classes
 *       residing in {@code *.service}, {@code *.api}, {@code *.security},
 *       {@code *.auth.*} MUST carry {@link TenantUnscopedCache} with a
 *       non-empty justification OR {@link TenantScopedByConstruction} with
 *       a non-empty justification. Exempt classes: {@code CaffeineCacheService}
 *       and {@code TieredCacheService} (internal to the wrapper).</li>
 * </ol>
 *
 * <h2>Rule C4 (deferred to task 4.6)</h2>
 *
 * The warroom flagged a gap: a developer who wants to skip Caffeine entirely
 * and use {@code ConcurrentHashMap}/{@code Map}/{@code Set} as a cache could
 * dodge Rule C3. Per D-4.23-4 that guard is deferred to task 4.6 as a
 * separate heuristic rule (field name matches {@code .*[Cc]ache.*} or
 * {@code .*[Bb]ucket.*} + typed {@code Map}/{@code Set}/{@code ConcurrentHashMap}
 * in the scope packages). Not in this PR.
 *
 * <h2>Scope packages</h2>
 *
 * {@code *.service}, {@code *.api}, {@code *.security}, {@code *.auth.*}
 * per design-c D-C-3. Expansion beyond the original spec's {@code *.service}
 * captured 7 additional Caffeine fields outside that package (the full
 * 10-field inventory + Phase C task 4.4's policyByTenantAndId = 11).
 *
 * @see TenantUnscopedCache
 * @see TenantScopedByConstruction
 */
@DisplayName("ArchUnit Family C — cache-isolation guard")
class FamilyCArchitectureTest {

    private static final String CACHE_SERVICE_CLASS =
            "org.fabt.shared.cache.CacheService";
    private static final String TIERED_CACHE_SERVICE_CLASS =
            "org.fabt.shared.cache.TieredCacheService";
    private static final Set<String> CACHE_SERVICE_METHODS = Set.of(
            "get", "put", "evict", "evictAll", "evictAllByPrefix");

    /**
     * Classes exempt from Rules C1 and C3 — wrapper implementations that
     * legitimately access the cache abstraction directly.
     */
    private static final Set<String> EXEMPT_CLASSES = Set.of(
            "org.fabt.shared.cache.TenantScopedCacheService",
            "org.fabt.shared.cache.CaffeineCacheService",
            "org.fabt.shared.cache.TieredCacheService");

    /**
     * <b>Pending-migration allowlist for Rule C1.</b> Each entry is the
     * fully-qualified {@code <package>.<ClassName>.<methodName>} of a
     * pre-Phase-C call site that manually embeds {@code tenantId} in its
     * cache key (per design-c D-C-1: "existing 7 call sites already manually
     * embed tenantId in the cache key" — actual inventory is 10 methods,
     * spec-drift noted in warroom 2026-04-19 PM). These sites are
     * tenant-safe TODAY — they just aren't routed through
     * {@code TenantScopedCacheService} yet.
     *
     * <p><b>Task 4.b migration contract:</b> each migration MUST remove the
     * corresponding entry from this set. The rule will go red if a new site
     * is added without an annotation OR the allowlist entry isn't removed
     * when the site migrates. Zero-entry state at task 4.b completion is
     * the visible signal that 4.b is done.
     *
     * <p><b>FQN format</b> (not SimpleClassName) per warroom PR #137
     * post-commit review (Marcus hardening): prevents cross-package
     * class-name collisions from silently leaking an exemption between
     * two classes that happen to share a simple name. Per-method
     * granularity — a method may contain multiple {@code get/put/evict}
     * calls; the entry exempts the whole method.
     */
    private static final Set<String> PENDING_MIGRATION_SITES = Set.of(
            // Task 4.b complete (v0.47.0 release gate): all 9 original entries
            // migrated through TenantScopedCacheService. Empty set means
            // production code is fully routed through the wrapper; any new
            // entry requires design-c warroom sign-off per spec requirement
            // pending-migration-sites-drained.
    );

    private static final String SCOPE_PKG_SERVICE = "..service..";
    private static final String SCOPE_PKG_API = "..api..";
    private static final String SCOPE_PKG_SECURITY = "..security..";
    private static final String SCOPE_PKG_AUTH = "..auth..";

    private static com.tngtech.archunit.core.domain.JavaClasses importMain() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.fabt..");
    }

    // -------------------------------------------------------------------
    // Rule C1 — Raw CacheService call sites require justification
    // -------------------------------------------------------------------

    @Test
    @DisplayName("C1: CacheService.{get,put,evict,*} call sites require @TenantUnscopedCache/@TenantScopedByConstruction or wrapper")
    void c1_cacheServiceCallSite_requiresJustification() {
        ArchRule rule = methods()
                .that().areDeclaredInClassesThat().resideInAnyPackage(
                        SCOPE_PKG_SERVICE, SCOPE_PKG_API, SCOPE_PKG_SECURITY, SCOPE_PKG_AUTH)
                .and().areNotAnnotatedWith(TenantUnscopedCache.class)
                .and().areNotAnnotatedWith(TenantScopedByConstruction.class)
                .should(notCallRawCacheService());

        rule.check(importMain());
    }

    private static ArchCondition<JavaMethod> notCallRawCacheService() {
        return new ArchCondition<>("not call CacheService.{get,put,evict,evictAll,evictAllByPrefix} without @TenantUnscopedCache or @TenantScopedByConstruction justification") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                String ownerFqn = method.getOwner().getName();
                if (EXEMPT_CLASSES.contains(ownerFqn)) {
                    return;
                }
                // FQN format per warroom PR #137 hardening (Marcus):
                // <fully.qualified.ClassName>.<methodName>. Prevents cross-
                // package simple-name collisions.
                String siteKey = method.getOwner().getName() + "." + method.getName();
                if (PENDING_MIGRATION_SITES.contains(siteKey)) {
                    return;
                }
                for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
                    if (!CACHE_SERVICE_METHODS.contains(call.getName())) continue;
                    JavaClass callTargetOwner = call.getTarget().getOwner();
                    if (!isCacheServiceType(callTargetOwner)) continue;
                    events.add(SimpleConditionEvent.violated(method,
                            method.getFullName() + " calls " + callTargetOwner.getSimpleName()
                                    + "." + call.getName() + "() at "
                                    + call.getSourceCodeLocation()
                                    + " — annotate the method with "
                                    + "@TenantUnscopedCache(\"justification\") or "
                                    + "@TenantScopedByConstruction(\"justification\"), "
                                    + "route through TenantScopedCacheService, OR "
                                    + "add the method name to "
                                    + "FamilyCArchitectureTest.PENDING_MIGRATION_SITES "
                                    + "if this is a known pre-Phase-C site awaiting "
                                    + "task 4.b migration"));
                }
            }
        };
    }

    private static boolean isCacheServiceType(JavaClass cls) {
        if (cls.getName().equals(CACHE_SERVICE_CLASS)) return true;
        if (cls.getName().equals(TIERED_CACHE_SERVICE_CLASS)) return true;
        // Any subtype/impl of CacheService (e.g. CaffeineCacheService) also counts.
        for (JavaClass supertype : cls.getAllRawInterfaces()) {
            if (supertype.getName().equals(CACHE_SERVICE_CLASS)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------
    // Rule C2 — Spring @Cacheable/@CacheEvict/@CachePut blocked outright
    // -------------------------------------------------------------------

    @Test
    @DisplayName("C2: Spring @Cacheable / @CacheEvict / @CachePut blocked in all org.fabt classes")
    void c2_springCacheAnnotations_blocked() {
        ArchRule rule = noMethods()
                .that().areDeclaredInClassesThat().resideInAPackage("org.fabt..")
                .should().beAnnotatedWith("org.springframework.cache.annotation.Cacheable")
                .orShould().beAnnotatedWith("org.springframework.cache.annotation.CacheEvict")
                .orShould().beAnnotatedWith("org.springframework.cache.annotation.CachePut")
                .because("FABT uses zero Spring cache-abstraction annotations today — "
                        + "introducing them would create a parallel caching pattern with "
                        + "its own CacheManager + CacheResolver seams that would need a "
                        + "separate tenant-scoping story. Use TenantScopedCacheService "
                        + "(preferred) or directly-annotated Caffeine fields with "
                        + "@TenantUnscopedCache / @TenantScopedByConstruction justification. "
                        + "Design-c D-C-4.");

        rule.check(importMain());
    }

    // -------------------------------------------------------------------
    // Rule C3 — Caffeine field-type guard
    // -------------------------------------------------------------------

    @Test
    @DisplayName("C3: Caffeine Cache fields in *.service/*.api/*.security/*.auth.* must be annotated @TenantUnscopedCache or @TenantScopedByConstruction")
    void c3_caffeineFields_requireJustification() {
        ArchRule rule = fields()
                .that().haveRawType(Cache.class)
                .and().areDeclaredInClassesThat().resideInAnyPackage(
                        SCOPE_PKG_SERVICE, SCOPE_PKG_API, SCOPE_PKG_SECURITY, SCOPE_PKG_AUTH)
                .should(beAnnotatedWithNonEmptyCacheJustification());

        rule.check(importMain());
    }

    // -------------------------------------------------------------------
    // Negative tests — fixtures in org.fabt.architecture.fixtures.cache
    // assert each rule fires on the intentional violation pattern.
    // Warroom Riley add: 3 fixtures covering C1 bypass (not needed — C1
    // already has live positive via the SAFE_SITES), C2 (@Cacheable),
    // C3 unannotated + C3 empty-justification.
    // -------------------------------------------------------------------

    private static com.tngtech.archunit.core.domain.JavaClasses importFixtures() {
        return new ClassFileImporter()
                .importPackages("org.fabt.architecture.fixtures.cache");
    }

    @Test
    @DisplayName("C2 negative: @Cacheable on fixture method triggers violation")
    void c2_negative_springCacheableFiresRule() {
        ArchRule rule = noMethods()
                .that().areDeclaredInClassesThat().resideInAPackage("org.fabt..")
                .should().beAnnotatedWith("org.springframework.cache.annotation.Cacheable")
                .orShould().beAnnotatedWith("org.springframework.cache.annotation.CacheEvict")
                .orShould().beAnnotatedWith("org.springframework.cache.annotation.CachePut");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> rule.check(importFixtures()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("SpringCacheableFixture");
    }

    @Test
    @DisplayName("C3 negative: unannotated Caffeine field triggers violation")
    void c3_negative_unannotatedCaffeineFiresRule() {
        ArchRule rule = fields()
                .that().haveRawType(Cache.class)
                .and().areDeclaredInClassesThat().resideInAPackage("org.fabt.architecture.fixtures.cache..")
                .should(beAnnotatedWithNonEmptyCacheJustification());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> rule.check(importFixtures()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("UnannotatedCaffeineFixture");
    }

    @Test
    @DisplayName("C3 negative: empty-justification @TenantUnscopedCache triggers violation")
    void c3_negative_emptyJustificationFiresRule() {
        ArchRule rule = fields()
                .that().haveRawType(Cache.class)
                .and().areDeclaredInClassesThat().resideInAPackage("org.fabt.architecture.fixtures.cache..")
                .should(beAnnotatedWithNonEmptyCacheJustification());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> rule.check(importFixtures()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("EmptyJustificationFixture");
    }

    // -------------------------------------------------------------------
    // Shared condition used by Rule C3 + negative tests above
    // -------------------------------------------------------------------

    private static ArchCondition<JavaField> beAnnotatedWithNonEmptyCacheJustification() {
        return new ArchCondition<>("be annotated with @TenantUnscopedCache or @TenantScopedByConstruction carrying a non-empty justification") {
            @Override
            public void check(JavaField field, ConditionEvents events) {
                String ownerFqn = field.getOwner().getName();
                if (EXEMPT_CLASSES.contains(ownerFqn)) {
                    return;
                }
                boolean annotated = false;
                String value = null;
                if (field.isAnnotatedWith(TenantUnscopedCache.class)) {
                    annotated = true;
                    value = field.getAnnotationOfType(TenantUnscopedCache.class).value();
                } else if (field.isAnnotatedWith(TenantScopedByConstruction.class)) {
                    annotated = true;
                    value = field.getAnnotationOfType(TenantScopedByConstruction.class).value();
                }
                if (!annotated) {
                    events.add(SimpleConditionEvent.violated(field,
                            "Caffeine Cache field " + ownerFqn + "." + field.getName()
                                    + " at " + field.getSourceCodeLocation()
                                    + " is NOT annotated with @TenantUnscopedCache or "
                                    + "@TenantScopedByConstruction — add one with a non-empty "
                                    + "justification string explaining why tenant scoping would "
                                    + "be incorrect for this cache, OR route through "
                                    + "TenantScopedCacheService"));
                    return;
                }
                if (value == null || value.isBlank()) {
                    events.add(SimpleConditionEvent.violated(field,
                            "Caffeine Cache field " + ownerFqn + "." + field.getName()
                                    + " at " + field.getSourceCodeLocation()
                                    + " has an EMPTY justification string — the annotation "
                                    + "value must explain why tenant scoping would be incorrect "
                                    + "for this cache (reviewer needs to verify the key-space "
                                    + "claim)"));
                }
            }
        };
    }
}
