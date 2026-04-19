package org.fabt.shared.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@Profile("lite")
public class CaffeineCacheService implements CacheService {

    private final ConcurrentMap<String, Cache<String, Object>> caches = new ConcurrentHashMap<>();
    private final Duration defaultTtl;

    public CaffeineCacheService(@Value("${fabt.cache.l1-ttl-seconds:60}") long ttlSeconds) {
        this.defaultTtl = Duration.ofSeconds(ttlSeconds);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String cacheName, String key, Class<T> type) {
        Cache<String, Object> cache = caches.get(cacheName);
        if (cache == null) {
            return Optional.empty();
        }
        Object value = cache.getIfPresent(key);
        return Optional.ofNullable((T) value);
    }

    @Override
    public <T> void put(String cacheName, String key, T value, Duration ttl) {
        Cache<String, Object> cache = caches.computeIfAbsent(cacheName, n ->
                Caffeine.newBuilder()
                        .expireAfterWrite(ttl != null ? ttl : defaultTtl)
                        .maximumSize(1000)
                        .build()
        );
        cache.put(key, value);
    }

    @Override
    public void evict(String cacheName, String key) {
        Cache<String, Object> cache = caches.get(cacheName);
        if (cache != null) {
            cache.invalidate(key);
        }
    }

    @Override
    public void evictAll(String cacheName) {
        Cache<String, Object> cache = caches.get(cacheName);
        if (cache != null) {
            cache.invalidateAll();
        }
    }

    @Override
    public long evictAllByPrefix(String cacheName, String prefix) {
        Cache<String, Object> cache = caches.get(cacheName);
        if (cache == null) {
            return 0L;
        }
        java.util.List<String> toEvict = cache.asMap().keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .toList();
        toEvict.forEach(cache::invalidate);
        return toEvict.size();
    }
}
