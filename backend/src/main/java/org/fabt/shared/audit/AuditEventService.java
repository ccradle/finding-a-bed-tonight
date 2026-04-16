package org.fabt.shared.audit;

import java.util.List;
import java.util.UUID;

import org.fabt.shared.audit.repository.AuditEventRepository;
import org.fabt.shared.config.JsonString;
import org.fabt.shared.web.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Persists audit events on the publishing thread (synchronous listener). Listens for
 * {@link AuditEventRecord} published via {@link org.springframework.context.ApplicationEventPublisher}
 * from {@code UserService}, {@code ReferralTokenController}, and other writers.
 *
 * <p>Failures are logged and swallowed so the originating request still completes — an explicit
 * operational trade-off (Casey Drummond): monitor {@code Failed to persist audit event} in logs.</p>
 *
 * <p>Tenant isolation (cross-tenant-isolation-audit Phase 2.12): every audit row carries
 * {@code tenant_id} sourced from {@link TenantContext#getTenantId()} at listener time.
 * Because {@code @EventListener} is synchronous (not {@code @TransactionalEventListener}),
 * the ScopedValue-bound {@code TenantContext} of the publisher thread is still in scope
 * when this listener fires. Queries via {@link #findByTargetUserId} filter on the caller's
 * tenant, preventing cross-tenant audit-history reads (the Casey VAWA audit-integrity concern).</p>
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

            // Tenant isolation: pull tenantId from TenantContext (bound by the
            // publisher's request/batch scope). null is defensible for
            // historical/orphan cases but we log WARN so operators can catch
            // publisher sites that forgot to wrap in runWithContext.
            UUID tenantId = TenantContext.getTenantId();
            if (tenantId == null) {
                log.warn("Audit event published without TenantContext bound: action={}, actor={}, target={}. "
                        + "Row will be persisted with tenant_id=NULL; investigate publisher for missing "
                        + "TenantContext.runWithContext wrap.",
                        event.action(), event.actorUserId(), event.targetUserId());
            }

            AuditEventEntity entity = new AuditEventEntity(
                    tenantId,
                    event.actorUserId(),
                    event.targetUserId(),
                    event.action(),
                    details,
                    event.ipAddress());

            repository.save(entity);
            log.debug("Audit event persisted: action={}, actor={}, target={}, tenant={}",
                    event.action(), event.actorUserId(), event.targetUserId(), tenantId);
        } catch (Exception e) {
            log.error("Failed to persist audit event: action={}, error={}",
                    event.action(), e.getMessage());
        }
    }

    /**
     * Tenant-scoped audit query. Pulls {@code tenantId} from {@link TenantContext}
     * and delegates to {@link AuditEventRepository#findByTargetUserIdAndTenantId}.
     * A cross-tenant probe (valid UUID belonging to another tenant) returns empty.
     * See D3 — we use empty-list, not 404, because this is a LIST endpoint and
     * "no matching rows" is the natural shape for both cross-tenant and not-found-anywhere.
     */
    public List<AuditEventEntity> findByTargetUserId(UUID targetUserId) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            log.warn("Audit query attempted without TenantContext bound; returning empty list for targetUserId={}",
                    targetUserId);
            return List.of();
        }
        return repository.findByTargetUserIdAndTenantId(targetUserId, tenantId);
    }
}
