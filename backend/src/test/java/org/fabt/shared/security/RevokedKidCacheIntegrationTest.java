package org.fabt.shared.security;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end Testcontainers test for {@link RevokedKidCache}'s contract
 * with the {@code jwt_revocations} table (V61). Exercises:
 *
 * <ul>
 *   <li>Initial unknown kid → false (not revoked) + cached</li>
 *   <li>INSERT into jwt_revocations + cache stale → still returns false
 *       (cache hit) until invalidated</li>
 *   <li>{@link RevokedKidCache#invalidateKid(UUID)} bypass → next call
 *       re-reads + returns true (D26 emergency-revoke pattern)</li>
 *   <li>{@link RevokedKidCache#invalidateAll(Collection)} bulk bypass —
 *       used by {@code TenantKeyRotationService.bumpJwtKeyGeneration}
 *       (A4 D27 step 5)</li>
 * </ul>
 *
 * <p>Note: TTL-based eviction (1 minute) is NOT tested directly because
 * waiting 60s is not worth the IT runtime. The contract that matters
 * for production correctness is the explicit-bypass paths.
 */
class RevokedKidCacheIntegrationTest extends BaseIntegrationTest {

    @Autowired private RevokedKidCache cache;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("Unknown kid returns false; subsequent call hits cache (no second DB roundtrip)")
    void unknownKidNotRevoked() {
        UUID kid = UUID.randomUUID();
        assertFalse(cache.isRevoked(kid));
        // Second call returns the cached false; we infer cache hit by behavior consistency
        assertFalse(cache.isRevoked(kid));
    }

    @Test
    @DisplayName("Inserting into jwt_revocations after a cache-populating call leaves the cache stale until invalidation")
    void cacheStalenessUntilInvalidate() {
        UUID kid = UUID.randomUUID();

        // 1. Populate cache with "not revoked"
        assertFalse(cache.isRevoked(kid));

        // 2. Insert into the DB — cache still says false
        jdbc.update("INSERT INTO jwt_revocations (kid, expires_at) VALUES (?, ?)",
                kid, java.sql.Timestamp.from(Instant.now().plusSeconds(86400)));
        assertFalse(cache.isRevoked(kid),
                "stale-cache demonstration: cached false survives DB insert until invalidation");

        // 3. Invalidate → next call re-reads → true
        cache.invalidateKid(kid);
        assertTrue(cache.isRevoked(kid),
                "post-invalidation lookup must reflect the DB insert");
    }

    @Test
    @DisplayName("invalidateAll bulk-evicts a set of kids (used by bumpJwtKeyGeneration)")
    void invalidateAllBulkEviction() {
        UUID kid1 = UUID.randomUUID();
        UUID kid2 = UUID.randomUUID();
        UUID kid3 = UUID.randomUUID();

        // Populate cache for all 3 with "not revoked"
        assertFalse(cache.isRevoked(kid1));
        assertFalse(cache.isRevoked(kid2));
        assertFalse(cache.isRevoked(kid3));

        // Insert all 3 into DB; cache still stale
        java.sql.Timestamp exp = java.sql.Timestamp.from(Instant.now().plusSeconds(86400));
        jdbc.update("INSERT INTO jwt_revocations (kid, expires_at) VALUES (?, ?)", kid1, exp);
        jdbc.update("INSERT INTO jwt_revocations (kid, expires_at) VALUES (?, ?)", kid2, exp);
        jdbc.update("INSERT INTO jwt_revocations (kid, expires_at) VALUES (?, ?)", kid3, exp);
        assertFalse(cache.isRevoked(kid1));
        assertFalse(cache.isRevoked(kid2));
        assertFalse(cache.isRevoked(kid3));

        // Bulk invalidate
        cache.invalidateAll(List.of(kid1, kid2, kid3));

        // All 3 now reflect the DB
        assertTrue(cache.isRevoked(kid1));
        assertTrue(cache.isRevoked(kid2));
        assertTrue(cache.isRevoked(kid3));
    }

    @Test
    @DisplayName("Pre-existing DB row is detected by first cache miss")
    void preExistingDbRowDetected() {
        UUID kid = UUID.randomUUID();
        jdbc.update("INSERT INTO jwt_revocations (kid, expires_at) VALUES (?, ?)",
                kid, java.sql.Timestamp.from(Instant.now().plusSeconds(86400)));
        // First call hits DB (cache miss) and correctly returns true
        assertTrue(cache.isRevoked(kid));
        // Second call hits cache + returns true
        assertTrue(cache.isRevoked(kid));
    }
}
