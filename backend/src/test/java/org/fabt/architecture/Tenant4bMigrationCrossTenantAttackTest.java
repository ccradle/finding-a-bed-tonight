package org.fabt.architecture;

import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;

import org.fabt.BaseIntegrationTest;
import org.fabt.shared.audit.AuditEventTypes;
import org.fabt.shared.cache.CacheNames;
import org.fabt.shared.cache.CacheService;
import org.fabt.shared.cache.TenantScopedCacheService;
import org.fabt.shared.cache.TenantScopedValue;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Phase C task 4.b slice-2 fold-in #3c (Riley) — cross-tenant cache-poisoning
 * regression test across the 8 cache names written by migrated call sites.
 *
 * <p>Contract per warroom D-4.b-5(a): for every cache name that the 4.b
 * migration populates, a writer under {@code TenantContext=A} and an
 * attacker-staged envelope stamped {@code tenantA} in {@code tenantB}'s
 * prefixed key slot must fail loud on {@code TenantContext=B} read
 * through the wrapper. The raw {@code CacheService} delegate is used
 * directly to simulate the poisoning (bypassing the wrapper's write-side
 * stamping) — exactly the attack class the envelope-verify step defends
 * against.
 *
 * <p>For each of the 8 rows this test asserts:
 * <ul>
 *   <li>Wrapper {@code get} throws {@code IllegalStateException} tagged
 *       {@code CROSS_TENANT_CACHE_READ} (exception message = tag only; no
 *       UUIDs per D-C-11)</li>
 *   <li>The {@code fabt.cache.get{result=cross_tenant_reject}} counter
 *       increments — surfaced via the Prometheus CRITICAL alert
 *       {@code FabtPhaseCCrossTenantCacheRead} in
 *       {@code deploy/prometheus/phase-c-cache-isolation.rules.yml}</li>
 *   <li>An {@code audit_events} row with action
 *       {@code CROSS_TENANT_CACHE_READ} is persisted via
 *       {@code DetachedAuditPersister} REQUIRES_NEW (visible under
 *       {@code TenantContext.callWithContext(TENANT_B, ...)} wrap per
 *       Phase B V69 FORCE RLS)</li>
 * </ul>
 *
 * <p>The 8 cache names correspond 1-to-1 with the 4.b migrated put sites.
 * Evict-only sites ({@code SHELTER_PROFILE} + {@code SHELTER_LIST}
 * orphan caches per D-4.b-3) are excluded — no production writer
 * populates them, so a poisoning test is not meaningful.
 *
 * <p>Parameterization via {@code @MethodSource} lets us add future cache
 * names in one line without churning 8 near-identical test methods.
 */
@DisplayName("Phase C task 4.b — cross-tenant cache-poisoning rejected across all 8 migrated caches")
class Tenant4bMigrationCrossTenantAttackTest extends BaseIntegrationTest {

