package org.fabt.shared.audit;

import java.util.List;
import java.util.UUID;

import org.fabt.shared.audit.repository.AuditEventRepository;
import org.fabt.shared.config.JsonString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Persists audit events asynchronously. Listens for AuditEventRecord
 * published via Spring ApplicationEventPublisher from UserService
 * (and future services that need audit logging).
 */
@Service
public class AuditEventService {

    private static final Logger log = LoggerFactory.getLogger(AuditEventService.class);

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;

    public AuditEventService(AuditEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void onAuditEvent(AuditEventRecord event) {
        try {
            JsonString details = null;
            if (event.details() != null) {
                details = new JsonString(objectMapper.writeValueAsString(event.details()));
            }

            AuditEventEntity entity = new AuditEventEntity(
                    event.actorUserId(),
                    event.targetUserId(),
                    event.action(),
                    details,
                    event.ipAddress());

            repository.save(entity);
            log.debug("Audit event persisted: action={}, actor={}, target={}",
                    event.action(), event.actorUserId(), event.targetUserId());
        } catch (Exception e) {
            log.error("Failed to persist audit event: action={}, error={}",
                    event.action(), e.getMessage());
        }
    }

    public List<AuditEventEntity> findByTargetUserId(UUID targetUserId) {
        return repository.findByTargetUserId(targetUserId);
    }
}
