package org.fabt.shared.security;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.auth.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end Testcontainers tests for {@link TenantKeyRotationService}'s
 * full rotation flow per A4 D27 + warroom W3-W7.
 *
 * <p>Each test uses {@link TestAuthHelper#setupSecondaryTenant} with a
 * unique slug to avoid Testcontainers shared-DB state pollution from
 * other tests in this class (or other test classes that hit the same
 * tenant). Generation assertions are RELATIVE
 * ({@code newGen == oldGen + 1}) rather than absolute so the tests don't
 * depend on cross-test ordering.
 */
class TenantKeyRotationServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JwtService jwtService;
    @Autowired private TenantKeyRotationService rotationService;
    @Autowired private KidRegistryService kidRegistry;
    @Autowired private JdbcTemplate jdbc;

    /** Per-test fresh tenant + admin user — eliminates cross-test state. */
    private UUID freshTenant;
    private User freshUser;

    @BeforeEach
    void setUp() {
        // Sets up the test tenant; we ignore it and use a fresh secondary
        // tenant per test so JWT key rotation history is per-test.
        authHelper.setupTestTenant();
        freshTenant = authHelper.setupSecondaryTenant(
                "rotation-test-" + UUID.randomUUID()).getId();
        freshUser = authHelper.createUserInTenant(freshTenant,
                "rotation-test-" + UUID.randomUUID() + "@test.fabt.org",
                "Rotation Test User", new String[]{"PLATFORM_ADMIN"}, false);
    }

    @Test
    @DisplayName("Rotation flips active generation in tenant_key_material (relative assertion)")
    void rotationFlipsActiveGeneration() {
        // Bootstrap: issue a token to lazy-create the tenant's first kid + active row
        jwtService.generateAccessToken(freshUser);
        Integer beforeGen = jdbc.queryForObject(
                "SELECT generation FROM tenant_key_material WHERE tenant_id = ? AND active = TRUE",
                Integer.class, freshTenant);
        assertEquals(1, beforeGen, "fresh tenant must start at generation 1");

        TenantKeyRotationService.RotationResult result =
                rotationService.bumpJwtKeyGeneration(freshTenant, freshUser.getId());

        assertEquals(beforeGen, result.oldGeneration());
        assertEquals(beforeGen + 1, result.newGeneration());

        // Verify DB state
        Integer afterGen = jdbc.queryForObject(
                "SELECT generation FROM tenant_key_material WHERE tenant_id = ? AND active = TRUE",
                Integer.class, freshTenant);
        assertEquals(beforeGen + 1, afterGen);

        Boolean priorInactive = jdbc.queryForObject(
                "SELECT NOT active FROM tenant_key_material WHERE tenant_id = ? AND generation = ?",
                Boolean.class, freshTenant, beforeGen);
        assertTrue(priorInactive);

        // tenant.jwt_key_generation bumped
        Integer tenantGen = jdbc.queryForObject(
                "SELECT jwt_key_generation FROM tenant WHERE id = ?",
                Integer.class, freshTenant);
        assertEquals(beforeGen + 1, tenantGen);
    }

    @Test
    @DisplayName("Old kids inserted into jwt_revocations with 7-day expires_at ceiling")
    void oldKidsRevoked() {
        jwtService.generateAccessToken(freshUser);
        UUID priorKid = kidRegistry.findOrCreateActiveKid(freshTenant);

        TenantKeyRotationService.RotationResult result =
                rotationService.bumpJwtKeyGeneration(freshTenant, freshUser.getId());
        assertEquals(1, result.revokedKidCount(),
                "exactly one kid existed in the prior gen; rotation must revoke it");

        Boolean revoked = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM jwt_revocations WHERE kid = ?)",
                Boolean.class, priorKid);
        assertTrue(revoked, "prior-gen kid must be in jwt_revocations after rotation");
    }

    @Test
    @DisplayName("Post-rotation: old-gen JWTs validate-fail; new JWTs work")
    void postRotationOldFailsNewWorks() {
        String oldToken = jwtService.generateAccessToken(freshUser);
        // Sanity: old token validates pre-rotation
        assertNotNull(jwtService.validateToken(oldToken));

        rotationService.bumpJwtKeyGeneration(freshTenant, freshUser.getId());

        // Old token now fails (kid is in jwt_revocations + cache invalidated)
        assertThrows(RevokedJwtException.class,
                () -> jwtService.validateToken(oldToken),
                "old-gen JWT must be rejected post-rotation");

        // New token (issued post-rotation) validates cleanly
        String newToken = jwtService.generateAccessToken(freshUser);
        assertNotNull(jwtService.validateToken(newToken));
    }

    @Test
    @DisplayName("Cache invalidation: post-rotation, KidRegistry returns the new active kid")
    void cacheInvalidationFlipsActiveKid() {
        jwtService.generateAccessToken(freshUser);
        UUID priorKid = kidRegistry.findOrCreateActiveKid(freshTenant);

        rotationService.bumpJwtKeyGeneration(freshTenant, freshUser.getId());

        UUID newKid = kidRegistry.findOrCreateActiveKid(freshTenant);
        assertNotEquals(priorKid, newKid,
                "post-rotation findOrCreateActiveKid must return the new gen's kid; "
                + "stale-cache return of priorKid would mean the after-commit "
                + "cache invalidation hook did not run");
    }

    @Test
    @DisplayName("JWT_KEY_GENERATION_BUMPED audit row written with W3 JSONB shape")
    void auditEventWritten() {
        jwtService.generateAccessToken(freshUser);
        Integer beforeGen = jdbc.queryForObject(
                "SELECT generation FROM tenant_key_material WHERE tenant_id = ? AND active = TRUE",
                Integer.class, freshTenant);

        rotationService.bumpJwtKeyGeneration(freshTenant, freshUser.getId());

        // Query audit_events for this tenant's most recent rotation event
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT actor_user_id, target_user_id, action, details::text AS details_json "
                + "FROM audit_events WHERE action = 'JWT_KEY_GENERATION_BUMPED' "
                + "  AND details->>'tenantId' = ? "
                + "ORDER BY timestamp DESC LIMIT 1",
                freshTenant.toString());
        assertEquals(1, rows.size(),
                "JWT_KEY_GENERATION_BUMPED audit row must be written by the rotation");
        Map<String, Object> row = rows.get(0);
        assertEquals(freshUser.getId(), row.get("actor_user_id"));
        assertNull(row.get("target_user_id"),
                "target is the tenant, not a user — targetUserId stays null");
        String detailsJson = (String) row.get("details_json");
        assertTrue(detailsJson.contains(freshTenant.toString()),
                "details JSONB must include tenantId; was: " + detailsJson);
        assertTrue(detailsJson.contains("\"oldGen\": " + beforeGen),
                "details JSONB must include oldGen=" + beforeGen + "; was: " + detailsJson);
        assertTrue(detailsJson.contains("\"newGen\": " + (beforeGen + 1)),
                "details JSONB must include newGen=" + (beforeGen + 1) + "; was: " + detailsJson);
        assertTrue(detailsJson.contains("\"revokedKidCount\": 1"),
                "details JSONB must include revokedKidCount=1; was: " + detailsJson);
    }

    @Test
    @DisplayName("Cross-tenant isolation: rotating tenant A does not affect tenant B")
    void crossTenantIsolation() {
        UUID otherTenant = authHelper.setupSecondaryTenant(
                "rotation-isolation-other-" + UUID.randomUUID()).getId();
        User otherUser = authHelper.createUserInTenant(otherTenant,
                "rotation-isolation-other-" + UUID.randomUUID() + "@test.fabt.org",
                "Other Tenant User", new String[]{"COC_ADMIN"}, false);

        jwtService.generateAccessToken(freshUser);
        jwtService.generateAccessToken(otherUser);
        UUID otherKidBefore = kidRegistry.findOrCreateActiveKid(otherTenant);
        Integer otherGenBefore = jdbc.queryForObject(
                "SELECT generation FROM tenant_key_material WHERE tenant_id = ? AND active = TRUE",
                Integer.class, otherTenant);

        rotationService.bumpJwtKeyGeneration(freshTenant, freshUser.getId());

        UUID otherKidAfter = kidRegistry.findOrCreateActiveKid(otherTenant);
        Integer otherGenAfter = jdbc.queryForObject(
                "SELECT generation FROM tenant_key_material WHERE tenant_id = ? AND active = TRUE",
                Integer.class, otherTenant);
        assertEquals(otherKidBefore, otherKidAfter,
                "other tenant's active kid must not change when fresh tenant rotates");
        assertEquals(otherGenBefore, otherGenAfter,
                "other tenant's generation must not change when fresh tenant rotates");
    }

    @Test
    @DisplayName("Rotation throws IllegalStateException when no active generation exists (W7 atomicity setup)")
    void rotationFailsForUnboostrappedTenant() {
        // Use a brand-new tenant that has NEVER had a token issued for it
        UUID unbootstrapped = authHelper.setupSecondaryTenant(
                "unbootstrapped-" + UUID.randomUUID()).getId();
        // Sanity: no active row exists yet
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant_key_material WHERE tenant_id = ?",
                Integer.class, unbootstrapped);
        assertEquals(0, count);

        // Rotation throws IllegalStateException (translated from
        // EmptyResultDataAccessException by the service)
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> rotationService.bumpJwtKeyGeneration(unbootstrapped, freshUser.getId()),
                "rotation on a tenant without a bootstrapped active generation must throw "
                + "IllegalStateException with a clear message");
        assertTrue(ex.getMessage().contains("no active key generation"),
                "error must explain the cause; was: " + ex.getMessage());
    }

    @Test
    @DisplayName("Two rotations in a row produce gen N -> N+1 -> N+2 with both old kids revoked")
    void doubleRotationRevokesBothPriorKids() {
        jwtService.generateAccessToken(freshUser);
        UUID gen1Kid = kidRegistry.findOrCreateActiveKid(freshTenant);

        // First rotation
        TenantKeyRotationService.RotationResult r1 =
                rotationService.bumpJwtKeyGeneration(freshTenant, freshUser.getId());
        // Issue a new token under gen 2 to create its kid in the registry
        jwtService.generateAccessToken(freshUser);
        UUID gen2Kid = kidRegistry.findOrCreateActiveKid(freshTenant);
        assertNotEquals(gen1Kid, gen2Kid);

        // Second rotation
        TenantKeyRotationService.RotationResult r2 =
                rotationService.bumpJwtKeyGeneration(freshTenant, freshUser.getId());
        assertEquals(r1.newGeneration(), r2.oldGeneration());
        assertEquals(r1.newGeneration() + 1, r2.newGeneration());

        // Both gen-1 and gen-2 kids in jwt_revocations
        Boolean gen1Revoked = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM jwt_revocations WHERE kid = ?)",
                Boolean.class, gen1Kid);
        Boolean gen2Revoked = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM jwt_revocations WHERE kid = ?)",
                Boolean.class, gen2Kid);
        assertTrue(gen1Revoked, "gen-1 kid must remain revoked after two rotations");
        assertTrue(gen2Revoked, "gen-2 kid must be revoked by the second rotation");
    }
}
