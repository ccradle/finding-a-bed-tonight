package org.fabt.shared.security;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code tenant_key_material} + {@code kid_to_tenant_key} write
 * paths. Uses raw {@link JdbcTemplate} per A3 D20 + warroom E2 — the
 * INSERT-ON-CONFLICT-DO-NOTHING pattern doesn't map cleanly to Spring
 * Data JDBC repositories, and explicit SQL stays auditable.
 *
 * <p>Two responsibilities:
 *
 * <ol>
 *   <li><b>Lazy registration</b> on first encrypt for a tenant. If the
 *       tenant has no active generation in {@code tenant_key_material},
 *       create one with {@code generation = 1, active = TRUE}. If no
 *       kid exists for that generation, generate a fresh
 *       {@link UUID#randomUUID()} and register it. Concurrent
 *       first-encrypts converge on the same kid via the V61 UNIQUE
 *       constraint on {@code (tenant_id, generation)}.</li>
 *   <li><b>kid resolution</b> on decrypt. Resolves an opaque kid back
 *       to its (tenant, generation) pair. Used by
 *       {@link SecretEncryptionService#decryptForTenant} to verify the
 *       caller's expected tenantId matches the kid's actual tenant.</li>
 * </ol>
 *
 * <p>Note: this service performs INSERTs into tables that V61 does not
 * yet have RLS on (deferred to Phase B task 3.4 per the V61 header
 * comment). The interim defenses are (a) opaque random kids and (b) the
 * cross-tenant check on decrypt. Adding an RLS layer will require
 * changing the read path to handle the chicken-and-egg with TenantContext
 * binding — out of scope for A3.
 */
@Service
public class KidRegistryService {

    private final JdbcTemplate jdbc;

    public KidRegistryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the kid for the tenant's active generation. Lazily creates
     * both the tenant_key_material row and the kid_to_tenant_key row on
     * first call. Idempotent — second caller for the same tenant
     * receives the same kid.
     */
    @Transactional
    public UUID findOrCreateActiveKid(UUID tenantId) {
        ensureActiveGeneration(tenantId);
        int activeGeneration = getActiveGeneration(tenantId);
        return findOrCreateKid(tenantId, activeGeneration);
    }

    /**
     * Resolves a kid back to its (tenant, generation). Throws on unknown
     * kid (caller decides whether that's a hostile probe or a legitimate
     * stale ciphertext that lost its registry entry — likely the former).
     */
    @Transactional(readOnly = true)
    public KidResolution resolveKid(UUID kid) {
        try {
            return jdbc.queryForObject(
                    "SELECT kid, tenant_id, generation FROM kid_to_tenant_key WHERE kid = ?",
                    (rs, rowNum) -> new KidResolution(
                            (UUID) rs.getObject("kid"),
                            (UUID) rs.getObject("tenant_id"),
                            rs.getInt("generation")),
                    kid);
        } catch (EmptyResultDataAccessException notFound) {
            throw new NoSuchElementException("kid not registered: " + kid);
        }
    }

    private void ensureActiveGeneration(UUID tenantId) {
        // PK conflict on (tenant_id, generation) absorbs the race; on success
        // the new row has active=TRUE. Partial unique index
        // tenant_key_material_active_per_tenant prevents two simultaneous
        // active rows from coexisting per tenant.
        jdbc.update(
                "INSERT INTO tenant_key_material (tenant_id, generation, active) "
                + "VALUES (?, 1, TRUE) "
                + "ON CONFLICT (tenant_id, generation) DO NOTHING",
                tenantId);
    }

    private int getActiveGeneration(UUID tenantId) {
        Integer generation = jdbc.queryForObject(
                "SELECT generation FROM tenant_key_material "
                + "WHERE tenant_id = ? AND active = TRUE",
                Integer.class, tenantId);
        if (generation == null) {
            throw new IllegalStateException(
                    "tenant_key_material has no active generation for tenant " + tenantId
                    + " — ensureActiveGeneration should have created one");
        }
        return generation;
    }

    private UUID findOrCreateKid(UUID tenantId, int generation) {
        // Optimistic read first — the common case post-bootstrap
        List<UUID> existing = jdbc.queryForList(
                "SELECT kid FROM kid_to_tenant_key WHERE tenant_id = ? AND generation = ?",
                UUID.class, tenantId, generation);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        // Race-safe insert. The UNIQUE (tenant_id, generation) constraint
        // (V61 amendment) is what makes ON CONFLICT correct under
        // concurrent first-encrypts.
        UUID newKid = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO kid_to_tenant_key (kid, tenant_id, generation) "
                + "VALUES (?, ?, ?) "
                + "ON CONFLICT (tenant_id, generation) DO NOTHING",
                newKid, tenantId, generation);

        // Re-query — if our insert won, we get newKid; if a concurrent
        // thread won, we get theirs. Either way, exactly one kid is
        // registered for this (tenant, generation).
        return jdbc.queryForObject(
                "SELECT kid FROM kid_to_tenant_key WHERE tenant_id = ? AND generation = ?",
                UUID.class, tenantId, generation);
    }

    /** Result of a kid lookup. */
    public record KidResolution(UUID kid, UUID tenantId, int generation) {}
}