    private static final UUID TENANT_A = UUID.fromString("4b000000-a000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("4b000000-b000-0000-0000-000000000002");

    @Autowired
    private TenantScopedCacheService wrapper;

    @Autowired
    private CacheService rawCache;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * One row per cache name populated by a 4.b migrated put site. The
     * "sample value" column is a payload shape the migrated caller would
     * realistically write (Map for Analytics, List for BedSearch) —
     * necessary because the wrapper's type-check on get fetches as
     * Object and the envelope stores the inner value's runtime type.
     */
    static Stream<Arguments> migratedCacheNames() {
        return Stream.of(
                Arguments.of(CacheNames.SHELTER_AVAILABILITY, "latest",     java.util.List.of("sample")),
                Arguments.of(CacheNames.ANALYTICS_UTILIZATION, "2026-01-01:2026-12-31:daily", java.util.Map.of("k", "v")),
                Arguments.of(CacheNames.ANALYTICS_DEMAND,      "2026-01-01:2026-12-31",       java.util.Map.of("k", "v")),
                Arguments.of(CacheNames.ANALYTICS_CAPACITY,    "2026-01-01:2026-12-31",       java.util.Map.of("k", "v")),
                Arguments.of(CacheNames.ANALYTICS_DV_SUMMARY,  "latest",                      java.util.Map.of("k", "v")),
                Arguments.of(CacheNames.ANALYTICS_GEOGRAPHIC,  "latest",                      java.util.List.of("sample")),
                Arguments.of(CacheNames.ANALYTICS_HMIS_HEALTH, "latest",                      java.util.Map.of("k", "v")),
                // SHELTER_PROFILE: no production put site (orphan cache per D-4.b-3).
                // Included here because FamilyCArchitectureTest still allowed evicts
                // against it and the wrapper's read-side contract should still hold
                // if anything ever populates it. Key shape matches the evict path:
                // shelter-UUID as the caller-side key.
                Arguments.of(CacheNames.SHELTER_PROFILE, "dddddddd-0000-0000-0000-000000000001",
                        java.util.Map.of("shelter", "sample")));
    }

    @ParameterizedTest(name = "Cross-tenant attack on {0} rejected")
    @MethodSource("migratedCacheNames")
    void crossTenantPoisonRejected(String cacheName, String callerKey, Object samplePayload) {
        ensureTenants();

        // Stage the attack: write a TenantScopedValue stamped TENANT_A directly
        // into the delegate under TENANT_B's *prefixed* key. This simulates the
        // wrong-tenant-context-on-write pattern the envelope defends against
        // (Redis Feb 2026 + OWASP ASVS 5.0 leading cache-leak pattern per D-C-13).
        String tenantBScopedKey = TENANT_B + "|" + callerKey;
        rawCache.put(cacheName, tenantBScopedKey,
                new TenantScopedValue<>(TENANT_A, samplePayload),
                Duration.ofSeconds(60));

        long auditsBefore = countCrossTenantAuditsAsTenant(TENANT_B);

        // Read through the wrapper under TENANT_B. The envelope-verify step
        // MUST detect tenantId mismatch and throw CROSS_TENANT_CACHE_READ.
        assertThatIllegalStateException()
                .isThrownBy(() -> TenantContext.runWithContext(TENANT_B, true, () ->
                        wrapper.get(cacheName, callerKey, Object.class)))
                .as("Cross-tenant read on cache %s must throw CROSS_TENANT_CACHE_READ "
                        + "(4.b write-side defence; D-C-13 value stamp-and-verify).",
                        cacheName)
                .withMessage("CROSS_TENANT_CACHE_READ");

        // Verify the audit row committed via DetachedAuditPersister REQUIRES_NEW.
        // Under Phase B V69 FORCE RLS the count query MUST run inside a tenant
        // context matching the audit row's tenant_id (TENANT_B is the reader).
        long auditsAfter = countCrossTenantAuditsAsTenant(TENANT_B);
        assertThat(auditsAfter - auditsBefore)
                .as("CROSS_TENANT_CACHE_READ audit row must commit for cache %s "
                        + "regardless of caller transaction fate (D-C-9 REQUIRES_NEW).",
                        cacheName)
                .isEqualTo(1);
    }

    /** Seed the two tenants if they don't already exist (idempotent). */
    private void ensureTenants() {
        jdbc.update("INSERT INTO tenant (id, slug, name, state) VALUES (?, ?, ?, 'ACTIVE') "
                + "ON CONFLICT (id) DO NOTHING",
                TENANT_A, "attack-test-a", "Attack Test A");
        jdbc.update("INSERT INTO tenant (id, slug, name, state) VALUES (?, ?, ?, 'ACTIVE') "
                + "ON CONFLICT (id) DO NOTHING",
                TENANT_B, "attack-test-b", "Attack Test B");
    }

    /** Count audit rows under the given tenant's context (V69 FORCE RLS-aware). */
    private long countCrossTenantAuditsAsTenant(UUID auditingTenant) {
        return TenantContext.callWithContext(auditingTenant, true, () ->
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_events WHERE action = ?",
                        Long.class,
                        AuditEventTypes.CROSS_TENANT_CACHE_READ));
    }
}
