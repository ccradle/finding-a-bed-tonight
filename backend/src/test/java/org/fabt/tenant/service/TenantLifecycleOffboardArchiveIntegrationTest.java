package org.fabt.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.fabt.BaseIntegrationTest;
import org.fabt.auth.domain.ApiKey;
import org.fabt.auth.repository.ApiKeyRepository;
import org.fabt.shared.audit.AuditEventType;
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

/**
 * End-to-end integration test for F-5: offboard writes a schema'd JSON export to
 * disk + stores the receipt URI + audits; archive requires the receipt and stamps
 * {@code archived_at}; full round-trip verifies JSON parses and contains the
 * expected top-level keys + seed data counts.
 */
@TestPropertySource(properties = "fabt.tenant.lifecycle.enabled=true")
class TenantLifecycleOffboardArchiveIntegrationTest extends BaseIntegrationTest {

    @TempDir
    static Path tempExportRoot;

    @DynamicPropertySource
    static void exportPath(DynamicPropertyRegistry registry) {
        registry.add("fabt.tenant.offboard.export-path", () -> tempExportRoot.toString());
    }

    @Autowired private TenantLifecycleService lifecycleService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void offboard_writesExportFile_storesReceiptUri_emitsAudit() throws IOException {
        // Seed: create tenant via lifecycle (F-4 atomic bootstrap), add one API
        // key so the export has a non-trivial row to include.
        String slug = "f5-off-" + UUID.randomUUID();
        Tenant created = lifecycleService.create("F-5 Off CoC", slug, UUID.randomUUID());
        seedActiveApiKey(created.getId());

        UUID actor = UUID.randomUUID();
        Tenant offboarded = lifecycleService.offboard(created.getId(), actor, "tenant-request");

        // State + receipt URI persisted
        assertThat(offboarded.getState()).isEqualTo(TenantState.OFFBOARDING);
        assertThat(offboarded.getOffboardExportReceiptUri())
            .as("receipt URI stamped on tenant row")
            .isNotBlank()
            .startsWith(tempExportRoot.toString());

        // File exists on disk and is parseable JSON
        Path exportFile = Path.of(offboarded.getOffboardExportReceiptUri());
        assertThat(exportFile).exists();
        JsonNode root = objectMapper.readTree(exportFile.toFile());

        // Envelope fields
        assertThat(root.get("schemaVersion").asString()).isEqualTo("1.0.0");
        assertThat(root.get("tenantId").asString()).isEqualTo(created.getId().toString());
        assertThat(root.get("exportedAt")).isNotNull();

        // Required top-level tables present (F-5 schema contract)
        for (String table : TenantOffboardExportService.EXPORT_TABLES) {
            assertThat(root.has(table))
                .as("export envelope contains %s", table)
                .isTrue();
            assertThat(root.get(table).isArray())
                .as("%s is an array", table)
                .isTrue();
        }

        // Bidirectional drift guard: no top-level key in the envelope exists
        // outside {envelope metadata} ∪ EXPORT_TABLES. Catches the reverse
        // regression where someone adds writeTable(gen, "incidents", ...) but
        // forgets to append to EXPORT_TABLES — envelope and the single-source-
        // of-truth constant drift apart silently otherwise.
        java.util.Set<String> envelopeMetaKeys = java.util.Set.of(
            "schemaVersion", "tenantId", "exportedAt");
        java.util.Set<String> declaredTables = java.util.Set.of(TenantOffboardExportService.EXPORT_TABLES);
        java.util.Set<String> foundKeys = new java.util.HashSet<>();
        root.propertyNames().forEach(foundKeys::add);
        foundKeys.removeAll(envelopeMetaKeys);
        foundKeys.removeAll(declaredTables);
        assertThat(foundKeys)
            .as("no undeclared top-level keys — every envelope key must appear in "
                + "TenantOffboardExportService.EXPORT_TABLES or the metadata set")
            .isEmpty();

        // Seed data present: tenant row = 1, api_keys row = 1
        assertThat(root.get("tenant").size()).isEqualTo(1);
        assertThat(root.get("api_keys").size()).isEqualTo(1);
        // api_keys export deliberately excludes key_hash + old_key_hash
        JsonNode apiKeyRow = root.get("api_keys").get(0);
        assertThat(apiKeyRow.has("key_hash"))
            .as("key_hash deliberately excluded from export (secret)")
            .isFalse();
        assertThat(apiKeyRow.has("old_key_hash"))
            .as("old_key_hash deliberately excluded from export (secret)")
            .isFalse();

        // TENANT_OFFBOARDING_STARTED audit row
        List<String> actions = queryAuditActionsForTenant(created.getId(), actor);
        assertThat(actions).contains(AuditEventType.TENANT_OFFBOARDING_STARTED.name());
    }

