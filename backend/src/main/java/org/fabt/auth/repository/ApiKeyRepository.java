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
}
