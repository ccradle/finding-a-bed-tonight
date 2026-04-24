package org.fabt.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F-6 CRYPTO-SHRED ANCHOR — pins the crypto-shred property.
 *
 * <p><b>History:</b> this test started life (commit {@code b5672da}) as a
 * red-state TDD anchor pinning the gap in the Phase A HKDF-DEK derivation:
 * deleting {@code tenant_key_material} + {@code kid_to_tenant_key} closed
 * the happy-path decrypt but destroyed nothing the adversary needed. An
 * attacker with {@code FABT_ENCRYPTION_KEY} and any pre-shred ciphertext
 * could rederive the DEK via public HKDF and recover plaintext in ms.
 *
 * <p><b>Post-F-6.0:</b> with V82 ({@code tenant_dek}) + V83 (re-encrypt) +
 * V84 (CASCADE chain) + the {@code SecretEncryptionService} refactor +
 * {@code TenantLifecycleService.hardDelete} all shipped, the test now
 * PINS the property instead of pinning the gap. Adversary's raw-HKDF
 * derivation path produces a key that doesn't match the ciphertext's
 * real (random) DEK; GCM auth tag fails; plaintext is unrecoverable.
 *
 * <p><b>What this test guards against:</b> a regression that puts the
 * data-encryption path back on deterministic HKDF derivation. If
 * {@code encryptForTenant} ever routes through {@link KeyDerivationService}
 * directly again (bypassing {@link TenantDekService}), the adversary's
 * path would start recovering canaries and this assertion fires with the
 * recovered plaintext in the failure message.
 *
 * <p>See {@code openspec/changes/multi-tenant-production-readiness/design-f6-real-cryptoshred.md}
 * §2 (threat model) + §11.1 (test strategy).
 */
class CryptoShredGapIntegrationTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private SecretEncryptionService encryption;
    @Autowired private KeyDerivationService keyDerivation;
    @Autowired private KidRegistryService kidRegistry;
    @Autowired private TenantDekService tenantDekService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void cryptoShred_plaintextMustBeUnrecoverable_evenByDirectHkdfAdversary() throws Exception {
        // ──────────────────────────────────────────────────────────────────
        // 1. Seed a tenant, encrypt a canary under the v1 per-tenant path.
        //    Uses TOTP purpose — the choice is arbitrary; every KeyPurpose
        //    resolves through the same deterministic HKDF and has the same
        //    gap.
        // ──────────────────────────────────────────────────────────────────
        UUID tenantId = authHelper.setupSecondaryTenant(
                "shred-gap-" + UUID.randomUUID()).getId();
        String canary = "SHRED-CANARY-" + UUID.randomUUID();

        String ciphertext = encryption.encryptForTenant(tenantId, KeyPurpose.TOTP, canary);

        // Pre-shred sanity: the happy path works.
        String roundTripped = encryption.decryptForTenant(tenantId, KeyPurpose.TOTP, ciphertext);
        assertThat(roundTripped)
            .as("baseline: decryptForTenant must round-trip pre-shred")
            .isEqualTo(canary);

        // ──────────────────────────────────────────────────────────────────
        // 2. Simulate the post-F-6.0 crypto-shred: destroy everything
        //    TenantLifecycleService.hardDelete destroys. Under Option A
        //    (V82+V83+V84) the shred surface is tenant_dek — the random
        //    per-tenant DEK wrapped under an HKDF-derived key lives there
        //    and nowhere else. Deleting the row destroys the only copy of
        //    the DEK. The pre-F-6 registry rows (kid_to_tenant_key,
        //    tenant_key_material) are also cleared to mirror the full
        //    CASCADE chain a real `DELETE FROM tenant` would fire.
        // ──────────────────────────────────────────────────────────────────
        UUID kidBeforeShred = kidRegistry.findOrCreateActiveKid(tenantId);

        // RLS + trigger guard: tenant_dek has both the V68-style RESTRICTIVE
        // DELETE policy (requires app.tenant_id = tenant_id) AND a BEFORE
        // DELETE trigger that requires fabt.shred_in_progress = tenant_id.
        // Bind both GUCs in the same tx so the DELETE matches the exact
        // posture hardDelete's CASCADE path provides.
        TransactionTemplate shredTx = new TransactionTemplate(transactionManager);
        shredTx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantId.toString());
            jdbc.queryForObject("SELECT set_config('fabt.shred_in_progress', ?, true)",
                String.class, tenantId.toString());
            // Option A's shred surface — this is what carries the crypto-shred
            // guarantee. Deleting these rows renders the wrapped DEKs
            // unrecoverable.
            jdbc.update("DELETE FROM tenant_dek WHERE tenant_id = ?", tenantId);
            // Legacy registry rows — no longer hold data-encryption keys
            // but still part of the CASCADE chain real hardDelete fires.
            jdbc.update("DELETE FROM kid_to_tenant_key WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM tenant_key_material WHERE tenant_id = ?", tenantId);
        });
        kidRegistry.invalidateTenantActiveKid(tenantId);
        kidRegistry.invalidateKidResolution(kidBeforeShred);
        tenantDekService.invalidateTenantDeks(tenantId);

        // ──────────────────────────────────────────────────────────────────
        // 3. Happy-path decrypt is blocked — envelope check rejects the
        //    now-unknown kid. This is what the §D11 design relied on to
        //    claim crypto-shred. It IS a real protection for any caller
        //    going through SecretEncryptionService.
        // ──────────────────────────────────────────────────────────────────
        assertThatThrownBy(() ->
                encryption.decryptForTenant(tenantId, KeyPurpose.TOTP, ciphertext))
            .as("post-shred: happy-path decrypt must fail at kid lookup")
            .isInstanceOf(CrossTenantCiphertextException.class);

        // ──────────────────────────────────────────────────────────────────
        // 4. ADVERSARY SIMULATION — the real gap.
        //
        //    Model: attacker with access to FABT_ENCRYPTION_KEY (env dump,
        //    .env file in a backup, process memory) and a pre-shred DB
        //    backup containing `ciphertext`. Skips SecretEncryptionService
        //    entirely; reconstructs the DEK directly from public inputs
        //    (tenantId + purpose, both recoverable — tenantId lives in the
        //    envelope's kid lookup or the backup's other tables; purpose
        //    is implicit in which column the ciphertext came from).
        //
        //    Every piece of state the adversary uses here is either:
        //      - public (envelope wire format, purpose string)
        //      - in the backup (tenantId, ciphertext bytes)
        //      - on the running host (master KEK)
        //    Under Option A, the DEK lives only in tenant_dek (now deleted)
        //    AND was random (not HKDF-derived). The adversary's HKDF-derive
        //    path recomputes a DIFFERENT key than the ciphertext was
        //    encrypted under — the GCM auth tag fails.
        // ──────────────────────────────────────────────────────────────────
        String adversaryRecovered;
        try {
            adversaryRecovered = adversaryDirectHkdfDecrypt(tenantId, ciphertext);
        } catch (javax.crypto.BadPaddingException | javax.crypto.IllegalBlockSizeException gcmFailure) {
            // Under Option A the GCM tag check fails when the adversary's
            // HKDF-derived key is applied to a ciphertext that was encrypted
            // under a DIFFERENT (random) DEK. That IS the crypto-shred
            // guarantee we want. BadPaddingException covers AEADBadTagException
            // (subclass); IllegalBlockSizeException covers the padding-alignment
            // failure path. Any OTHER exception (NPE from a refactor,
            // Testcontainer timeout, etc.) is a genuine test bug and should
            // propagate as an ERROR, not be silently coerced to "passed."
            // Warroom pass-3 Jordan blocker fix.
            adversaryRecovered = "(unrecoverable: " + gcmFailure.getClass().getSimpleName() + ")";
        }

        // THE ANCHOR ASSERTION — the crypto-shred claim's core invariant.
        //
        // Pre-Option A: adversaryRecovered == canary (gap). Now that V82 +
        // V83 + V84 + TenantDekService are live, the shred actually destroys
        // the DEK material and the adversary's HKDF-derive path hits a
        // ciphertext it cannot decrypt.
        assertThat(adversaryRecovered)
            .as("""
                CRYPTO-SHRED ANCHOR: adversary recovered `%s` post-shred.
                Under Option A the data-encryption DEK was random (not HKDF),
                lived only in tenant_dek, and was destroyed by the simulated
                shred. If this assertion fails the recovered canary is the
                regression signal — the gap has reopened. See
                openspec/changes/multi-tenant-production-readiness/design-f6-real-cryptoshred.md
                §2 threat model + §11.1 test strategy.
                """.formatted(canary))
            .isNotEqualTo(canary);
    }

    // ─── Adversary helper ────────────────────────────────────────────────

    /**
     * Bypasses every layer of {@link SecretEncryptionService} and recovers
     * plaintext from the v1 envelope using only:
     * <ul>
     *   <li>the master KEK (via the autowired {@link KeyDerivationService})</li>
     *   <li>the tenantId (known to the adversary — lives in DB backups)</li>
     *   <li>the purpose tag (here: "totp", a public string in
     *       {@link KeyPurpose})</li>
     *   <li>the envelope's own IV + ciphertext+tag</li>
     * </ul>
     * The adversary does NOT touch kid_to_tenant_key, tenant_key_material,
     * or {@code SecretEncryptionService.decryptForTenant}. If this method
     * returns the canary, the §D11 crypto-shred claim is false.
     */
    private String adversaryDirectHkdfDecrypt(UUID tenantId, String storedCiphertext)
            throws Exception {
        byte[] decoded = Base64.getDecoder().decode(storedCiphertext);
        if (!EncryptionEnvelope.isV1Envelope(decoded)) {
            throw new IllegalStateException(
                    "test precondition: expected v1 envelope from encryptForTenant");
        }
        EncryptionEnvelope envelope = EncryptionEnvelope.decode(storedCiphertext);

        // Raw HKDF — KeyPurpose.TOTP resolves to deriveTotpKey; using the
        // typed accessor rather than reflection to keep the adversary's
        // attack realistic: they don't need internals, they need the
        // public derivation contract, which is documented in the class
        // javadoc and stable across versions.
        SecretKey directlyDerivedDek = keyDerivation.deriveTotpKey(tenantId);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, directlyDerivedDek,
                new GCMParameterSpec(128, envelope.iv()));
        byte[] plaintext = cipher.doFinal(envelope.ciphertextWithTag());
        return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
    }
}
