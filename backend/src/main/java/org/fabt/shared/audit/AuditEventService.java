package org.fabt.shared.audit;

import java.util.List;
import java.util.UUID;

import org.fabt.shared.audit.repository.AuditEventRepository;
import org.fabt.shared.config.JsonString;
import org.fabt.shared.web.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;
    private final AuditEventPersister persister;

    public AuditEventService(AuditEventRepository repository, ObjectMapper objectMapper,
                              JdbcTemplate jdbc, AuditEventPersister persister) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.persister = persister;
    }

    @EventListener
    public void onAuditEvent(AuditEventRecord event) {
        try {
            JsonString details = null;
            if (event.details() != null) {
                details = new JsonString(objectMapper.writeValueAsString(event.details()));
            }

            // Tenant isolation: pull tenantId from TenantContext (bound by the
            // publisher's request/batch scope). Phase B D55 three-level lookup:
            //   1. TenantContext.getTenantId() — request/batch scope
            //   2. current_setting('app.tenant_id') — services that took
            //      tenantId as an explicit parameter and did set_config
            //      (e.g. TenantKeyRotationService, KidRegistryService)
            //   3. SYSTEM_TENANT_ID sentinel — with WARN log
            // Row's tenant_id is always populated and the FORCE-RLS policy
            // succeeds. WARN surfaces publishers that forgot to bind.
            UUID tenantId = TenantContext.getTenantId();
            if (tenantId == null) {
                tenantId = tryReadSessionTenantId();
            }
            if (tenantId == null) {
                tenantId = TenantContext.SYSTEM_TENANT_ID;
                log.warn("Audit event published without TenantContext bound: action={}, actor={}, target={}. "
                        + "Row persisted under SYSTEM_TENANT_ID sentinel; investigate publisher for missing "
                        + "TenantContext.runWithContext wrap (Phase B D55).",
                        event.action(), event.actorUserId(), event.targetUserId());
            }

            // Phase B D55 + Bug A/D fix: delegate to a separate Spring bean
            // so the @Transactional proxy engages (self-invocation within
            // this class would bypass it and leave set_config(is_local=true)
            // running in an implicit single-statement tx → reverted before
            // the INSERT → FORCE RLS rejection on orphan paths).
            persister.persist(tenantId, event, details);
            log.debug("Audit event persisted: action={}, actor={}, target={}, tenant={}",
                    event.action(), event.actorUserId(), event.targetUserId(), tenantId);
        } catch (Exception e) {
            log.error("Failed to persist audit event: action={}, error={}",
                    event.action(), e.getMessage());
        }
    }

    /**
     * Second-tier lookup for tenant context: services like
     * {@code TenantKeyRotationService} and {@code KidRegistryService} take
     * tenantId as an explicit parameter and set {@code app.tenant_id} via
     * set_config at the start of their @Transactional method (per Phase B
     * D46 pattern). When such a service publishes an audit event, the
     * ScopedValue-bound TenantContext may not be set, but the session's
     * {@code app.tenant_id} IS. Read it here.
     *
     * <p>Returns null if unset, empty, or malformed — in which case the
     * outer fallback to SYSTEM_TENANT_ID kicks in.
     */
    private UUID tryReadSessionTenantId() {
        try {
            String raw = jdbc.queryForObject(
                    "SELECT current_setting('app.tenant_id', true)", String.class);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return UUID.fromString(raw);
        } catch (Exception ignore) {
            return null;
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