    @Test
    void archive_requiresExportReceipt_stampsArchivedAt() {
        String slug = "f5-arc-" + UUID.randomUUID();
        Tenant created = lifecycleService.create("F-5 Arc CoC", slug, UUID.randomUUID());
        lifecycleService.offboard(created.getId(), UUID.randomUUID(), "export");

        UUID actor = UUID.randomUUID();
        Instant beforeArchive = Instant.now();
        Tenant archived = lifecycleService.archive(created.getId(), actor, "retention-start");

        assertThat(archived.getState()).isEqualTo(TenantState.ARCHIVED);
        assertThat(archived.getArchivedAt())
            .as("archived_at stamped within the request window")
            .isBetween(beforeArchive.minusSeconds(1), Instant.now().plusSeconds(1));
        assertThat(archived.getOffboardExportReceiptUri())
            .as("archive preserves the offboard receipt — forensic link to the export file")
            .isNotBlank();

        // TENANT_ARCHIVED audit row
        List<String> actions = queryAuditActionsForTenant(created.getId(), actor);
        assertThat(actions).contains(AuditEventType.TENANT_ARCHIVED.name());
    }

    @Test
    void archive_withoutOffboardFirst_fails_withoutStateFlip() {
        // §D8 allows ACTIVE -> SUSPENDED -> OFFBOARDING -> ARCHIVED but NOT
        // ACTIVE -> ARCHIVED directly. The FSM assertion catches this before
        // the receipt check fires — but either way, the state must NOT advance.
        String slug = "f5-direct-" + UUID.randomUUID();
        Tenant created = lifecycleService.create("F-5 Direct", slug, UUID.randomUUID());

        assertThatThrownBy(() ->
                lifecycleService.archive(created.getId(), UUID.randomUUID(), "skip-offboard"))
            .isInstanceOf(org.fabt.tenant.domain.IllegalStateTransitionException.class);

        Tenant reloaded = tenantRepository.findById(created.getId()).orElseThrow();
        assertThat(reloaded.getState())
            .as("failed archive must not advance state")
            .isEqualTo(TenantState.ACTIVE);
        assertThat(reloaded.getArchivedAt())
            .as("no archived_at stamp on failed archive")
            .isNull();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private UUID seedActiveApiKey(UUID tenantId) {
        ApiKey k = new ApiKey();
        k.setTenantId(tenantId);
        k.setKeyHash("f5-sim-" + UUID.randomUUID());
        k.setKeySuffix(UUID.randomUUID().toString().substring(0, 4));
        k.setLabel("f5-export-seed");
        k.setRole("COC_ADMIN");
        k.setActive(true);
        k.setCreatedAt(Instant.now());
        return apiKeyRepository.save(k).getId();
    }

    private List<String> queryAuditActionsForTenant(UUID tenantId, UUID actor) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantId.toString());
            return jdbc.queryForList(
                "SELECT action FROM audit_events WHERE actor_user_id = ? AND tenant_id = ?",
                String.class, actor, tenantId);
        });
    }
}
