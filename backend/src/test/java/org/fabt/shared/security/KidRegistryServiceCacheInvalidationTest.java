package org.fabt.shared.security;

import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.testsupport.WithTenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Verifies the cache-invalidation hooks added in the post-A3-warroom commit:
 *
 * <ul>
 *   <li>{@link KidRegistryService#invalidateTenantActiveKid(UUID)} — required
 *       by Phase A4's {@code bumpJwtKeyGeneration} so post-rotation encrypts
 *       don't use the old generation's kid for up to the 5-min TTL window.</li>
 *   <li>{@link KidRegistryService#invalidateKidResolution(UUID)} — required
 *       by Phase F's tenant hard-delete (crypto-shred) so in-flight decrypts
 *       see the deletion immediately.</li>
 * </ul>
 *
 * <p>Validates the contract by simulating rotation: encrypt under generation 1,
 * manually mutate the DB to mark generation 1 as inactive + insert generation
 * 2 + insert a new kid, invalidate the cache, then verify the next
 * findOrCreateActiveKid returns the NEW kid (would have returned the cached
 * old kid without invalidation).
 */
class KidRegistryServiceCacheInvalidationTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private KidRegistryService kidRegistry;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        tenantId = authHelper.getTestTenantId();
    }

    @Test
    @DisplayName("invalidateTenantActiveKid evicts the cached kid; next call re-reads from DB")
    void invalidateTenantActiveKid_evictsCache() {
        // 1. First call populates the cache + creates generation 1 + first kid
        UUID firstKid = kidRegistry.findOrCreateActiveKid(tenantId);
        assertEquals(firstKid, kidRegistry.findOrCreateActiveKid(tenantId),
                "second call without invalidation must return the cached value");

        // 2. Simulate rotation in the DB: deactivate gen 1, add gen 2, register a new kid.
        // Post-Phase-B: tenant_key_material + kid_to_tenant_key have RESTRICTIVE
        // WRITE policies requiring tenant_id = fabt_current_tenant_id(); wrap these
        // DDL-ish writes in the tenant's context so the policies accept them.
        UUID rotatedKid = UUID.randomUUID();
        WithTenantContext.doAs(tenantId, () -> {
            jdbc.update("UPDATE tenant_key_material SET active = FALSE, rotated_at = NOW() "
                      + "WHERE tenant_id = ? AND generation = 1", tenantId);
            jdbc.update("INSERT INTO tenant_key_material (tenant_id, generation, active) "
                      + "VALUES (?, 2, TRUE)", tenantId);
            jdbc.update("INSERT INTO kid_to_tenant_key (kid, tenant_id, generation) "
                      + "VALUES (?, ?, 2)", rotatedKid, tenantId);
        });

        // 3. Without invalidation, cache returns stale firstKid
        assertEquals(firstKid, kidRegistry.findOrCreateActiveKid(tenantId),
                "stale-cache demonstration: returns the pre-rotation kid until invalidation");

        // 4. After invalidation, next call re-reads + sees the new active kid
        kidRegistry.invalidateTenantActiveKid(tenantId);
        UUID afterInvalidation = kidRegistry.findOrCreateActiveKid(tenantId);
        assertEquals(rotatedKid, afterInvalidation,
                "post-invalidation lookup must return the new active kid (gen 2's kid)");
        assertNotEquals(firstKid, afterInvalidation, "new kid must differ from pre-rotation kid");
    }

    @Test
    @DisplayName("invalidateKidResolution evicts the cached resolution; next call re-reads from DB")
    void invalidateKidResolution_evictsCache() {
        // 1. Create a kid, resolve it, populating the resolution cache
        UUID kid = kidRegistry.findOrCreateActiveKid(tenantId);
        KidRegistryService.KidResolution first = kidRegistry.resolveKid(kid);
        KidRegistryService.KidResolution second = kidRegistry.resolveKid(kid);
        assertEquals(first, second, "second call without invalidation hits the cache (same value)");

        // 2. Simulate hard-delete: drop the kid_to_tenant_key row + tenant_key_material row.
        // Post-Phase-B: DELETE is RESTRICTIVE by tenant_id; wrap in tenant binding.
        WithTenantContext.doAs(tenantId, () -> {
            jdbc.update("DELETE FROM kid_to_tenant_key WHERE kid = ?", kid);
            jdbc.update("DELETE FROM tenant_key_material WHERE tenant_id = ?", tenantId);
        });

        // 3. Without invalidation, cache still returns the stale resolution
        KidRegistryService.KidResolution stillCached = kidRegistry.resolveKid(kid);
        assertEquals(first, stillCached,
                "stale-cache demonstration: resolution survives DB deletion until invalidation");

        // 4. After invalidation, next call re-reads + throws (kid no longer exists)
        kidRegistry.invalidateKidResolution(kid);
        try {
            kidRegistry.resolveKid(kid);
            org.junit.jupiter.api.Assertions.fail(
                    "post-invalidation resolveKid must throw NoSuchElementException — DB row is gone");
        } catch (java.util.NoSuchElementException expected) {
            // good — invalidation forced a DB re-read which correctly throws
        }
    }
}
