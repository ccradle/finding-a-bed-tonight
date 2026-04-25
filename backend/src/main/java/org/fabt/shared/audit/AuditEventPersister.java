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
    private final AuditChainHasher chainHasher;

    AuditEventPersister(AuditEventRepository repository, JdbcTemplate jdbc,
                        AuditChainHasher chainHasher) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.chainHasher = chainHasher;
    }

    /**
     * Binds {@code app.tenant_id} to {@code tenantId} for this transaction,
     * then persists the audit event with Phase G-1 chain hashes stamped in.
     *
     * <p>{@code PROPAGATION_REQUIRED} preserves the "audit joins publisher tx"
     * contract — a publisher rollback rolls the audit row back too. When
     * there is no publisher tx (orphan paths), REQUIRED creates a fresh one,
     * which is exactly what we need for the {@code set_config(is_local=true)}
     * to behave correctly.
     *
     * <p><b>Phase G-1 chain hashing:</b> {@link AuditChainHasher#computeHashes}
     * runs BEFORE {@code repository.save}, reads
     * {@code tenant_audit_chain_head.last_hash} under a row lock, and
     * returns the row's {@code prev_hash} + {@code row_hash}.
     * {@link AuditChainHasher#advanceChainHead} runs AFTER save (needs the
     * DB-assigned row id) and bumps the chain head. Both operations run in
     * this method's REQUIRED transaction — the audit row INSERT, the chain
     * head UPDATE, and the publisher's work all commit or roll back together.
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
                event.action() == null ? null : event.action().name(),
                details,
                event.ipAddress());

        AuditChainHasher.HashedRow hashed = chainHasher.computeHashes(tenantId, entity);
        entity.setPrevHash(hashed.prevHash());
        entity.setRowHash(hashed.rowHash());

        repository.save(entity);

        chainHasher.advanceChainHead(tenantId, entity.getId(), hashed);
    }

    /**
     * Phase G-4.3 entry point — persists an audit row with a CALLER-SUPPLIED
     * UUID. Used by {@code PlatformAdminLogger} aspect (and the
     * {@code logLockout} direct-write hook) so the audit row id can be
     * pre-generated and embedded as the {@code audit_event_id} foreign-ish
     * key in the companion {@code platform_admin_access_log} row inside the
     * same transaction (Decision 11 — pre-generated UUIDs unblock the
     * double-write ordering).
     *
     * <p>Bypasses Spring Data JDBC's {@code repository.save(entity)} —
     * Spring Data JDBC interprets a non-null {@code @Id} as "this is an
     * existing row, do an UPDATE" without an explicit {@code Persistable}
     * implementation, which is the wrong semantics here. Raw
     * {@link JdbcTemplate#update} produces an INSERT regardless of id
     * presence.
     *
     * <p>{@link AuditChainHasher#computeHashes} + {@link AuditChainHasher#advanceChainHead}
     * still run inside this transaction so platform-admin-emitted rows for
     * tenant-scoped events (e.g. {@code PLATFORM_TENANT_SUSPENDED} with
     * {@code tenantId = X}) chain into the target tenant's audit history.
     * Platform-wide events ({@code tenantId = SYSTEM_TENANT_ID}) skip
     * chaining via the existing {@link AuditChainHasher} contract.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    void persistWithPreAssignedId(UUID auditEventId, UUID tenantId,
                                   AuditEventRecord event, JsonString details) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantId.toString());

        AuditEventEntity entity = new AuditEventEntity(
                tenantId,
                event.actorUserId(),
                event.targetUserId(),
                event.action() == null ? null : event.action().name(),
                details,
                event.ipAddress());
        entity.setId(auditEventId);

        AuditChainHasher.HashedRow hashed = chainHasher.computeHashes(tenantId, entity);

        // Raw INSERT — Spring Data JDBC's repository.save would treat the
        // pre-set id as "existing row" and emit UPDATE.
        jdbc.update(
                "INSERT INTO audit_events ("
                        + "id, timestamp, tenant_id, actor_user_id, target_user_id, "
                        + "action, details, ip_address, prev_hash, row_hash) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
                auditEventId,
                java.sql.Timestamp.from(entity.getTimestamp()),
                tenantId,
                event.actorUserId(),
                event.targetUserId(),
                event.action().name(),
                details == null ? null : details.value(),
                event.ipAddress(),
                hashed.prevHash(),
                hashed.rowHash());

        chainHasher.advanceChainHead(tenantId, auditEventId, hashed);
    }
}
