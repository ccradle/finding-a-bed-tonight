package org.fabt.shared.cache;

import java.time.Duration;
import java.util.Optional;

public interface CacheService {

    <T> Optional<T> get(String cacheName, String key, Class<T> type);

    <T> void put(String cacheName, String key, T value, Duration ttl);

    void evict(String cacheName, String key);

    void evictAll(String cacheName);

    /**
     * Evicts every entry in {@code cacheName} whose key starts with {@code prefix}.
     * Returns the number of entries evicted.
     *
     * <p>Added in Phase C (multi-tenant-production-readiness) to support
     * {@code TenantScopedCacheService.invalidateTenant(UUID)} without coupling
     * the wrapper to a specific cache implementation.
     *
     * <p>Implementation guidance:
     * <ul>
     *   <li>Caffeine: filter {@code cache.asMap().keySet()} by prefix + invalidate each match.</li>
     *   <li>Redis L2 (future, per redis-pooling-adr.md shape 2/3):
     *       {@code SCAN 0 MATCH "<prefix>*" COUNT 1000} iteratively with
     *       {@code UNLINK} per batch. {@code UNLINK} is non-blocking on the Redis
     *       main thread; {@code DEL} and {@code KEYS} are explicitly rejected
     *       per Redis Inc.'s Feb 2026 guidance for multi-tenant deployments.</li>
     * </ul>
     *
     * <p>If {@code cacheName} has never been written to, returns {@code 0} without
     * throwing. A malformed or absent cache is NOT an error from the caller's
     * perspective — the tenant-lifecycle FSM is allowed to invalidate a never-
     * written tenant.
     *
     * @param cacheName the logical cache name (one of {@link CacheNames})
     * @param prefix the key prefix to match; callers pass the tenant prefix
     *               (e.g. {@code "<tenantUuid>|"}) from
     *               {@code TenantScopedCacheService}
     * @return count of entries evicted; {@code 0} if the cache is empty or absent
     */
    long evictAllByPrefix(String cacheName, String prefix);
}
