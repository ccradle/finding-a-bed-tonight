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
}
