package org.fabt.subscription.repository;

import java.util.List;
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
}
