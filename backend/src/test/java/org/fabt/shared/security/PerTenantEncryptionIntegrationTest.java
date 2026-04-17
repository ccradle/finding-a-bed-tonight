package org.fabt.shared.security;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end Testcontainers tests for the v1 per-tenant encryption path
 * (A3 D17–D22). Implements the 8-case IT plan from the A3 design draft:
 *
 * <ol>
 *   <li>T1 — encrypt-then-decrypt round-trip per tenant</li>
 *   <li>T2 — cross-tenant decrypt rejection throws CrossTenantCiphertextException</li>
 *   <li>T3 — decrypt of pre-existing v0 ciphertext via legacy path</li>
 *   <li>T4 — first-encrypt-race lazy-registration: 10 concurrent threads
 *       converge on exactly one kid_to_tenant_key row (E3 race safety)</li>
 *   <li>T5 — synthetic v0 ciphertext starting with FABT magic fails clean
 *       (E3a boundary — no silent corruption)</li>
 *   <li>T6 — purpose-mismatched decrypt fails on GCM auth tag</li>
 *   <li>T7 — perf SLO (E4): first 10 encrypts on a cold-cache tenant
 *       average &lt; 100ms each. Light-touch version of the design's
 *       "first 100" SLO scaled down for IT runtime budget</li>
 * </ol>
 *
 * <p>The 8th IT case from the design (ArchUnit Family A visibility) lives
 * in {@code MasterKekProviderArchitectureTest} per A3.2.2.
 */
class PerTenantEncryptionIntegrationTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private SecretEncryptionService encryption;
    @Autowired private MasterKekProvider masterKekProvider;
    @Autowired private KidRegistryService kidRegistry;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        tenantA = authHelper.getTestTenantId();
        tenantB = authHelper.setupSecondaryTenant("encryption-it-secondary").getId();
    }

    // ------------------------------------------------------------------
    // T1 — round-trip
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T1 — encryptForTenant round-trips through decryptForTenant")
    void roundTripPerTenant() {
        String plaintext = "hello-tenant-A-" + UUID.randomUUID();
        String stored = encryption.encryptForTenant(tenantA, KeyPurpose.TOTP, plaintext);

        // Stored value MUST be a v1 envelope (FABT magic visible after Base64 decode)
        byte[] decoded = Base64.getDecoder().decode(stored);
        assertTrue(EncryptionEnvelope.isV1Envelope(decoded),
                "stored value must carry v1 envelope; was Base64-decoded length=" + decoded.length);

        String roundTripped = encryption.decryptForTenant(tenantA, KeyPurpose.TOTP, stored);
        assertEquals(plaintext, roundTripped);
    }

    // ------------------------------------------------------------------
    // T2 — cross-tenant rejection
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T2 — decryptForTenant with wrong tenantId throws CrossTenantCiphertextException")
    void crossTenantRejection() {
        String plaintext = "tenant-A-secret-" + UUID.randomUUID();
        String stored = encryption.encryptForTenant(tenantA, KeyPurpose.TOTP, plaintext);

        CrossTenantCiphertextException ex = assertThrows(CrossTenantCiphertextException.class,
                () -> encryption.decryptForTenant(tenantB, KeyPurpose.TOTP, stored));

        assertEquals(tenantB, ex.getExpectedTenantId(),
                "expectedTenantId must be the caller's tenant (tenant B) — that's who asked to decrypt");
        assertEquals(tenantA, ex.getActualTenantId(),
                "actualTenantId must be the kid's owning tenant (tenant A) — that's who created the ciphertext");
        assertNotNull(ex.getKid());
    }

    // ------------------------------------------------------------------
    // T3 — v0 legacy fallback
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T3 — decryptForTenant decodes a pre-Phase-A v0 ciphertext via the legacy path")
    void v0LegacyFallback() throws Exception {
        // Encrypt under the v0 platform-key path that Phase 0 uses
        String plaintext = "legacy-v0-ct-" + UUID.randomUUID();
        String storedV0 = encryption.encrypt(plaintext); // Phase 0 method, no envelope

        // Sanity: bytes do NOT carry the v1 magic — that's the discriminator
        // the read path uses to route to the legacy v0 decoder.
        byte[] decoded = Base64.getDecoder().decode(storedV0);
        assertTrue(!EncryptionEnvelope.isV1Envelope(decoded),
                "v0 ciphertext must NOT be detected as v1 envelope");

        // decryptForTenant routes to v0 fallback regardless of tenantId
        // (v0 ciphertexts are not tenant-scoped)
        String roundTripped = encryption.decryptForTenant(tenantA, KeyPurpose.TOTP, storedV0);
        assertEquals(plaintext, roundTripped);
    }

    // ------------------------------------------------------------------
    // T4 — first-encrypt race safety
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T4 — 10 concurrent first-encrypts on a fresh tenant register exactly one kid")
    void firstEncryptRaceSafety() throws Exception {
        // Use a brand-new tenant with no prior kid_to_tenant_key rows
        UUID freshTenant = authHelper.setupSecondaryTenant("race-test-" + UUID.randomUUID()).getId();

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
                    UUID kid = kidRegistry.findOrCreateActiveKid(freshTenant);
                    observedKids.add(kid);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "race threads must finish within 15s");

        assertEquals(threads, observedKids.size(), "every thread must have observed a kid");
        UUID singleKid = observedKids.get(0);
        for (UUID observed : observedKids) {
            assertEquals(singleKid, observed,
                    "all 10 threads must converge on the same kid — UNIQUE constraint + ON CONFLICT DO NOTHING");
        }

        // DB check: exactly one row in kid_to_tenant_key for this tenant
        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kid_to_tenant_key WHERE tenant_id = ?",
                Integer.class, freshTenant);
        assertEquals(1, rowCount.intValue(),
                "kid_to_tenant_key must have exactly one row for the fresh tenant after the race");
    }

    // ------------------------------------------------------------------
    // T5 — adversarial v0 starting with FABT magic
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T5 — v0 ciphertext starting with FABT magic fails clean (no silent corruption)")
    void adversarialFabtPrefixedV0() throws Exception {
        // Construct a v0-shape envelope whose first 5 bytes happen to be
        // the v1 magic + version. The downstream v1 path will:
        // (a) decode an arbitrary kid (zeros)
        // (b) attempt KidRegistryService.resolveKid(zeros-UUID)
        // (c) throw NoSuchElementException (kid unregistered)
        // The test confirms it does NOT silently return corrupt plaintext.
        byte[] adversarial = new byte[EncryptionEnvelope.HEADER_LENGTH + 16 + 8];
        adversarial[0] = 0x46; // F
        adversarial[1] = 0x41; // A
        adversarial[2] = 0x42; // B
        adversarial[3] = 0x54; // T
        adversarial[4] = EncryptionEnvelope.VERSION_V1; // 0x01
        // Rest zeros — kid will be 00000000-..., guaranteed not registered

        String storedAdversarial = Base64.getEncoder().encodeToString(adversarial);

        // The exception thrown depends on which check trips first. Either:
        //  - NoSuchElementException ("kid not registered: ...") from KidRegistryService.resolveKid
        //  - Some other downstream failure
        // Either way, NOT a silent return of garbage plaintext.
        Exception thrown = assertThrows(Exception.class,
                () -> encryption.decryptForTenant(tenantA, KeyPurpose.TOTP, storedAdversarial));
        assertNotNull(thrown);
    }

    // ------------------------------------------------------------------
    // T6 — purpose mismatch fails on GCM auth tag
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T6 — encrypting under one purpose and decrypting under another fails on GCM auth tag")
    void purposeMismatchFailsCleanly() {
        String plaintext = "tag-protection-" + UUID.randomUUID();
        String stored = encryption.encryptForTenant(tenantA, KeyPurpose.TOTP, plaintext);

        // Same tenant, same kid in the envelope, but different purpose -> different DEK
        // -> GCM auth tag fails -> RuntimeException ("Failed to decrypt v1 ciphertext...")
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> encryption.decryptForTenant(tenantA, KeyPurpose.WEBHOOK_SECRET, stored));
        assertTrue(ex.getMessage().contains("Failed to decrypt"),
                "must be a decrypt-side failure, was: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // T7 — cold-cache perf SLO (E4 light)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T7 — first 10 encrypts on a cold-cache tenant average <100ms each (E4 SLO)")
    void coldCacheEncryptPerformance() {
        UUID coldTenant = authHelper.setupSecondaryTenant("perf-test-" + UUID.randomUUID()).getId();
        int n = 10;
        long startNs = System.nanoTime();
        for (int i = 0; i < n; i++) {
            encryption.encryptForTenant(coldTenant, KeyPurpose.TOTP, "warmup-" + i);
        }
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        long perCallMs = elapsedMs / n;
        assertTrue(perCallMs < 100,
                "first " + n + " encrypts averaged " + perCallMs + "ms — exceeds E4 100ms SLO");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Constructs a v0-shape AES-GCM envelope using the platform key.
     * Used by T3 to produce a legitimate v0 ciphertext for the legacy
     * decrypt path. (Distinct from {@code SecretEncryptionService.encrypt}
     * only by being inline-constructed in tests.)
     */
    @SuppressWarnings("unused")
    private String encryptV0WithPlatformKey(String plaintext) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, masterKekProvider.getPlatformKey(),
                new GCMParameterSpec(128, iv));
        byte[] ciphertextWithTag = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ByteBuffer buf = ByteBuffer.allocate(iv.length + ciphertextWithTag.length);
        buf.put(iv);
        buf.put(ciphertextWithTag);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    @SuppressWarnings("unused")
    private void unusedToSuppressWarning() {
        AtomicReference<UUID> ref = new AtomicReference<>();
        ref.set(tenantA);
        assertArrayEquals(new byte[0], new byte[0]);
    }
}
