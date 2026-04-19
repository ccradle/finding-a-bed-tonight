package org.fabt.architecture.fixtures.cache;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.fabt.shared.security.TenantUnscopedCache;

/**
 * Negative-test fixture for {@code FamilyCArchitectureTest} Rule C3.
 *
 * <p>This fixture annotates its Caffeine field with
 * {@code @TenantUnscopedCache("")} — empty string justification. Rule C3
 * MUST fire on empty-or-blank justification strings because the annotation
 * is the reviewer's documentation: an empty string provides zero defense
 * at review time.
 *
 * <p>DO NOT fill in the justification. That would defeat the fixture.
 */
public class EmptyJustificationFixture {

    // Intentionally empty justification — the negative test asserts Rule C3 fires.
    @TenantUnscopedCache("")
    private final Cache<String, String> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    public String get(String key) {
        return cache.getIfPresent(key);
    }
}
