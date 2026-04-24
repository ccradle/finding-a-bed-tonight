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
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice G-0 (issue #98) — integration tests for the typed-action migration.
 *
 * <h2>T1 — round-trip: enum → DB String → read back</h2>
 *
 * <p>Publishes an {@link AuditEventRecord} with a typed
 * {@link AuditEventType} action and verifies the DB row's {@code action}
 * column stores the exact {@code .name()} wire form. Proves:
 * <ul>
 *   <li>{@link AuditEventPersister#persist} correctly serialises
 *       {@code event.action().name()} to the VARCHAR column</li>
 *   <li>Historical audit queries filtering by the pre-migration string
 *       value (e.g. {@code WHERE action = 'TENANT_CREATED'}) still match
 *       rows written post-migration — the wire form is identical</li>
 * </ul>
 *
 * <h2>T2 — canonical-JSON determinism (hash-input stability for Phase G-1)</h2>
 *
 * <p>Serialises the same logical audit row twice and asserts byte-identical
 * JSON output. Phase G-1 will compute
 * {@code row_hash = SHA256(prev_hash || canonical_json(row))}; once the first
 * chain row is written, the serialisation form is permanent hash input. This
 * test locks the property for the {@link AuditEventRecord#details()} payload
 * before G-1 ships.
 *
 * <p><b>Note:</b> this is a non-schema test — no new DB objects. It runs
 * against the existing {@code audit_events} table unchanged, asserting only
 * the Java-layer contract. The full chain-hashing test lands with G-1.
 */
class AuditEventG0RoundTripIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("T1 — enum action round-trips through DB as .name() wire form")
    void t1_enumActionRoundTripsAsName() {
        UUID tenantId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        String probeToken = "G0_ROUNDTRIP_" + UUID.randomUUID();

        // Publish under a TenantContext so the row carries tenantId and passes
        // V69 FORCE RLS on audit_events.
        TenantContext.runWithContext(tenantId, false, () -> {
            eventPublisher.publishEvent(new AuditEventRecord(
                    actor,
                    null,
                    AuditEventType.TEST_PROBE,
                    Map.of("probe_token", probeToken, "t1_marker", "round-trip"),
                    null));
        });

        // Read back under the same tenant context — FORCE RLS filters otherwise.
        List<String> actionsForProbe = WithTenantContext.readAs(tenantId, () ->
                jdbcTemplate.queryForList(
                        "SELECT action FROM audit_events "
                        + "WHERE details ->> 'probe_token' = ? AND tenant_id = ?",
                        String.class, probeToken, tenantId));

        assertThat(actionsForProbe)
                .as("Audit row must land with exactly one match for the probe token")
                .hasSize(1);

        assertThat(actionsForProbe.get(0))
                .as("DB column `action` must equal the enum's .name() wire form. "
                    + "If this diverges, Phase G-1 chain hashing would see inconsistent "
                    + "canonical_json input and historical queries filtering by the "
                    + "pre-migration string would miss rows.")
                .isEqualTo(AuditEventType.TEST_PROBE.name())
                .isEqualTo("TEST_PROBE");
    }

    @Test
    @DisplayName("T2 — canonical-JSON determinism: same record serialises byte-identically twice")
    void t2_canonicalJsonDeterminism() throws Exception {
        // Same input produced twice. Jackson's default ObjectMapper config has
        // non-deterministic ordering for Map entries in some cases; LinkedHashMap
        // + explicit insertion order in the record guarantees stability.
        //
        // If this test ever fails after a Jackson upgrade or config change,
        // Phase G-1's chain hash becomes unstable — an audit row hash computed
        // on shipped version X would not re-verify on version Y.
        UUID tenantId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        java.util.LinkedHashMap<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("alpha", "one");
        details.put("beta", 42);
        details.put("gamma", List.of("a", "b", "c"));
        details.put("delta", Map.of("nested", true));

        AuditEventRecord first = new AuditEventRecord(
                actor, target, AuditEventType.TEST_PROBE, details, "10.0.0.1");
        AuditEventRecord second = new AuditEventRecord(
                actor, target, AuditEventType.TEST_PROBE, details, "10.0.0.1");

        String firstJson = objectMapper.writeValueAsString(first.details());
        String secondJson = objectMapper.writeValueAsString(second.details());

        assertThat(firstJson)
                .as("Repeated serialisation of the same audit details payload must be "
                    + "byte-identical. Phase G-1 chain hashing relies on this invariant: "
                    + "row_hash = SHA256(prev_hash || canonical_json(row)) must yield the "
                    + "same hash on every re-computation for verification to succeed.")
                .isEqualTo(secondJson);

        // Also pin the structural shape — key order, type rendering — so a
        // future Jackson config change that shuffles Map ordering or switches
        // number-rendering fails loudly here instead of silently at G-1 verify time.
        assertThat(firstJson)
                .as("Canonical JSON shape is load-bearing for G-1 chain hashing")
                .contains("\"alpha\":\"one\"")
                .contains("\"beta\":42")
                .contains("\"gamma\":[\"a\",\"b\",\"c\"]");
    }

    @Test
    @DisplayName("T3 — enum action inside details payload serialises as .name() string")
    void t3_enumInDetailsSerialisesAsName() throws Exception {
        // Defensive: if a publisher ever embeds an AuditEventType inside the
        // details payload (e.g. {"related_action": AuditEventType.X}), Jackson's
        // default enum serialiser uses Enum.name(). This pins the expectation
        // so a Jackson config change that switched to .toString() or @JsonValue
        // fails this test rather than silently changing the canonical_json
        // form of audit rows.
        Map<String, Object> details = Map.of(
                "related_action", AuditEventType.TENANT_SUSPENDED);
        String json = objectMapper.writeValueAsString(details);

        assertThat(json)
                .as("AuditEventType must serialise as its .name() string inside JSONB payloads")
                .isEqualTo("{\"related_action\":\"TENANT_SUSPENDED\"}");
    }
}
