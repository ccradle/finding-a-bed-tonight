package org.fabt.architecture.fixtures.cache;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Negative-test fixture for {@code FamilyCArchitectureTest} Rule C3.
 *
 * <p>This class is intentionally placed in {@code org.fabt.architecture.fixtures.cache}
 * (NOT in any of the Family C scope packages: {@code *.service}, {@code *.api},
 * {@code *.security}, {@code *.auth.*}) so the production Rule C3 does NOT scan
 * it and the build stays green. The negative test imports this package
 * explicitly and runs Rule C3 against it, asserting the rule fires as
 * expected.
 *
 * <p>This fixture has an UNANNOTATED Caffeine cache field — Rule C3 must
 * produce a violation when scoped to this package.
 *
 * <p>DO NOT add {@code @TenantUnscopedCache} or
 * {@code @TenantScopedByConstruction} to this field; that would defeat the
 * fixture's purpose.
 */
public class UnannotatedCaffeineFixture {

    // Intentionally unannotated — the negative test asserts Rule C3 fires.
    private final Cache<String, String> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    public String get(String key) {
        return cache.getIfPresent(key);
    }
}
