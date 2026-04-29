package org.fabt.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.tenant.service.TenantLifecycleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;

/**
 * §11.2 of design-f6-real-cryptoshred — property-style crypto-shred
 * regression suite.
 *
 * <p>On each of 10 seeds: create N tenants × 4 purposes (400 ciphertexts
 * total), pick K random tenants to hard-delete, assert K × 4 ciphertexts
 * are unrecoverable and (N−K) × 4 remain intact. N=10, K=2 per seed to
 * keep CI under ~2 minutes while still exercising the distributional
 * property Jordan's pass-1 warroom requested.
 *
 * <p>"Unrecoverable" here means: the happy-path decrypt via
 * {@link SecretEncryptionService#decryptForTenant} throws, AND the
 * adversary-bypass path (raw HKDF re-derivation + AES-GCM) throws on
 * GCM auth-tag failure. Both conditions together prove that:
 *
 * <ul>
 *   <li>The cache + envelope + RLS layers correctly filter the shredded
 *       tenant's ciphertexts.</li>
 *   <li>The underlying random DEK is actually destroyed — the adversary
 *       with {@code master_KEK} + ciphertext cannot recompute a working
 *       key, because the real DEK was random (not HKDF-derived) and
 *       lived only in {@code tenant_dek} (now CASCADE-deleted).</li>
 * </ul>
 *
 * <p>The 10-seed fan-out catches correlations that a single-path test
 * would miss — e.g., a bug where shred of tenant A inadvertently deletes
 * tenant B's DEK via some query-builder path, or where the adversary
 * path succeeds only for specific tenantId bit patterns.
 *
 * <p>Retention-days=0 for the same reason as
 * {@code TenantLifecycleHardDeleteIntegrationTest}: we're testing the
 * crypto property, not the 30-day gate.
 */
@TestPropertySource(properties = {
        "fabt.tenant.lifecycle.enabled=true",
        "fabt.tenant.hard-delete.retention-days=0"
})
@DisplayName("N-tenant property-style crypto-shred regression (F-6.0 §11.2)")
class NTenantCanaryShredTest extends BaseIntegrationTest {

    // Override fabt.tenant.offboard.export-path so each test run writes to
    // an OS-appropriate tmpdir instead of the prod default /var/fabt/exports
    // (which on a Linux CI runner requires root to create). Matches the
    // pattern in TenantLifecycleHardDeleteIntegrationTest + TenantLifecycle-
    // OffboardArchiveIntegrationTest.
    @TempDir
    static Path tempExportRoot;

    @DynamicPropertySource
    static void exportPath(DynamicPropertyRegistry registry) {
        registry.add("fabt.tenant.offboard.export-path", () -> tempExportRoot.toString());
    }

    @Autowired private TestAuthHelper authHelper;
    @Autowired private TenantLifecycleService lifecycleService;
    @Autowired private TenantDekService tenantDekService;
    @Autowired private SecretEncryptionService encryption;
    @Autowired private KeyDerivationService keyDerivation;

