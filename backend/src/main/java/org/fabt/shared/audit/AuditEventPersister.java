package org.fabt.shared.audit;

import java.util.UUID;

import org.fabt.shared.audit.repository.AuditEventRepository;
import org.fabt.shared.config.JsonString;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists audit events under an explicit tenant binding.
 *
 * <p><b>Why this exists.</b> {@link AuditEventService} is the {@link
 * org.springframework.context.event.EventListener} entry point. It determines
 * which tenant the audit row belongs to (via its three-level lookup —
 * TenantContext → session {@code app.tenant_id} → SYSTEM_TENANT_ID sentinel)
 * and then delegates here.
 *
 * <p>Extracting this to a separate Spring bean — instead of inline inside
 * {@code AuditEventService.onAuditEvent} — is load-bearing for Phase B
 * correctness. The write path has to run inside a real transaction so that:
 *
 * <ol>
 *   <li>{@code SELECT set_config('app.tenant_id', ?, true)} with
 *       {@code is_local=true} actually scopes the GUC override to the tx
 *       boundary, and</li>
 *   <li>the subsequent {@code repository.save(entity)} sees the bound
 *       {@code app.tenant_id} when the FORCE-RLS policy on
 *       {@code audit_events} evaluates {@code tenant_id =
 *       fabt_current_tenant_id()}.</li>
 * </ol>
 *
 * <p>If this method lived on {@code AuditEventService} and was called via
 * {@code this.persist(...)} from its own {@code @EventListener}, Spring's
 * AOP proxy would be bypassed (self-invocation) and the {@code @Transactional}
 * annotation would be inert. Under autoCommit=true with no outer publisher
 * transaction (orphan audit paths: pre-auth flows, scheduled jobs that don't
 * wrap in TransactionTemplate), the {@code set_config(is_local=true)} would
 * apply to the implicit single-statement tx wrapping the SET, revert at
 * that statement's end, and the subsequent INSERT would run with
 * {@code app.tenant_id=''} → FORCE RLS rejects with error 42501.
 *
 * <p>Cross-bean invocation from {@code AuditEventService} hits the Spring
 * proxy, which opens a real transaction (PROPAGATION_REQUIRED), binds the
 * GUC inside it, writes the row, commits. Under an existing publisher tx
 * (e.g., {@code TenantKeyRotationService.bumpJwtKeyGeneration}), REQUIRED
 * joins the outer tx — preserving the pre-Phase-B "audit row rolls back
 * with the publisher" contract.
 *
 * <p><b>Scope.</b> Package-private: only {@code AuditEventService} calls
 * this. Audit persistence is tamper-sensitive; direct access from other
 * packages must go through {@code AuditEventService}.
 */
@Service
class AuditEventPersister {

    private final AuditEventRepository repository;
    private final JdbcTemplate jdbc;

    AuditEventPersister(AuditEventRepository repository, JdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    /**
     * Binds {@code app.tenant_id} to {@code tenantId} for this transaction,
     * then persists the audit event.
     *
     * <p>{@code PROPAGATION_REQUIRED} preserves the "audit joins publisher tx"
     * contract — a publisher rollback rolls the audit row back too. When
     * there is no publisher tx (orphan paths), REQUIRED creates a fresh one,
     * which is exactly what we need for the {@code set_config(is_local=true)}
     * to behave correctly.
     *
     * <p>Called only from {@link AuditEventService#onAuditEvent}. Package-
     * private to enforce that.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    void persist(UUID tenantId, AuditEventRecord event, JsonString details) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantId.toString());
        AuditEventEntity entity = new AuditEventEntity(
                tenantId,
                event.actorUserId(),
                event.targetUserId(),
                event.action(),
                details,
                event.ipAddress());
        repository.save(entity);
    }
}
