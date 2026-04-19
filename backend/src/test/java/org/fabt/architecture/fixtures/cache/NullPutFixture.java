package org.fabt.architecture.fixtures.cache;

import java.time.Duration;

import org.fabt.shared.cache.CacheNames;
import org.fabt.shared.cache.CacheService;

/**
 * Negative-test fixture for {@code NegativeCacheGuardrailTest}.
 *
 * <p>Intentionally calls {@code cacheService.put(cacheName, key, null, ttl)}
 * with a LITERAL null as the value argument. The production
 * {@code NegativeCacheGuardrailTest} does NOT scan this package (production
 * scan is rooted at {@code src/main/java/org/fabt/}); the negative test
 * invokes the scan explicitly against the fixtures sub-tree and asserts the
 * violation fires.
 *
 * <p>DO NOT fix the null. That defeats the fixture.
 */
public class NullPutFixture {

    private final CacheService cacheService;

    public NullPutFixture(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    /** Intentional literal-null put — the guardrail MUST flag this. */
    public void writeNull(String key) {
        cacheService.put(CacheNames.SHELTER_PROFILE, key, null, Duration.ofSeconds(60));
    }
}
