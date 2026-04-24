package org.fabt.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.shared.audit.AuditEventTypes;
import org.fabt.shared.security.KeyPurpose;
import org.fabt.shared.security.SecretEncryptionService;
import org.fabt.shared.security.TenantDekService;
import org.fabt.shared.web.TenantContext;
import org.fabt.tenant.domain.IllegalStateTransitionException;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.domain.TenantState;
import org.fabt.tenant.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;

/**
 * End-to-end test for F-6.0 task 7.8f: {@code TenantLifecycleService.hardDelete}.
 *
 * <p>Pins the crypto-shred post-commit invariants the warroom pass-2 ship
 * checklist (§12) gates on:
 *
 * <ul>
 *   <li>FSM: only ARCHIVED → DELETED is permitted; other states reject with
 *       {@link IllegalStateTransitionException} and emit a
 *       {@code TENANT_HARD_DELETE_REJECTED} attempt-audit.</li>
 *   <li>Retention gate: {@code archived_at + retentionDays} must be in the past.</li>
 *   <li>CASCADE: the single {@code DELETE FROM tenant} destroys every child
 *       row — tenant_dek, tenant_key_material, shelter, app_user, everything.</li>
 *   <li>Audit tombstone: a platform-owned {@code TENANT_HARD_DELETED} row lands in
 *       audit_events with the deleted tenant's UUID + terminal chain hash
 *       embedded in the details JSONB.</li>
 *   <li>Cache invalidation: {@link TenantDekService} resolveDek throws
 *       post-shred — the DEK is gone from DB AND from the JVM cache.</li>
 * </ul>
 *
 * <p>Retention window is overridden to 0 days for this test harness via
 * {@code fabt.tenant.hard-delete.retention-days=0} — production default is 30.
 */
@TestPropertySource(properties = {
        "fabt.tenant.lifecycle.enabled=true",
        "fabt.tenant.hard-delete.retention-days=0"
})
class TenantLifecycleHardDeleteIntegrationTest extends BaseIntegrationTest {

    @TempDir
    static Path tempExportRoot;

    @DynamicPropertySource
    static void exportPath(DynamicPropertyRegistry registry) {
        registry.add("fabt.tenant.offboard.export-path", () -> tempExportRoot.toString());
    }

    @Autowired private TenantLifecycleService lifecycleService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TenantDekService tenantDekService;
    @Autowired private SecretEncryptionService encryption;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    // ------------------------------------------------------------------
    // T1 — Happy path: full lifecycle, shred destroys everything
    // ------------------------------------------------------------------

