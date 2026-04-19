package org.fabt.shared.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a cache field whose key structurally includes the tenant id and
 * whose on-read code path verifies the tenant match — tenant-scoped by
 * construction, but NOT via the {@code TenantScopedCacheService} wrapper.
 *
 * <p>Distinct from {@link TenantUnscopedCache} (which marks caches whose
 * key space is structurally platform-wide). Reviewers see these two
 * annotations and ask different questions:
 *
 * <ul>
 *   <li>{@code @TenantUnscopedCache("kid is platform-unique")} — "confirm
 *       the key space is truly global; no tenant data could leak."</li>
 *   <li>{@code @TenantScopedByConstruction("composite PolicyKey(tenantId, policyId) + on-read verify at X.Y")}
 *       — "confirm the composite key includes tenantId AND the read path
 *       verifies the stored tenant matches the caller's."</li>
 * </ul>
 *
 * <p>{@code FamilyCArchitectureTest} accepts either annotation on
 * Caffeine fields in {@code *.service}, {@code *.api}, {@code *.security},
 * {@code *.auth.*}. Unannotated Caffeine fields in those packages fail the
 * build.
 *
 * <h2>Canonical example</h2>
 *
 * <pre>{@code
 * @TenantScopedByConstruction("tenant-scoped by composite PolicyKey + on-read verification; Phase C task 4.4")
 * private final Cache<PolicyKey, EscalationPolicy> policyByTenantAndId = Caffeine.newBuilder()
 *         .maximumSize(500)
 *         .expireAfterAccess(Duration.ofMinutes(10))
 *         .recordStats()
 *         .build();
 * }</pre>
 *
 * <h2>Review gate</h2>
 *
 * Like {@link TenantUnscopedCache}, new introductions trigger
 * {@code @ccradle} review via the CI grep job. Reviewer MUST verify both
 * (a) the composite key includes tenantId, and (b) the read path has an
 * explicit tenantId-match check.
 *
 * <h2>Design-c reference</h2>
 *
 * Per Phase C task 4.2+4.3+4.5 warroom (D-4.23-1 resolution): splitting
 * {@code @TenantUnscopedCache} vs {@code @TenantScopedByConstruction} was
 * chosen over a single overloaded annotation because the two categories
 * answer different review questions with different failure modes.
 * Conflating them under one name is the kind of thing Casey flags as
 * legal-adjacent misleading documentation.
 */
@Target({ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TenantScopedByConstruction {

    /**
     * Human-readable explanation of how the cache is tenant-scoped by
     * construction: (a) what the composite key shape is, and (b) where
     * the on-read tenant verification happens. Must be non-empty at build
     * time (enforced by {@code FamilyCArchitectureTest}).
     *
     * @return the justification string
     */
    String value();
}
