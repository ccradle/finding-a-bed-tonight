package org.fabt.shared.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a cache field or cache-access method as an intentional exception to
 * the Phase C Family C rule that requires every application-layer cache to
 * route through {@code TenantScopedCacheService}. Used ONLY for caches whose
 * key space is structurally platform-wide or pre-authentication — e.g., JWT
 * claims by globally-unique token hash, MFA JTI blocklist, kid-to-tenant
 * resolution.
 *
 * <p>The justification string is required and must be non-empty. It becomes
 * the reviewer's future-self documentation: why this cache is safe to
 * bypass tenant scoping. {@code FamilyCArchitectureTest} enforces presence
 * of the annotation on every Caffeine field in {@code org.fabt.*.service},
 * {@code *.api}, {@code *.security}, {@code *.auth.*} and on every method
 * that makes a raw {@code CacheService.get/put/evict} call from those
 * packages.
 *
 * <h2>When to use</h2>
 *
 * Use {@code @TenantUnscopedCache} when ALL of the following hold:
 * <ul>
 *   <li>The cache key is globally unique by construction (e.g., SHA-256
 *       token hash, opaque kid UUID, JTI UUID).</li>
 *   <li>The cached values are keyed lookups only — tenant-bearing payloads
 *       are not stored, or the payload itself carries the tenant discriminator
 *       (resolved at use-site, not at cache-site).</li>
 *   <li>A reviewer reading the justification can independently verify the
 *       key-space claim without reading the surrounding code.</li>
 * </ul>
 *
 * <p>Use {@link TenantScopedByConstruction} instead when the cache key is
 * composite (e.g., {@code (tenantId, resourceId)}) and the cache IS
 * tenant-scoped — just not via the {@code TenantScopedCacheService} wrapper
 * because it uses a stronger typed key than a raw string.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @TenantUnscopedCache("JWT claims by token hash; token hash is globally unique")
 * private final Cache<String, Claims> claimsCache = Caffeine.newBuilder()
 *         .maximumSize(1000)
 *         .expireAfterWrite(Duration.ofMinutes(5))
 *         .build();
 * }</pre>
 *
 * <h2>Review gate</h2>
 *
 * New {@code @TenantUnscopedCache} introductions trigger {@code @ccradle}
 * review via a CI grep job on every PR diff. The author must defend the
 * justification in review; the reviewer must confirm the key-space claim.
 * See {@code .github/workflows/ci.yml} and {@code .github/CODEOWNERS} for
 * the gate configuration.
 *
 * <h2>Non-goals</h2>
 *
 * This annotation does NOT disable tenant isolation for the annotated
 * cache at runtime — it is a review-time documentation marker plus an
 * ArchUnit-rule escape hatch. The annotated cache is still subject to all
 * other platform controls (auth filters, RLS on underlying DB queries,
 * etc.).
 */
@Target({ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TenantUnscopedCache {

    /**
     * Human-readable explanation of why this cache is structurally safe to
     * bypass tenant scoping. Must be non-empty at build time (enforced by
     * {@code FamilyCArchitectureTest}). Named {@code value} so callers can
     * use the positional shorthand {@code @TenantUnscopedCache("...")}.
     *
     * @return the justification string
     */
    String value();
}
