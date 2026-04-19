package org.fabt.architecture.fixtures.cache;

import java.time.Duration;
import java.util.Optional;

import org.fabt.shared.cache.CacheNames;
import org.fabt.shared.cache.CacheService;

/**
 * Negative-test fixture for {@code NegativeCacheGuardrailTest}.
 *
 * <p>Intentionally calls
 * {@code cacheService.put(cacheName, key, Optional.empty(), ttl)} — the
 * Optional-as-bypass pattern the negative-cache guardrail rejects (design-c
 * D-C-5). The correct pattern is
 * {@code TenantScopedCacheService.putNegative(cacheName, key, ttl)}.
 *
 * <p>DO NOT replace the Optional.empty() with a non-empty value. That
 * defeats the fixture.
 */
public class OptionalEmptyPutFixture {

    private final CacheService cacheService;

    public OptionalEmptyPutFixture(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    /** Intentional Optional.empty() put — the guardrail MUST flag this. */
    public void writeEmptyOptional(String key) {
        cacheService.put(CacheNames.SHELTER_PROFILE, key, Optional.empty(), Duration.ofSeconds(60));
    }
}
