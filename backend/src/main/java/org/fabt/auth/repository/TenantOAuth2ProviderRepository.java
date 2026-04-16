package org.fabt.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.fabt.auth.domain.TenantOAuth2Provider;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface TenantOAuth2ProviderRepository extends CrudRepository<TenantOAuth2Provider, UUID> {

    @Query("SELECT * FROM tenant_oauth2_provider WHERE tenant_id = :tenantId AND enabled = true")
    List<TenantOAuth2Provider> findByTenantIdAndEnabledTrue(@Param("tenantId") UUID tenantId);

    @Query("SELECT * FROM tenant_oauth2_provider WHERE tenant_id = :tenantId AND provider_name = :providerName")
    Optional<TenantOAuth2Provider> findByTenantIdAndProviderName(@Param("tenantId") UUID tenantId,
                                                                  @Param("providerName") String providerName);

    @Query("SELECT * FROM tenant_oauth2_provider WHERE tenant_id = :tenantId")
    List<TenantOAuth2Provider> findByTenantId(@Param("tenantId") UUID tenantId);

    /**
     * Tenant-scoped single-provider lookup for state-mutating paths.
     * Returns empty when the {@code id} exists but belongs to a different
     * tenant — callers map empty to 404 (not 403) to avoid existence leak.
     * See {@code cross-tenant-isolation-audit} design decisions D1 and D3.
     */
    @Query("SELECT * FROM tenant_oauth2_provider WHERE id = :id AND tenant_id = :tenantId")
    Optional<TenantOAuth2Provider> findByIdAndTenantId(@Param("id") UUID id,
                                                        @Param("tenantId") UUID tenantId);
}
