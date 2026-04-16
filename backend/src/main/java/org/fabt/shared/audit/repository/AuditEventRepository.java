package org.fabt.shared.audit.repository;

import java.util.List;

import org.fabt.shared.audit.AuditEventEntity;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends CrudRepository<AuditEventEntity, UUID> {

    /**
     * Tenant-scoped audit-event query — used by
     * {@code AuditEventController.getAuditEvents} and any other caller
     * that wants audit history for a target user.
     *
     * <p>Pre-fix (before cross-tenant-isolation-audit Phase 2.12) the
     * tenant_id predicate was missing: a CoC admin in Tenant A could
     * query Tenant B user UUIDs and read Tenant B's audit history
     * (VAWA audit-integrity violation). Post-fix, the query filters by
     * the caller's tenant and returns empty for cross-tenant probes.
     * See {@code cross-tenant-isolation-audit} design D3 and D15.
     */
    @Query("SELECT * FROM audit_events "
            + "WHERE target_user_id = :targetUserId AND tenant_id = :tenantId "
            + "ORDER BY timestamp DESC LIMIT 100")
    List<AuditEventEntity> findByTargetUserIdAndTenantId(@Param("targetUserId") UUID targetUserId,
                                                         @Param("tenantId") UUID tenantId);
}