    @Test
    void hardDelete_destroysTenantAndAllChildRows_writesTombstone_invalidatesCaches() {
        // 1. Full lifecycle: CREATE → SUSPEND → OFFBOARD → ARCHIVE → hardDelete
        UUID actor = UUID.randomUUID();
        String slug = "hardDelete-happy-" + UUID.randomUUID();
        Tenant created = lifecycleService.create("Shred Happy CoC", slug, actor);
        UUID tenantId = created.getId();

        // 2. Seed per-tenant rows across several CASCADE-bound tables. Each must
        //    vanish after the single DELETE FROM tenant fires.
        TenantDekService.ActiveDek dek =
            tenantDekService.getOrCreateActiveDek(tenantId, KeyPurpose.TOTP);
        UUID dekKid = dek.kid();
        String canaryEncrypted = encryption.encryptForTenant(
            tenantId, KeyPurpose.WEBHOOK_SECRET, "shred-canary-" + UUID.randomUUID());
        assertThat(canaryEncrypted).isNotBlank();

        // Round-trip prove the ciphertext decrypts cleanly PRE-shred.
        String roundTripped = encryption.decryptForTenant(
            tenantId, KeyPurpose.WEBHOOK_SECRET, canaryEncrypted);
        assertThat(roundTripped).startsWith("shred-canary-");

        // 3. Drive through OFFBOARDING → ARCHIVED. Offboard writes the export
        //    receipt; archive stamps archived_at.
        lifecycleService.offboard(tenantId, actor, "tenant-requested");
        Tenant archived = lifecycleService.archive(tenantId, actor, "retention-start");
        assertThat(archived.getState()).isEqualTo(TenantState.ARCHIVED);
        assertThat(archived.getArchivedAt()).isNotNull();

        // Pre-shred sanity: counts of child rows for this tenant.
        int dekRowsBefore = countTenantDekRows(tenantId);
        int keyMaterialRowsBefore = countTenantKeyMaterialRows(tenantId);
        int sheltersBefore = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shelter WHERE tenant_id = ?", Integer.class, tenantId);
        assertThat(dekRowsBefore).as("tenant has at least one DEK row").isGreaterThanOrEqualTo(2);
        assertThat(keyMaterialRowsBefore).isGreaterThanOrEqualTo(1);

        // Prime the kid-resolution cache so we can later prove it was invalidated.
        TenantDekService.ResolvedDek resolvedPre = tenantDekService.resolveDek(dekKid);
        assertThat(resolvedPre.tenantId()).isEqualTo(tenantId);

        // 4. hardDelete.
        long tombstonesBefore = countTombstones(tenantId);
        lifecycleService.hardDelete(tenantId, actor, "pilot-exit");

        // 5. Assertions — destruction on commit.
        assertThat(tenantRepository.findById(tenantId))
            .as("tenant row must be gone post-shred").isEmpty();
        assertThat(countTenantDekRows(tenantId))
            .as("tenant_dek CASCADE must destroy all per-tenant DEKs").isEqualTo(0);
        assertThat(countTenantKeyMaterialRows(tenantId))
            .as("tenant_key_material CASCADE must destroy rows").isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM shelter WHERE tenant_id = ?", Integer.class, tenantId))
            .as("shelter CASCADE must destroy rows").isEqualTo(0);

        // 6. Cache invalidation: resolveDek on the pre-shred kid must now throw
        //    NoSuchElementException (the row was CASCADE-deleted AND the cache
        //    was evicted by the AFTER_COMMIT listener).
        assertThatThrownBy(() -> tenantDekService.resolveDek(dekKid))
            .as("resolveDek must throw post-shred — cache evicted + row gone")
            .isInstanceOf(java.util.NoSuchElementException.class);

        // 7. Tombstone present in audit_events under SYSTEM_TENANT_ID with the
        //    deleted tenant's UUID embedded in details.
        List<Map<String, Object>> tombstones = queryTombstonesAsSystem(tenantId);
        assertThat(tombstones)
            .as("exactly one TENANT_HARD_DELETED tombstone should exist for this tenant")
            .hasSize(1);
        Map<String, Object> tombstone = tombstones.get(0);
        // Postgres JSONB reformats with a space after each colon on storage,
        // so contains-match on unspaced tokens would fail. Check the keys
        // and values independently instead of a single glued substring.
        String details = tombstone.get("details").toString();
        assertThat(details).contains("\"deleted_tenant_id\"");
        assertThat(details).contains(tenantId.toString());
        assertThat(details).contains("\"last_audit_chain_hash\"");
        assertThat(details).contains("\"previous_state\"");
        assertThat(details).contains("\"ARCHIVED\"");

        assertThat(countTombstones(tenantId)).isEqualTo(tombstonesBefore + 1);
    }

    // ------------------------------------------------------------------
    // T2 — FSM: non-ARCHIVED states must reject
    // ------------------------------------------------------------------

