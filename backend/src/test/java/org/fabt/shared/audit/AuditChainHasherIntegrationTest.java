package org.fabt.shared.audit;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.shared.web.TenantContext;
import org.fabt.testsupport.WithTenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice G-1 — integration tests for the per-tenant audit hash chain.
 *
 * <h2>T1 — chain progression</h2>
 * Three audit events for the same tenant; assert each row's
 * {@code prev_hash} equals the previous row's {@code row_hash}, and
 * {@code tenant_audit_chain_head.last_hash} matches the last row's
 * {@code row_hash}.
 *
 * <h2>T2 — genesis hash is the zero sentinel</h2>
 * First audit row for a freshly-seeded tenant picks up the 32-byte zero
 * value from {@code tenant_audit_chain_head.last_hash} (seeded by V80 /
 * {@code TenantLifecycleService.create}). Assert the row's
 * {@code prev_hash} is exactly 32 zero bytes and {@code row_hash} matches
 * {@code SHA-256(zeros || canonical_json(row))}.
 *
 * <h2>T3 — SYSTEM_TENANT_ID orphan path skipped</h2>
 * Publish an event outside any TenantContext so it falls back to
 * SYSTEM_TENANT_ID. Assert the persisted row has {@code prev_hash = NULL}
 * and {@code row_hash = NULL}. Orphans are not chained.
 *
 * <h2>T4 — canonical_json byte-for-byte reproducibility</h2>
 * Build two equal entities, assert their canonical JSON is byte-identical.
 * Verifier (Slice G-2) relies on this invariant to re-compute historical
 * hashes.
 */
class AuditChainHasherIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditChainHasher chainHasher;

    private static final byte[] ZERO_SENTINEL = new byte[32]; // all zeros

    @Test
    @DisplayName("T1 — chain progression: each row's prev_hash == previous row's row_hash")
    void t1_chainProgression() {
        UUID tenantId = seedTenantAndChainHead();
        UUID actor = UUID.randomUUID();

        String probeToken = "G1_CHAIN_" + UUID.randomUUID();

        // Publish 3 audit events in order under the tenant's context.
        TenantContext.runWithContext(tenantId, false, () -> {
            for (int i = 1; i <= 3; i++) {
                eventPublisher.publishEvent(new AuditEventRecord(
                        actor, null, AuditEventType.TEST_PROBE,
                        Map.of("probe_token", probeToken, "seq", i),
                        null));
            }
        });

        // Read the 3 rows back, ordered by timestamp.
        List<byte[][]> hashes = WithTenantContext.readAs(tenantId, () ->
                jdbc.query(
                        "SELECT prev_hash, row_hash FROM audit_events "
                        + "WHERE tenant_id = ? AND details ->> 'probe_token' = ? "
                        + "ORDER BY timestamp",
                        (rs, n) -> new byte[][]{ rs.getBytes(1), rs.getBytes(2) },
                        tenantId, probeToken));

        assertThat(hashes)
                .as("Expected 3 chained audit rows for tenant")
                .hasSize(3);

        // Row 0: prev_hash = zeros (genesis), row_hash = non-null
        assertThat(hashes.get(0)[0])
                .as("Genesis row's prev_hash must be 32-byte zero sentinel")
                .isEqualTo(ZERO_SENTINEL);
        assertThat(hashes.get(0)[1]).as("row_hash must be 32 bytes").hasSize(32);

        // Row 1: prev_hash = row 0's row_hash
        assertThat(hashes.get(1)[0])
                .as("Row 1's prev_hash must equal row 0's row_hash (chain link)")
                .isEqualTo(hashes.get(0)[1]);

        // Row 2: prev_hash = row 1's row_hash
        assertThat(hashes.get(2)[0])
                .as("Row 2's prev_hash must equal row 1's row_hash (chain link)")
                .isEqualTo(hashes.get(1)[1]);

        // Chain head must point at row 2's row_hash
        byte[] chainHead = jdbc.queryForObject(
                "SELECT last_hash FROM tenant_audit_chain_head WHERE tenant_id = ?",
                byte[].class, tenantId);
        assertThat(chainHead)
                .as("tenant_audit_chain_head.last_hash must point at the most recent row_hash")
                .isEqualTo(hashes.get(2)[1]);
    }

    @Test
    @DisplayName("T2 — genesis row has zero-sentinel prev_hash and a 32-byte row_hash")
    void t2_genesisHashStructuralProperties() throws Exception {
        UUID tenantId = seedTenantAndChainHead();
        UUID actor = UUID.randomUUID();
        String probeToken = "G1_GENESIS_" + UUID.randomUUID();

        TenantContext.runWithContext(tenantId, false, () -> {
            eventPublisher.publishEvent(new AuditEventRecord(
                    actor, null, AuditEventType.TEST_PROBE,
                    Map.of("probe_token", probeToken),
                    null));
        });

        Map<String, Object> row = WithTenantContext.readAs(tenantId, () ->
                jdbc.queryForMap(
                        "SELECT prev_hash, row_hash FROM audit_events "
                        + "WHERE tenant_id = ? AND details ->> 'probe_token' = ?",
                        tenantId, probeToken));

        byte[] prevHash = (byte[]) row.get("prev_hash");
        byte[] rowHash = (byte[]) row.get("row_hash");

        assertThat(prevHash)
                .as("Genesis prev_hash must be 32-byte zero sentinel (V80 seed value)")
                .isEqualTo(ZERO_SENTINEL);
        assertThat(rowHash)
                .as("Genesis row_hash must be 32 bytes (SHA-256 digest)")
                .isNotNull()
                .hasSize(32);
        assertThat(rowHash)
                .as("Genesis row_hash must differ from the zero sentinel — hashing a "
                    + "non-empty canonical_json with any input yields a non-zero digest "
                    + "with overwhelming probability")
                .isNotEqualTo(ZERO_SENTINEL);

        // NOTE on full hash re-computation: asserting `row_hash == SHA-256(zeros || canonical_json(row))`
        // requires a canonicalizer that matches the form stored in the DB. At write time the
        // hasher consumes Jackson's JSON output for `details`; at verify time the DB returns
        // PG's JSONB canonical form (`::text`) which has different whitespace + sorted keys.
        // Bridging that gap is G-2's canonicalizer — tracked as §8.6 in
        // multi-tenant-production-readiness/tasks.md. For G-1 we verify structural
        // properties only; full round-trip verification lands with the verifier.
    }

    @Test
    @DisplayName("T3 — SYSTEM_TENANT_ID orphan audit skips hashing (NULL prev_hash + row_hash)")
    void t3_systemTenantOrphanSkipsHashing() {
        String probeToken = "G1_ORPHAN_" + UUID.randomUUID();

        // Publish outside any TenantContext — falls back to SYSTEM_TENANT_ID.
        eventPublisher.publishEvent(new AuditEventRecord(
                UUID.randomUUID(), null, AuditEventType.TEST_PROBE,
                Map.of("probe_token", probeToken),
                null));

        // Read under SYSTEM context (FORCE RLS on audit_events).
        Map<String, Object> row = WithTenantContext.readAsSystem(() ->
                jdbc.queryForMap(
                        "SELECT prev_hash, row_hash, tenant_id FROM audit_events "
                        + "WHERE tenant_id = ?::uuid AND details ->> 'probe_token' = ?",
                        TenantContext.SYSTEM_TENANT_ID, probeToken));

        assertThat(row.get("prev_hash"))
                .as("Orphan audit under SYSTEM_TENANT_ID must have NULL prev_hash "
                    + "(no chain head exists for the sentinel — by design)")
                .isNull();
        assertThat(row.get("row_hash"))
                .as("Orphan audit under SYSTEM_TENANT_ID must have NULL row_hash")
                .isNull();
    }

    @Test
    @DisplayName("T4 — canonicalJson is byte-identical for equal entities (verifier stability)")
    void t4_canonicalJsonStableForEqualEntities() {
        AuditEventEntity a = sampleEntity();
        AuditEventEntity b = sampleEntity();

        String canonicalA = chainHasher.canonicalJson(a);
        String canonicalB = chainHasher.canonicalJson(b);

        assertThat(canonicalA)
                .as("canonical_json must be byte-identical for equal logical entities — "
                    + "Phase G-2 verifier re-hashes rows read from the DB and compares to "
                    + "stored row_hash. Any non-determinism breaks verification.")
                .isEqualTo(canonicalB);

        // Pin the structural shape so a future change to the serialiser
        // (field order, escaping, null rendering) fails here instead of
        // silently breaking the G-2 verifier on shipped rows.
        assertThat(canonicalA)
                .as("canonical_json structural shape is load-bearing — see AuditChainHasher "
                    + "javadoc on STABILITY CONTRACT")
                .startsWith("{\"tenant_id\":")
                .contains("\"timestamp\":")
                .contains("\"actor_user_id\":")
                .contains("\"target_user_id\":")
                .contains("\"action\":\"TEST_PROBE\"")
                .contains("\"details\":")
                .contains("\"ip_address\":")
                .endsWith("}");
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    /**
     * Creates a tenant row directly via SQL (bypassing TenantLifecycleService
     * so this test suite doesn't depend on the full lifecycle bootstrap) and
     * inserts a tenant_audit_chain_head seed row with the 32-zero sentinel
     * (matching what V80 backfill + TenantLifecycleService.create do).
     */
    private UUID seedTenantAndChainHead() {
        UUID tenantId = UUID.randomUUID();
        String slug = "g1-chain-" + tenantId.toString().substring(0, 8);
        WithTenantContext.readAsSystem(() -> {
            jdbc.update(
                    "INSERT INTO tenant (id, slug, name, state, "
                    + "jwt_key_generation, data_residency_region) "
                    + "VALUES (?, ?, ?, 'ACTIVE', 1, 'us-any')",
                    tenantId, slug, "G-1 chain test");
            jdbc.update(
                    "INSERT INTO tenant_audit_chain_head (tenant_id, last_hash, last_row_id) "
                    + "VALUES (?, decode('"
                    + "0000000000000000000000000000000000000000000000000000000000000000"
                    + "', 'hex'), NULL)",
                    tenantId);
            return null;
        });
        return tenantId;
    }

    private static AuditEventEntity sampleEntity() {
        AuditEventEntity e = new AuditEventEntity();
        e.setTenantId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        e.setTimestamp(java.time.Instant.parse("2026-04-24T20:00:00Z"));
        e.setActorUserId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        e.setTargetUserId(null);
        e.setAction(AuditEventType.TEST_PROBE.name());
        e.setDetails(new org.fabt.shared.config.JsonString("{\"k\":\"v\"}"));
        e.setIpAddress("10.0.0.1");
        return e;
    }

}
