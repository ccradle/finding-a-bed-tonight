package org.fabt.architecture.fixtures.cache;

import org.springframework.cache.annotation.Cacheable;

/**
 * Negative-test fixture for {@code FamilyCArchitectureTest} Rule C2.
 *
 * <p>Placed in {@code org.fabt.architecture.fixtures.cache} (outside the
 * production scan scope of Rule C2 which scans {@code org.fabt..}, except
 * the test fixtures should not be picked up because the production scan
 * uses {@code ImportOption.DoNotIncludeTests}). The negative test invokes
 * Rule C2 against an explicit ClassFileImporter that DOES include this
 * test-tree sub-package and asserts the rule fires.
 *
 * <p>DO NOT remove the {@code @Cacheable} annotation from this method.
 */
public class SpringCacheableFixture {

    // Intentionally annotated @Cacheable — the negative test asserts Rule C2 fires.
    @Cacheable("fixture-cache")
    public String lookup(String key) {
        return "value:" + key;
    }
}
