package org.fabt.notification.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.fabt.notification.domain.Notification;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface NotificationRepository extends CrudRepository<Notification, UUID> {

    @Query("SELECT * FROM notification WHERE recipient_id = :recipientId AND read_at IS NULL ORDER BY CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'ACTION_REQUIRED' THEN 1 ELSE 2 END, created_at DESC LIMIT :limit OFFSET :offset")
    List<Notification> findUnreadByRecipientId(@Param("recipientId") UUID recipientId, @Param("limit") int limit, @Param("offset") int offset);

    @Query("SELECT * FROM notification WHERE recipient_id = :recipientId ORDER BY CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'ACTION_REQUIRED' THEN 1 ELSE 2 END, created_at DESC LIMIT :limit OFFSET :offset")
    List<Notification> findByRecipientId(@Param("recipientId") UUID recipientId, @Param("limit") int limit, @Param("offset") int offset);

    @Query("SELECT COUNT(*) FROM notification WHERE recipient_id = :recipientId AND read_at IS NULL")
    int countUnreadByRecipientId(@Param("recipientId") UUID recipientId);

    @Modifying
    @Transactional
    @Query("UPDATE notification SET read_at = NOW() WHERE id = :id AND recipient_id = :recipientId AND read_at IS NULL")
    int markRead(@Param("id") UUID id, @Param("recipientId") UUID recipientId);

    @Modifying
    @Transactional
    @Query("UPDATE notification SET acted_at = NOW(), read_at = COALESCE(read_at, NOW()) WHERE id = :id AND recipient_id = :recipientId")
    int markActed(@Param("id") UUID id, @Param("recipientId") UUID recipientId);

    @Modifying
    @Transactional
    @Query("UPDATE notification SET read_at = NOW() WHERE recipient_id = :recipientId AND read_at IS NULL AND severity != 'CRITICAL'")
    int markAllRead(@Param("recipientId") UUID recipientId);

    @Modifying
    @Transactional
    @Query("DELETE FROM notification WHERE read_at IS NOT NULL AND created_at < :cutoff AND severity != 'CRITICAL'")
    int deleteOldRead(@Param("cutoff") Timestamp cutoff);

    @Query("SELECT COUNT(*) > 0 FROM notification WHERE type = :type AND payload ->> 'referralId' = :referralId")
    boolean existsByTypeAndReferralId(@Param("type") String type, @Param("referralId") String referralId);

    @Modifying
    @Transactional
    @Query("INSERT INTO notification (tenant_id, recipient_id, type, severity, payload, created_at) SELECT :tenantId, unnest(CAST(:recipientIds AS uuid[])), :type, :severity, CAST(:payload AS jsonb), NOW()")
    int batchInsert(@Param("tenantId") UUID tenantId,
                     @Param("recipientIds") UUID[] recipientIds,
                     @Param("type") String type,
                     @Param("severity") String severity,
                     @Param("payload") String payload);

    @Modifying
    @Transactional
    @Query("DELETE FROM notification WHERE read_at IS NOT NULL AND created_at < :cutoff")
    int deleteOldReadIncludingCritical(@Param("cutoff") Timestamp cutoff);
}
