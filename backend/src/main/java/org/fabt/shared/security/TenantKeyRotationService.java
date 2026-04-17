package org.fabt.shared.security;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.shared.audit.AuditEventRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Per-tenant JWT key rotation per A4 D27 + warroom W3 + W4.
 *
 * <p>{@link #bumpJwtKeyGeneration(UUID, UUID)} atomically:
 *
 * <ol>
 *   <li>Marks the tenant's current generation row in
 *       {@code tenant_key_material} as inactive + sets {@code rotated_at}</li>
 *   <li>Inserts the next-generation row as active (W4: ON CONFLICT DO
 *       NOTHING for retry idempotency)</li>
 *   <li>Bulk-adds every kid of the prior generation to
 *       {@code jwt_revocations} with {@code expires_at = NOW() + 7 days}
 *       (W5: conservative ceiling matching refresh-token max lifetime)</li>
 *   <li>Bumps {@code tenant.jwt_key_generation}</li>
 *   <li>Publishes a {@code JWT_KEY_GENERATION_BUMPED} audit event with
 *       {@code {tenantId, oldGen, newGen, actorUserId, revokedKidCount}}
 *       (W3 — Casey's "operator demonstrates we rotated keys per
 *       schedule via audit query" requirement)</li>
 *   <li>Registers an after-commit hook to invalidate
 *       {@link KidRegistryService#invalidateTenantActiveKid} (so post-
 *       rotation encrypts use the new kid) and
 *       {@link RevokedKidCache#invalidateAll} (so revoked kids
 *       propagate across replicas immediately, not after the 1-min
 *       cache TTL)</li>
 * </ol>
 *
 * <p>All DB work + audit publish are inside one {@code @Transactional}.
 * Audit publish via {@link ApplicationEventPublisher} routes through
 * {@code AuditEventService}'s synchronous listener — the
 * {@code audit_events} INSERT joins the same transaction. If anything
 * throws, EVERYTHING rolls back (including the audit row). Cache
 * invalidation is registered as an {@link TransactionSynchronization#afterCommit}
 * hook so a rolled-back rotation never invalidates downstream caches
 * (would cause a tiny window of stale-cache-vs-stale-DB confusion).
 *
 * <p>This service is the production callsite for A3's
 * {@link KidRegistryService#invalidateTenantActiveKid} +
 * {@link RevokedKidCache#invalidateAll}; the test added in
 * {@code KidRegistryServiceCacheInvalidationTest} validates the
 * mechanism — this class wires it to the real lifecycle.
 */
@Service
public class TenantKeyRotationService {

    private static final Logger log = LoggerFactory.getLogger(TenantKeyRotationService.class);

    private final JdbcTemplate jdbc;
    private final KidRegistryService kidRegistryService;
    private final RevokedKidCache revokedKidCache;
    private final ApplicationEventPublisher eventPublisher;

    public TenantKeyRotationService(JdbcTemplate jdbc,
                                     KidRegistryService kidRegistryService,
                                     RevokedKidCache revokedKidCache,
                                     ApplicationEventPublisher eventPublisher) {
        this.jdbc = jdbc;
        this.kidRegistryService = kidRegistryService;
        this.revokedKidCache = revokedKidCache;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Rotates the tenant's JWT signing key. Idempotency: re-running on a
     * tenant that's already been bumped to the next gen is a no-op
     * thanks to W4's {@code ON CONFLICT DO NOTHING}; the caller still
     * gets a successful return with {@code newGen == oldGen + 1} as
     * recorded by step 1's read.
     *
     * @param tenantId      the tenant whose key to rotate
     * @param actorUserId   the platform admin who triggered rotation
     *                      (recorded in the audit event); may be null for
     *                      system-initiated rotation (Phase F suspend)
     * @return summary of the rotation
     */
    @Transactional
    public RotationResult bumpJwtKeyGeneration(UUID tenantId, UUID actorUserId) {
        // 1. Snapshot current active generation. queryForObject throws
        //    EmptyResultDataAccessException when there's no row — translate
        //    to a clearer IllegalStateException so callers get an actionable
        //    message instead of a Spring DAO exception.
        Integer currentGen;
        try {
            currentGen = jdbc.queryForObject(
                    "SELECT generation FROM tenant_key_material "
                    + "WHERE tenant_id = ? AND active = TRUE",
                    Integer.class, tenantId);
        } catch (org.springframework.dao.EmptyResultDataAccessException notBootstrapped) {
            throw new IllegalStateException(
                    "Tenant " + tenantId + " has no active key generation; "
                    + "cannot rotate (call findOrCreateActiveKid first to bootstrap)",
                    notBootstrapped);
        }
        if (currentGen == null) {
            // Defensive — queryForObject normally throws on no-rows, but
            // some JDBC drivers may return null for a NULL column value.
            throw new IllegalStateException(
                    "Tenant " + tenantId + " has no active key generation");
        }

        // 2. Read all kids of the current generation BEFORE updating —
        //    used for both the bulk INSERT into jwt_revocations AND the
        //    after-commit cache invalidation.
        List<UUID> kidsToRevoke = jdbc.queryForList(
                "SELECT kid FROM kid_to_tenant_key "
                + "WHERE tenant_id = ? AND generation = ?",
                UUID.class, tenantId, currentGen);

        // 3. Mark current gen inactive
        jdbc.update(
                "UPDATE tenant_key_material SET active = FALSE, rotated_at = NOW() "
                + "WHERE tenant_id = ? AND generation = ?",
                tenantId, currentGen);

        // 4. Insert next gen (W4 — ON CONFLICT DO NOTHING for retry idempotency).
        //    Without target: absorbs PK conflict on (tenant_id, gen+1) AND the
        //    partial unique on (tenant_id) WHERE active=TRUE.
        int nextGen = currentGen + 1;
        jdbc.update(
                "INSERT INTO tenant_key_material (tenant_id, generation, active) "
                + "VALUES (?, ?, TRUE) ON CONFLICT DO NOTHING",
                tenantId, nextGen);

        // 5. Bulk-add prior-gen kids to jwt_revocations
        //    (W5: 7-day ceiling = refresh-token max lifetime)
        if (!kidsToRevoke.isEmpty()) {
            jdbc.update(
                    "INSERT INTO jwt_revocations (kid, expires_at) "
                    + "SELECT kid, NOW() + INTERVAL '7 days' "
                    + "FROM kid_to_tenant_key WHERE tenant_id = ? AND generation = ? "
                    + "ON CONFLICT (kid) DO NOTHING",
                    tenantId, currentGen);
        }

        // 6. Bump tenant.jwt_key_generation column
        jdbc.update("UPDATE tenant SET jwt_key_generation = ? WHERE id = ?",
                nextGen, tenantId);

        // 7. Publish JWT_KEY_GENERATION_BUMPED audit event (W3)
        //    AuditEventService listens synchronously; the audit_events
        //    INSERT joins this same transaction so a rollback rolls
        //    audit too.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tenantId", tenantId.toString());
        details.put("oldGen", currentGen);
        details.put("newGen", nextGen);
        details.put("actorUserId", actorUserId == null ? "null" : actorUserId.toString());
        details.put("revokedKidCount", kidsToRevoke.size());
        eventPublisher.publishEvent(new AuditEventRecord(
                actorUserId, null, "JWT_KEY_GENERATION_BUMPED", details, null));

        // 8. Register after-commit cache invalidation. Runs ONLY if the
        //    transaction commits — a rollback skips it, preventing the
        //    stale-cache-vs-stale-DB race that would happen if
        //    invalidation ran inside the tx.
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        kidRegistryService.invalidateTenantActiveKid(tenantId);
                        revokedKidCache.invalidateAll(kidsToRevoke);
                        log.info("JWT key rotation committed for tenant {} ({} -> {}); "
                                + "evicted {} revoked-kid cache entries",
                                tenantId, currentGen, nextGen, kidsToRevoke.size());
                    }
                });

        return new RotationResult(tenantId, currentGen, nextGen, kidsToRevoke.size(), Instant.now());
    }

    /**
     * Result of a rotation. Carries the counts the admin endpoint returns
     * to the caller + the timestamps for audit-trail correlation.
     */
    public record RotationResult(UUID tenantId, int oldGeneration, int newGeneration,
                                  int revokedKidCount, Instant rotatedAt) {}
}
