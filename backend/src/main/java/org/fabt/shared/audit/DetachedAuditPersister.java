package org.fabt.shared.audit;

import java.util.UUID;

import org.fabt.shared.audit.repository.AuditEventRepository;
import org.fabt.shared.config.JsonString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Persists audit events under {@code PROPAGATION_REQUIRES_NEW} — sibling to
 * {@link AuditEventPersister}'s {@code REQUIRED} path.
 *
 * <p><b>Why this exists (Marcus Webb lens, Phase C task 4.1 warroom, design-c
 * D-C-9).</b> The default event-bus audit path ({@code ApplicationEventPublisher}
 * → {@link AuditEventService#onAuditEvent} → {@link AuditEventPersister} with
 * {@code PROPAGATION_REQUIRED}) joins the caller's transaction. If the caller
 * rolls back, the audit row rolls back with it. For most audits this is the
 * correct contract: an action that rolled back shouldn't audit as having
 * happened.
 *
 * <p>For security-evidence audits — specifically
 * {@link AuditEventTypes#CROSS_TENANT_CACHE_READ} emitted from
 * {@code TenantScopedCacheService.get} when on-read tenant verification detects
 * a stamp mismatch — this rollback-coupling is an anti-feature. An attacker who
 * triggers a cross-tenant read in a transactional endpoint and relies on the
 * subsequent {@code IllegalStateException} to roll the caller back would erase
 * the one audit signal proving the attempt happened.
 *
 * <p>{@code REQUIRES_NEW} cuts the audit row loose — it commits in its own
 * transaction, independent of the caller's fate.
 *
 * <p><b>Scope.</b> Reserved for security-evidence audit events that must
 * survive caller rollback. Today's call sites:
 * <ul>
 *   <li>{@code TenantScopedCacheService.get} → {@code CROSS_TENANT_CACHE_READ}</li>
 * </ul>
 *
 * <p>New call sites MUST be reviewed at the PR gate — adopt this path ONLY when
 * the audit is load-bearing for security forensics. The event-bus path remains
 * correct for the other 99% of audit rows.
 *
 * <p>Marked {@code public} (sibling {@link AuditEventPersister} is package-private)
 * so non-{@code shared.audit} beans — specifically the cache wrapper — can call it
 * directly without routing through {@link AuditEventService}'s event listener.
 */
@Service
public class DetachedAuditPersister {

    private static final Logger log = LoggerFactory.getLogger(DetachedAuditPersister.class);

    private final AuditEventRepository repository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DetachedAuditPersister(AuditEventRepository repository,
                                   JdbcTemplate jdbc,
                                   ObjectMapper objectMapper) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Binds {@code app.tenant_id} for this {@code REQUIRES_NEW} transaction,
     * serialises the event's {@code details} object into a {@link JsonString},
     * and persists an {@link AuditEventEntity}.
     *
     * <p>Independent of any caller transaction. Caller rollbacks do NOT affect
     * the persisted row.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistDetached(UUID tenantId, AuditEventRecord event) {
        try {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, tenantId.toString());
            JsonString details = null;
            if (event.details() != null) {
                details = new JsonString(objectMapper.writeValueAsString(event.details()));
            }
            AuditEventEntity entity = new AuditEventEntity(
                    tenantId,
                    event.actorUserId(),
                    event.targetUserId(),
                    event.action(),
                    details,
                    event.ipAddress());
            repository.save(entity);
        } catch (Exception e) {
            // Swallow + log: the security-evidence contract is best-effort; the
            // caller's IllegalStateException (the reason we're here) has already
            // fired and will propagate. We log loudly so an operator can follow
            // up, but do NOT re-throw (that would mask the original ISE).
            log.error("DetachedAuditPersister failed for action={} tenant={}: {}",
                    event.action(), tenantId, e.getMessage(), e);
        }
    }
}
