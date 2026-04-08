package org.fabt.subscription.repository;

import java.util.List;
import java.util.UUID;

import org.fabt.subscription.domain.WebhookDeliveryLog;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface WebhookDeliveryLogRepository extends CrudRepository<WebhookDeliveryLog, UUID> {

    @Query("SELECT * FROM webhook_delivery_log WHERE subscription_id = :subId ORDER BY attempted_at DESC LIMIT 20")
    List<WebhookDeliveryLog> findRecentBySubscriptionId(@Param("subId") UUID subscriptionId);

    @Modifying
    @Query("DELETE FROM webhook_delivery_log WHERE attempted_at < NOW() - INTERVAL '14 days'")
    int deleteOlderThan14Days();
}
