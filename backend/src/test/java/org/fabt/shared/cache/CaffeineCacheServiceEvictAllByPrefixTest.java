package org.fabt.shared.cache;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the Phase C {@link CacheService#evictAllByPrefix} API extension on
 * the Caffeine implementation. See spec
 * {@code cache-service-evict-all-by-prefix} + design-c D-C-12.
 */
@DisplayName("CaffeineCacheService.evictAllByPrefix")
class CaffeineCacheServiceEvictAllByPrefixTest {

    private CaffeineCacheService cache;

    @BeforeEach
    void setUp() {
        // 60s default TTL per application.yml; tests complete well inside that window.
        cache = new CaffeineCacheService(60L);
    }

    @Test
    @DisplayName("Evicts only keys starting with prefix; returns count")
    void evictsOnlyPrefixedKeys() {
        Duration ttl = Duration.ofSeconds(30);
        cache.put("shelter-profile", "tenantA|s1", "valueA1", ttl);
        cache.put("shelter-profile", "tenantA|s2", "valueA2", ttl);
        cache.put("shelter-profile", "tenantB|s1", "valueB1", ttl);

        long evicted = cache.evictAllByPrefix("shelter-profile", "tenantA|");

        assertThat(evicted).isEqualTo(2L);
        assertThat(cache.get("shelter-profile", "tenantA|s1", String.class)).isEmpty();
        assertThat(cache.get("shelter-profile", "tenantA|s2", String.class)).isEmpty();
        assertThat(cache.get("shelter-profile", "tenantB|s1", String.class))
                .contains("valueB1");
    }

    @Test
    @DisplayName("Returns 0 on never-written cache name — no NPE")
    void returnsZeroOnAbsentCache() {
        long evicted = cache.evictAllByPrefix("never-written", "tenantA|");
        assertThat(evicted).isEqualTo(0L);
    }

    @Test
    @DisplayName("Returns 0 when no key matches prefix")
    void returnsZeroOnNoMatch() {
        cache.put("shelter-profile", "tenantB|s1", "valueB1", Duration.ofSeconds(30));

        long evicted = cache.evictAllByPrefix("shelter-profile", "tenantA|");

        assertThat(evicted).isEqualTo(0L);
        assertThat(cache.get("shelter-profile", "tenantB|s1", String.class))
                .contains("valueB1");
    }

    @Test
    @DisplayName("Empty-prefix evicts all keys (implementation detail; documented)")
    void emptyPrefixEvictsAll() {
        cache.put("shelter-profile", "tenantA|s1", "a", Duration.ofSeconds(30));
        cache.put("shelter-profile", "tenantB|s1", "b", Duration.ofSeconds(30));

        long evicted = cache.evictAllByPrefix("shelter-profile", "");

        assertThat(evicted).isEqualTo(2L);
        assertThat(cache.get("shelter-profile", "tenantA|s1", String.class)).isEmpty();
        assertThat(cache.get("shelter-profile", "tenantB|s1", String.class)).isEmpty();
    }

    @Test
    @DisplayName("Idempotent — repeated calls after full eviction return 0")
    void idempotent() {
        cache.put("shelter-profile", "tenantA|s1", "a", Duration.ofSeconds(30));

        long first = cache.evictAllByPrefix("shelter-profile", "tenantA|");
        long second = cache.evictAllByPrefix("shelter-profile", "tenantA|");

        assertThat(first).isEqualTo(1L);
        assertThat(second).isEqualTo(0L);

        Optional<String> miss = cache.get("shelter-profile", "tenantA|s1", String.class);
        assertThat(miss).isEmpty();
    }
}
