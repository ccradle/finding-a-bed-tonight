package org.fabt.shared.security;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end tests for {@link TenantDekService} against the V82 {@code tenant_dek}
 * table. Exercises the Option A crypto-shred foundation: random DEK generation,
 * AES-KWP wrap/unwrap, race-safe first-encrypt, cache behavior, and tenant
 * invalidation.
 *
 * <p>This is a subset of the §11 test suite (Jordan's 7-test minimum) — the
 * parts that can be verified with just V82 + {@code TenantDekService} in
 * place. The downstream tests ({@code NTenantCanaryShredTest},
 * {@code TenantDekRlsTest}, {@code TenantDekShredGuardTest}) land alongside
 * their dependent components in tasks 11.2 / 11.4 / 11.7.
 */
class TenantDekServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private TenantDekService tenantDekService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        // Each test gets fresh, isolated tenants. The suite runs in a shared
        // Testcontainer and other tests (within this class and elsewhere) can
        // leave state in tenant_dek for the default "test-tenant" slug —
        // see feedback_isolated_test_data. Per-test UUIDs eliminate cross-test
        // pollution entirely.
        tenantA = authHelper.setupSecondaryTenant("dek-it-a-" + UUID.randomUUID()).getId();
        tenantB = authHelper.setupSecondaryTenant("dek-it-b-" + UUID.randomUUID()).getId();
    }

    // ------------------------------------------------------------------
    // T1 — first call creates row; DEK is random + wrapped on disk
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getOrCreateActiveDek inserts a tenant_dek row on first call; wrapped_dek is 40 bytes")
    void firstEncryptCreatesRow() {
        TenantDekService.ActiveDek active =
            tenantDekService.getOrCreateActiveDek(tenantA, KeyPurpose.TOTP);

        assertThat(active.kid()).isNotNull();
        assertThat(active.generation()).isEqualTo(1);
        assertThat(active.dek()).isNotNull();
        assertThat(active.dek().getEncoded()).hasSize(32);  // AES-256

        // Row present in DB. wrapped_dek size = 40 bytes for a 32-byte DEK per RFC 5649.
        Integer wrappedLen = jdbc.queryForObject(
            "SELECT length(wrapped_dek) FROM tenant_dek WHERE kid = ?",
            Integer.class, active.kid());
        assertThat(wrappedLen).as("AES-KWP output for 32-byte DEK is 40 bytes").isEqualTo(40);

        String purpose = jdbc.queryForObject(
            "SELECT purpose FROM tenant_dek WHERE kid = ?",
            String.class, active.kid());
        assertThat(purpose).isEqualTo("TOTP");
    }

    // ------------------------------------------------------------------
    // T2 — idempotent: second call for same (tenant, purpose) returns same kid
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getOrCreateActiveDek is idempotent — second call returns same kid + same DEK bytes")
    void idempotentForSameTenantPurpose() {
        TenantDekService.ActiveDek first =
            tenantDekService.getOrCreateActiveDek(tenantA, KeyPurpose.TOTP);
        TenantDekService.ActiveDek second =
            tenantDekService.getOrCreateActiveDek(tenantA, KeyPurpose.TOTP);

        assertThat(second.kid()).isEqualTo(first.kid());
        assertThat(second.generation()).isEqualTo(first.generation());
        assertThat(second.dek().getEncoded()).containsExactly(first.dek().getEncoded());

        // Exactly one row in DB — not two rows with same kid collided through some path.
        Integer rowCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM tenant_dek WHERE tenant_id = ? AND purpose = ?",
            Integer.class, tenantA, "TOTP");
        assertThat(rowCount).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // T3 — different purposes get different DEKs + different kids for same tenant
    // ------------------------------------------------------------------

    @Test
    @DisplayName("different purposes yield different kids + different DEKs for same tenant")
    void purposesIsolatedPerTenant() {
        TenantDekService.ActiveDek totp =
            tenantDekService.getOrCreateActiveDek(tenantA, KeyPurpose.TOTP);
        TenantDekService.ActiveDek webhook =
            tenantDekService.getOrCreateActiveDek(tenantA, KeyPurpose.WEBHOOK_SECRET);
        TenantDekService.ActiveDek oauth =
            tenantDekService.getOrCreateActiveDek(tenantA, KeyPurpose.OAUTH2_CLIENT_SECRET);
        TenantDekService.ActiveDek hmis =
            tenantDekService.getOrCreateActiveDek(tenantA, KeyPurpose.HMIS_API_KEY);

        // All 4 kids distinct
        assertThat(List.of(totp.kid(), webhook.kid(), oauth.kid(), hmis.kid()))
            .doesNotHaveDuplicates();

        // All 4 DEK byte arrays distinct (random — not HKDF-derived)
        assertThat(totp.dek().getEncoded()).isNotEqualTo(webhook.dek().getEncoded());
        assertThat(totp.dek().getEncoded()).isNotEqualTo(oauth.dek().getEncoded());
        assertThat(totp.dek().getEncoded()).isNotEqualTo(hmis.dek().getEncoded());
        assertThat(webhook.dek().getEncoded()).isNotEqualTo(oauth.dek().getEncoded());
    }

    // ------------------------------------------------------------------
    // T4 — different tenants get different DEKs for same purpose
    // ------------------------------------------------------------------

    @Test
    @DisplayName("different tenants yield different kids + different DEKs for same purpose")
    void tenantsIsolated() {
        TenantDekService.ActiveDek a =
            tenantDekService.getOrCreateActiveDek(tenantA, KeyPurpose.TOTP);
        TenantDekService.ActiveDek b =
            tenantDekService.getOrCreateActiveDek(tenantB, KeyPurpose.TOTP);

        assertThat(a.kid()).isNotEqualTo(b.kid());
        assertThat(a.dek().getEncoded()).isNotEqualTo(b.dek().getEncoded());
    }

    // ------------------------------------------------------------------
    // T5 — resolveDek returns correct ResolvedDek + same DEK bytes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("resolveDek returns the same tenant, purpose, generation, and DEK bytes as getOrCreateActiveDek")
    void resolveDekRoundTrip() {
        TenantDekService.ActiveDek active =
            tenantDekService.getOrCreateActiveDek(tenantA, KeyPurpose.OAUTH2_CLIENT_SECRET);

        TenantDekService.ResolvedDek resolved =
            tenantDekService.resolveDek(active.kid());

        assertThat(resolved.kid()).isEqualTo(active.kid());
        assertThat(resolved.tenantId()).isEqualTo(tenantA);
        assertThat(resolved.purpose()).isEqualTo(KeyPurpose.OAUTH2_CLIENT_SECRET);
        assertThat(resolved.generation()).isEqualTo(1);
        assertThat(resolved.dek().getEncoded()).containsExactly(active.dek().getEncoded());
    }

    // ------------------------------------------------------------------
    // T6 — resolveDek throws NoSuchElementException for unknown kid
    // ------------------------------------------------------------------

    @Test
    @DisplayName("resolveDek throws NoSuchElementException for an unregistered kid")
    void resolveDekThrowsForUnknownKid() {
        UUID neverRegistered = UUID.randomUUID();

        assertThatThrownBy(() -> tenantDekService.resolveDek(neverRegistered))
            .isInstanceOf(NoSuchElementException.class)
            .hasMessageContaining(neverRegistered.toString());
    }

    // ------------------------------------------------------------------
    // T7 — invalidateTenantDeks evicts both caches
    // ------------------------------------------------------------------

    @Test
    @DisplayName("invalidateTenantDeks evicts both activeDek and resolvedDek caches for the tenant")
    void invalidateTenantDeksEvictsBothCaches() {
        // 1. Prime both caches for tenantA
        TenantDekService.ActiveDek active =
            tenantDekService.getOrCreateActiveDek(tenantA, KeyPurpose.TOTP);
        tenantDekService.resolveDek(active.kid());

        // 2. Also prime tenantB — we want to assert tenantB's caches are NOT touched
        TenantDekService.ActiveDek activeB =
            tenantDekService.getOrCreateActiveDek(tenantB, KeyPurpose.WEBHOOK_SECRET);
        tenantDekService.resolveDek(activeB.kid());

        // 3. DELETE tenantA's row out of band so the next cache-bypassing read
        //    would throw. All three statements (set_config × 2 + DELETE)
        //    must live in ONE transaction so the tx-local GUCs are visible
        //    to the DELETE — set_config(..., true) is wiped at commit, and
        //    JdbcTemplate runs each call in its own auto-commit tx by default.
        //    V82's RESTRICTIVE DELETE policy needs app.tenant_id bound; the
        //    trigger guard needs fabt.shred_in_progress bound to the same id.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Integer deleted = tx.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantA.toString());
            jdbc.queryForObject("SELECT set_config('fabt.shred_in_progress', ?, true)",
                String.class, tenantA.toString());
            return jdbc.update("DELETE FROM tenant_dek WHERE tenant_id = ?", tenantA);
        });
        assertThat(deleted).as("out-of-band DELETE must succeed under bound GUCs").isEqualTo(1);

        // 4. WITHOUT invalidation: cache still serves stale resolved entry
        //    (demonstrates the cache DOES cache — otherwise the next test
        //    step wouldn't be meaningful).
        TenantDekService.ResolvedDek stillCached =
            tenantDekService.resolveDek(active.kid());
        assertThat(stillCached.tenantId())
            .as("stale-cache demonstration: cache still returns the ResolvedDek pre-invalidation")
            .isEqualTo(tenantA);

        // 5. Invalidate tenantA only.
        tenantDekService.invalidateTenantDeks(tenantA);

        // 6. tenantA's resolveDek now misses the cache, tries the DB, fails.
        assertThatThrownBy(() -> tenantDekService.resolveDek(active.kid()))
            .as("post-invalidation, resolveDek misses cache and raises on missing row")
            .isInstanceOf(NoSuchElementException.class);

        // 7. tenantB's cache must remain intact (invalidation was tenant-scoped).
        TenantDekService.ResolvedDek resolvedB =
            tenantDekService.resolveDek(activeB.kid());
        assertThat(resolvedB.tenantId()).isEqualTo(tenantB);
    }

    // ------------------------------------------------------------------
    // T8 — race safety: concurrent first-encrypts converge on one row
    // ------------------------------------------------------------------

    @Test
    @DisplayName("10 concurrent first-encrypts on a fresh tenant+purpose converge on exactly one tenant_dek row")
    void raceSafetyOnFirstEncrypt() throws Exception {
        UUID freshTenant = authHelper.setupSecondaryTenant(
            "dek-race-" + UUID.randomUUID()).getId();

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<UUID> observedKids = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    TenantDekService.ActiveDek a = tenantDekService.getOrCreateActiveDek(
                        freshTenant, KeyPurpose.WEBHOOK_SECRET);
                    observedKids.add(a.kid());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(15, TimeUnit.SECONDS))
            .as("race threads finish within 15s")
            .isTrue();

        assertThat(observedKids).hasSize(threads);
        UUID convergedKid = observedKids.get(0);
        assertThat(observedKids)
            .as("all 10 threads must converge on the same kid via ON CONFLICT DO NOTHING")
            .containsOnly(convergedKid);

        // Exactly one row in tenant_dek for (freshTenant, WEBHOOK_SECRET)
        Integer rowCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM tenant_dek WHERE tenant_id = ? AND purpose = ?",
            Integer.class, freshTenant, "WEBHOOK_SECRET");
        assertThat(rowCount).isEqualTo(1);
    }
}