    /**
     * 10 distinct PRNG seeds. Each run creates a fresh set of tenants
     * + canaries, so cross-run state isn't a concern; the seed only
     * controls which tenants get shredded.
     */
    @ParameterizedTest(name = "seed={0}")
    @ValueSource(longs = {1L, 2L, 3L, 5L, 8L, 13L, 21L, 34L, 55L, 89L})
    void shreddedTenants_areUnrecoverable_survivorsRoundTrip(long seed) throws Exception {
        // N and K chosen so the test runs in ~10s per seed; adjust if CI
        // budget tightens. The property-shape invariants hold for any
        // N > K >= 1; the specific values exist only to bound wall-clock.
        final int N = 10;
        final int K = 2;
        Random rng = new Random(seed);

        List<UUID> tenants = new ArrayList<>();
        // ciphertexts[tenantIndex][purposeOrdinal] = envelope string
        Map<UUID, Map<KeyPurpose, String>> ciphertexts = new LinkedHashMap<>();
        Map<UUID, Map<KeyPurpose, String>> canaries = new LinkedHashMap<>();
        // purposes to exercise — the 4 data-encryption purposes (JWT_SIGN is
        // rejected at the service boundary and has its own separate test).
        KeyPurpose[] dataPurposes = {
                KeyPurpose.TOTP, KeyPurpose.WEBHOOK_SECRET,
                KeyPurpose.OAUTH2_CLIENT_SECRET, KeyPurpose.HMIS_API_KEY
        };

        for (int i = 0; i < N; i++) {
            UUID tenantId = lifecycleService.create(
                    "N-tenant-canary seed=" + seed + " idx=" + i,
                    "ncanary-" + seed + "-" + i + "-" + UUID.randomUUID(),
                    UUID.randomUUID()).getId();
            tenants.add(tenantId);
            Map<KeyPurpose, String> tenantCiphertexts = new LinkedHashMap<>();
            Map<KeyPurpose, String> tenantCanaries = new LinkedHashMap<>();
            for (KeyPurpose purpose : dataPurposes) {
                String canary = "canary-" + seed + "-" + i + "-" + purpose + "-" + UUID.randomUUID();
                tenantCanaries.put(purpose, canary);
                tenantCiphertexts.put(purpose,
                        encryption.encryptForTenant(tenantId, purpose, canary));
            }
            ciphertexts.put(tenantId, tenantCiphertexts);
            canaries.put(tenantId, tenantCanaries);
        }

        // Pre-shred sanity: every tenant round-trips (catches encryption-
        // refactor regressions before the shred step masks them).
        for (UUID tenantId : tenants) {
            for (KeyPurpose purpose : dataPurposes) {
                String roundTripped = encryption.decryptForTenant(
                        tenantId, purpose, ciphertexts.get(tenantId).get(purpose));
                assertThat(roundTripped)
                        .as("pre-shred: canary round-trips for tenant %s purpose %s",
                                tenantId, purpose)
                        .isEqualTo(canaries.get(tenantId).get(purpose));
            }
        }

        // Pick K random tenants to shred. Use the seeded RNG so the
        // specific failing-seed case is reproducible in CI logs.
        List<UUID> shuffled = new ArrayList<>(tenants);
        Collections.shuffle(shuffled, rng);
        Set<UUID> victims = new HashSet<>(shuffled.subList(0, K));

        UUID actor = UUID.randomUUID();
        for (UUID victim : victims) {
            lifecycleService.offboard(victim, actor, "n-tenant-canary-test");
            lifecycleService.archive(victim, actor, "n-tenant-canary-test");
            lifecycleService.hardDelete(victim, actor, "n-tenant-canary-test");
        }

        // ─── Invariant 1: victim ciphertexts unrecoverable ────────────────
        // Both happy-path and adversary-bypass must fail for every
        // (victim, purpose) combination.
        for (UUID victim : victims) {
            for (KeyPurpose purpose : dataPurposes) {
                String ciphertext = ciphertexts.get(victim).get(purpose);

                // Happy path — must throw. Under Option A post-shred this is
                // NoSuchElementException-wrapped-as-CrossTenantCiphertextException
                // (kid not in tenant_dek anymore) OR a RuntimeException if a
                // downstream path differs; either way, not a clean decrypt.
                assertThatThrownBy(() ->
                        encryption.decryptForTenant(victim, purpose, ciphertext))
                        .as("post-shred happy-path decrypt MUST throw for victim %s purpose %s seed=%d",
                                victim, purpose, seed);

                // Adversary path — raw HKDF re-derive + AES-GCM. Under
                // Option A the random tenant_dek DEK was the actual
                // encryption key; HKDF yields a DIFFERENT key, GCM tag
                // fails with BadPaddingException (or subclass
                // AEADBadTagException).
                assertThatThrownBy(() -> adversaryHkdfDecrypt(victim, purpose, ciphertext))
                        .as("post-shred adversary HKDF-bypass MUST throw for victim %s purpose %s seed=%d",
                                victim, purpose, seed)
                        .isInstanceOfAny(javax.crypto.BadPaddingException.class,
                                javax.crypto.IllegalBlockSizeException.class);
            }
        }

        // ─── Invariant 2: survivor ciphertexts still round-trip ──────────
        // The shred must be surgical — only victims' DEKs destroyed, not
        // survivors'. If a bug deleted the wrong DEK rows, survivor
        // round-trips would fail here.
        for (UUID tenantId : tenants) {
            if (victims.contains(tenantId)) continue;
            for (KeyPurpose purpose : dataPurposes) {
                String ciphertext = ciphertexts.get(tenantId).get(purpose);
                String roundTripped = encryption.decryptForTenant(
                        tenantId, purpose, ciphertext);
                assertThat(roundTripped)
                        .as("post-shred: survivor %s purpose %s must still round-trip (seed=%d)",
                                tenantId, purpose, seed)
                        .isEqualTo(canaries.get(tenantId).get(purpose));
            }
        }
    }

