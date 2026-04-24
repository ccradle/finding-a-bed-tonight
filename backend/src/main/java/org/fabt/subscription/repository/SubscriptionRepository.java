package org.fabt.subscription.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.fabt.subscription.domain.Subscription;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface SubscriptionRepository extends CrudRepository<Subscription, UUID> {

    @Query("SELECT * FROM subscription WHERE tenant_id = :tenantId ORDER BY created_at DESC")
    List<Subscription> findByTenantId(@Param("tenantId") UUID tenantId);

    @Query("SELECT * FROM subscription WHERE event_type = :eventType AND status = 'ACTIVE'")
    List<Subscription> findActiveByEventType(@Param("eventType") String eventType);

    /**
     * Tenant-scoped single-subscription lookup for state-mutating paths
     * (delete). Returns empty when the {@code id} exists but belongs to a
     * different tenant — callers map empty to 404 (not 403) to avoid
     * existence leak. See {@code cross-tenant-isolation-audit} design
     * decisions D1 and D3.
     */
    @Query("SELECT * FROM subscription WHERE id = :id AND tenant_id = :tenantId")
    Optional<Subscription> findByIdAndTenantId(@Param("id") UUID id,
                                                @Param("tenantId") UUID tenantId);

    /**
     * Phase F §D3 active-state guard. Returns the subscription only if the owning
     * tenant is ACTIVE; rows for non-ACTIVE tenants appear as empty (caller maps to
     * 404 — no existence leak). Preferred over {@link #findByIdAndTenantId} for
     * request-bound paths; the plain variant is for {@code @TenantInternal}
     * call sites (lifecycle service, batch sweeps).
     */
    @Query("SELECT s.* FROM subscription s "
         + "INNER JOIN tenant t ON t.id = s.tenant_id "
         + "WHERE s.id = :id AND s.tenant_id = :tenantId AND t.state = 'ACTIVE'")
    Optional<Subscription> findByIdAndActiveTenantId(@Param("id") UUID id,
                                                      @Param("tenantId") UUID tenantId);
}
