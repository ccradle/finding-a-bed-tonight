package org.fabt.shared.cache;

import java.time.Duration;
import java.util.Optional;

public interface CacheService {

    <T> Optional<T> get(String cacheName, String key, Class<T> type);

    <T> void put(String cacheName, String key, T value, Duration ttl);

    void evict(String cacheName, String key);

    void evictAll(String cacheName);
}
