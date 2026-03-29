package org.fabt.shared.audit.repository;

import java.util.List;

import org.fabt.shared.audit.AuditEventEntity;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

public interface AuditEventRepository extends CrudRepository<AuditEventEntity, UUID> {

    @Query("SELECT * FROM audit_events WHERE target_user_id = :targetUserId ORDER BY timestamp DESC LIMIT 100")
    List<AuditEventEntity> findByTargetUserId(UUID targetUserId);
}
