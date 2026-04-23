package org.fabt.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.fabt.auth.domain.ApiKey;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ApiKeyRepository extends CrudRepository<ApiKey, UUID> {

    @Query("SELECT * FROM api_key WHERE key_hash = :keyHash AND active = true")
    Optional<ApiKey> findByKeyHashAndActiveTrue(@Param("keyHash") String keyHash);

    @Query("SELECT * FROM api_key WHERE old_key_hash = :keyHash AND active = true AND old_key_expires_at > NOW()")
    Optional<ApiKey> findByOldKeyHashWithinGracePeriod(@Param("keyHash") String keyHash);

    @Query("SELECT * FROM api_key WHERE old_key_expires_at IS NOT NULL AND old_key_expires_at < NOW() AND active = true")
    List<ApiKey> findExpiredGracePeriodKeys();

    List<ApiKey> findByTenantId(UUID tenantId);

    /**
     * Tenant-scoped single-key lookup for state-mutating paths (rotate,
     * deactivate). Returns empty when the {@code id} exists but belongs
     * to a different tenant — callers map empty to 404 (not 403) to avoid
     * existence leak. See {@code cross-tenant-isolation-audit} design
     * decisions D1 and D3.
     */
    @Query("SELECT * FROM api_key WHERE id = :id AND tenant_id = :tenantId")
    Optional<ApiKey> findByIdAndTenantId(@Param("id") UUID id,
                                          @Param("tenantId") UUID tenantId);

    /**
     * Phase F §D3 active-state guard. Returns the API key only if the owning tenant
     * is ACTIVE; a row belonging to a SUSPENDED/OFFBOARDING/ARCHIVED/DELETED tenant
     * appears as empty (caller maps to 404 — no existence leak). This is the
     * preferred read-path method for request-bound operations; use the plain
     * {@link #findByIdAndTenantId} only when the caller is annotated
     * {@link org.fabt.shared.security.TenantInternal} (e.g. the lifecycle service
     * itself, batch sweeps).
     */
    @Query("SELECT ak.* FROM api_key ak "
         + "INNER JOIN tenant t ON t.id = ak.tenant_id "
         + "WHERE ak.id = :id AND ak.tenant_id = :tenantId AND t.state = 'ACTIVE'")
    Optional<ApiKey> findByIdAndActiveTenantId(@Param("id") UUID id,
                                                @Param("tenantId") UUID tenantId);
}
