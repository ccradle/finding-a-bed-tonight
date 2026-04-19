package org.fabt.shared.security;

import java.time.Duration;
import java.util.Collection;
import java.util.UUID;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Caffeine-backed cache for the {@code jwt_revocations} fast-path lookup
 * per A4 D26. JWT validate calls {@link #isRevoked(UUID)} BEFORE
 * signature verify (saves the HMAC compute on revoked tokens; also
 * means any cache hit results in immediate rejection without doing
 * other work).
 *
 * <p>Cache TTL is short (1 minute per warroom Q2) so revocations
 * propagate fast across replicas. Emergency revocations call
 * {@link #invalidateKid(UUID)} to bypass the TTL — sub-second
 * propagation. {@link #invalidateAll(Collection)} bulk-evicts a set
 * of kids in one shot, used by {@code TenantKeyRotationService.bumpJwtKeyGeneration}
 * (A4 D27 step 5) when a rotation marks an entire generation's worth
 * of kids as revoked.
 *
 * <p>Cache stores Boolean (true = revoked, false = not revoked). False
 * entries DON'T expire faster than true entries — both follow the
 * 1-min TTL — because a kid that's ever-not-revoked staying not-revoked
 * for 1 min is fine (any new revocation invalidates explicitly).
 *
 * <p>The underlying SQL is {@code SELECT EXISTS(SELECT 1 FROM
 * jwt_revocations WHERE kid = ?)} — a single PK index probe; sub-ms.
 * The cache eliminates 99%+ of these probes after warmup.
 */
@Service
public class RevokedKidCache {

    private final JdbcTemplate jdbc;

    @TenantUnscopedCache("kid is a globally-unique opaque UUID; revocation list is platform-wide (any tenant's revoked kid must be rejected by any validator regardless of calling tenant context); read path is in Spring Security filter chain BEFORE TenantContext is bound")
    private final Cache<UUID, Boolean> cache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();

    public RevokedKidCache(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns true if the kid is in {@code jwt_revocations}. Cache miss
     * triggers a {@code SELECT EXISTS} probe; result cached for 1 min.
     */
    public boolean isRevoked(UUID kid) {
        Boolean cached = cache.getIfPresent(kid);
        if (cached != null) {
            return cached;
        }
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM jwt_revocations WHERE kid = ?)",
                Boolean.class, kid);
        boolean result = Boolean.TRUE.equals(exists);
        cache.put(kid, result);
        return result;
    }

    /**
     * Bypass the TTL — evict a single kid's cache entry so the next
     * {@link #isRevoked} probe re-reads from DB. Called by emergency-
     * revoke flows for sub-second cross-replica propagation.
     */
    public void invalidateKid(UUID kid) {
        cache.invalidate(kid);
    }

    /**
     * Bulk-invalidate. Called by
     * {@code TenantKeyRotationService.bumpJwtKeyGeneration} (A4 D27
     * step 5) to evict every kid that the rotation just added to
     * {@code jwt_revocations}.
     */
    public void invalidateAll(Collection<UUID> kids) {
        cache.invalidateAll(kids);
    }
}
