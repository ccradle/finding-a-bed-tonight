package org.fabt.shared.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Two-tier cache: Caffeine L1 (in-process) + Redis L2 (distributed).
 * Falls back to L1-only if Redis is unavailable.
 */
@Service
@Profile({"standard", "full"})
public class TieredCacheService implements CacheService {

    private static final Logger log = LoggerFactory.getLogger(TieredCacheService.class);

    private final ConcurrentMap<String, Cache<String, Object>> l1Caches = new ConcurrentHashMap<>();
    private final Duration l1Ttl;

    public TieredCacheService(
            @Value("${fabt.cache.l1-ttl-seconds:60}") long l1TtlSeconds) {
        this.l1Ttl = Duration.ofSeconds(l1TtlSeconds);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String cacheName, String key, Class<T> type) {
        // Check L1 first
        Cache<String, Object> l1 = l1Caches.get(cacheName);
        if (l1 != null) {
            Object value = l1.getIfPresent(key);
            if (value != null) {
                return Optional.of((T) value);
            }
        }

        // TODO: Check L2 (Redis) — will be wired when Redis infrastructure is implemented
        // On L2 hit, promote to L1

        return Optional.empty();
    }

    @Override
    public <T> void put(String cacheName, String key, T value, Duration ttl) {
        // Write to L1
        Cache<String, Object> l1 = l1Caches.computeIfAbsent(cacheName, n ->
                Caffeine.newBuilder()
                        .expireAfterWrite(l1Ttl)
                        .maximumSize(1000)
                        .build()
        );
        l1.put(key, value);

        // TODO: Write to L2 (Redis) with l2Ttl
    }

    @Override
    public void evict(String cacheName, String key) {
        Cache<String, Object> l1 = l1Caches.get(cacheName);
        if (l1 != null) {
            l1.invalidate(key);
        }
        // TODO: Evict from L2 (Redis)
    }

    @Override
    public void evictAll(String cacheName) {
        Cache<String, Object> l1 = l1Caches.get(cacheName);
        if (l1 != null) {
            l1.invalidateAll();
        }
        // TODO: Evict from L2 (Redis)
    }

    @Override
    public long evictAllByPrefix(String cacheName, String prefix) {
        Cache<String, Object> l1 = l1Caches.get(cacheName);
        long l1Evicted = 0L;
        if (l1 != null) {
            java.util.List<String> toEvict = l1.asMap().keySet().stream()
                    .filter(k -> k.startsWith(prefix))
                    .toList();
            toEvict.forEach(l1::invalidate);
            l1Evicted = toEvict.size();
        }
        // TODO: Evict from L2 (Redis) when L2 is wired (per redis-pooling-adr.md
        // shape 2 or 3). Implementation contract:
        //   SCAN 0 MATCH "<prefix>*" COUNT 1000 + UNLINK per batch.
        //   Never KEYS or DEL (both main-thread-blocking on large key counts).
        //   Return sum of L1 + L2 evictions once L2 is live.
        return l1Evicted;
    }
}