    /**
     * Adversary helper — bypasses {@link SecretEncryptionService} entirely
     * and attempts raw HKDF-derive + AES-GCM decrypt against the v1 envelope.
     * Models a pg_dump + leaked-env-var attacker. Under Option A this path
     * FAILS because the real encryption DEK was random (not HKDF), and lived
     * only in the tenant_dek row that was CASCADE-deleted.
     */
    private String adversaryHkdfDecrypt(UUID tenantId, KeyPurpose purpose,
                                         String storedCiphertext) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(storedCiphertext);
        if (!EncryptionEnvelope.isV1Envelope(decoded)) {
            throw new IllegalStateException(
                    "test precondition: encryptForTenant should have produced v1 envelope");
        }
        EncryptionEnvelope envelope = EncryptionEnvelope.decode(storedCiphertext);
        javax.crypto.SecretKey hkdfKey = switch (purpose) {
            case TOTP -> keyDerivation.deriveTotpKey(tenantId);
            case WEBHOOK_SECRET -> keyDerivation.deriveWebhookSecretKey(tenantId);
            case OAUTH2_CLIENT_SECRET -> keyDerivation.deriveOauth2ClientSecretKey(tenantId);
            case HMIS_API_KEY -> keyDerivation.deriveHmisApiKey(tenantId);
            case JWT_SIGN -> throw new IllegalStateException(
                    "JWT_SIGN is not a data-encryption purpose");
            // RESERVATION_PII (V93): random-DEK only by design (task 2.3a) —
            // there is intentionally no `deriveReservationPiiKey` HKDF method.
            // This switch case is only here to satisfy Java's exhaustive-enum
            // requirement; it is unreachable today because RESERVATION_PII is
            // NOT in `dataPurposes` (line 112-115). The crypto-shred property
            // for this purpose IS load-bearing and MUST be verified —
            // TODO(transitional-reentry-support task 13.13): extend canary
            // coverage to RESERVATION_PII via the round-trip + cross-tenant-
            // ciphertext invariant test. The HKDF-adversary half of THIS
            // canary won't apply (no HKDF key exists for the purpose), so
            // task 13.13 uses a different proof shape (ciphertext != plaintext
            // at the byte level + CrossTenantCiphertextException on
            // wrong-tenant decrypt).
            case RESERVATION_PII -> throw new IllegalStateException(
                    "RESERVATION_PII not in dataPurposes; reaching this branch indicates a future test extension landed without updating the adversary model — see TODO above and task 13.13");
        };
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, hkdfKey,
                new javax.crypto.spec.GCMParameterSpec(128, envelope.iv()));
        byte[] plaintext = cipher.doFinal(envelope.ciphertextWithTag());
        return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
    }
}