    @Test
    void hardDelete_fromActiveState_rejectsWithFsmException_emitsRejectedAudit() {
        UUID actor = UUID.randomUUID();
        String slug = "hardDelete-fsm-reject-" + UUID.randomUUID();
        Tenant created = lifecycleService.create("Shred Reject CoC", slug, actor);
        UUID tenantId = created.getId();

        assertThatThrownBy(() -> lifecycleService.hardDelete(tenantId, actor, "skip-archive"))
            .as("ACTIVE → DELETED is NOT an allowed transition per §D8")
            .isInstanceOf(IllegalStateTransitionException.class);

        // Tenant row must still exist — FSM rejection rolls back.
        Tenant reloaded = tenantRepository.findById(tenantId).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(TenantState.ACTIVE);

        // Attempt-audit: a TENANT_HARD_DELETE_REJECTED row must be present
        // (detached persister survives the outer rollback).
        long rejectedCount = countAuditRowsForActor(
            AuditEventTypes.TENANT_HARD_DELETE_REJECTED, actor, tenantId);
        assertThat(rejectedCount)
            .as("FSM rejection must emit attempt-audit via REQUIRES_NEW detached persister")
            .isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // T3 — Retention gate: archived recently must reject
    // ------------------------------------------------------------------

    @Test
    void hardDelete_withinRetentionWindow_rejectsWithIllegalStateException() {
        // Override retention to 3650 days for this test only (via JDBC — we
        // set archived_at to very recent past + assert the retention check
        // fires). Uses a subclass-free approach: manipulate archived_at
        // directly post-archive.
        UUID actor = UUID.randomUUID();
        String slug = "hardDelete-retention-" + UUID.randomUUID();
        Tenant created = lifecycleService.create("Shred Retention CoC", slug, actor);
        UUID tenantId = created.getId();

        lifecycleService.offboard(tenantId, actor, "tenant-requested");
        lifecycleService.archive(tenantId, actor, "retention-start");

        // Rewind archived_at to "just now" but override the retention-days
        // property via a helper: simplest path is to override tenant's
        // archived_at to the future, making even a 0-day retention fail.
        Instant tomorrow = Instant.now().plus(1, ChronoUnit.DAYS);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantId.toString());
            jdbc.update("UPDATE tenant SET archived_at = ? WHERE id = ?",
                java.sql.Timestamp.from(tomorrow), tenantId);
        });

        assertThatThrownBy(() -> lifecycleService.hardDelete(tenantId, actor, "too-early"))
            .as("retention window not elapsed — hardDelete must reject")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("retention");

        // Tenant must still exist.
        assertThat(tenantRepository.findById(tenantId)).isPresent();
    }

    // ------------------------------------------------------------------
    // T4 — Idempotency of re-run: second hardDelete on same tenant fails
    //      (tenant doesn't exist anymore, so findById throws NoSuchElement).
    // ------------------------------------------------------------------

    @Test
    void hardDelete_calledTwice_secondCallThrowsNoSuchElement() {
        UUID actor = UUID.randomUUID();
        String slug = "hardDelete-twice-" + UUID.randomUUID();
        Tenant created = lifecycleService.create("Shred Twice CoC", slug, actor);
        UUID tenantId = created.getId();

        lifecycleService.offboard(tenantId, actor, "tenant-requested");
        lifecycleService.archive(tenantId, actor, "retention-start");
        lifecycleService.hardDelete(tenantId, actor, "first-call");

        assertThatThrownBy(() -> lifecycleService.hardDelete(tenantId, actor, "second-call"))
            .as("second hardDelete on a gone tenant must throw NoSuchElementException")
            .isInstanceOf(java.util.NoSuchElementException.class);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private int countTenantDekRows(UUID tenantId) {
        Integer c = jdbc.queryForObject(
            "SELECT COUNT(*) FROM tenant_dek WHERE tenant_id = ?", Integer.class, tenantId);
        return c == null ? 0 : c;
    }

    private int countTenantKeyMaterialRows(UUID tenantId) {
        Integer c = jdbc.queryForObject(
            "SELECT COUNT(*) FROM tenant_key_material WHERE tenant_id = ?",
            Integer.class, tenantId);
        return c == null ? 0 : c;
    }

    /**
     * Counts tombstone rows for the given deleted tenant. Must read under
     * SYSTEM_TENANT_ID because the tombstone is owned by SYSTEM_TENANT_ID
     * per V57 RLS semantics.
     */
    private long countTombstones(UUID deletedTenantId) {
        return org.fabt.testsupport.WithTenantContext.readAsSystem(() -> {
            Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events "
                + "WHERE action = ? "
                + "  AND details ->> 'deleted_tenant_id' = ? "
                + "  AND tenant_id = ?",
                Long.class,
                AuditEventTypes.TENANT_HARD_DELETED,
                deletedTenantId.toString(),
                TenantContext.SYSTEM_TENANT_ID);
            return c == null ? 0L : c;
        });
    }

    private List<Map<String, Object>> queryTombstonesAsSystem(UUID deletedTenantId) {
        return org.fabt.testsupport.WithTenantContext.readAsSystem(() ->
            jdbc.queryForList(
                "SELECT action, details::text AS details FROM audit_events "
                + "WHERE action = ? "
                + "  AND details ->> 'deleted_tenant_id' = ? "
                + "  AND tenant_id = ?",
                AuditEventTypes.TENANT_HARD_DELETED,
                deletedTenantId.toString(),
                TenantContext.SYSTEM_TENANT_ID));
    }

    /**
     * Counts attempt-audit rows for a failed FSM transition. The row is
     * owned by the deleted-tenant context (not SYSTEM), so we bind
     * app.tenant_id to tenantId to read under RLS.
     */
    private long countAuditRowsForActor(String action, UUID actor, UUID tenantId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantId.toString());
            Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events "
                + "WHERE action = ? AND actor_user_id = ? AND tenant_id = ?",
                Long.class, action, actor, tenantId);
            return c == null ? 0L : c;
        });
    }
}
