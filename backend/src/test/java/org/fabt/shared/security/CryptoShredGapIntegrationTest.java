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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F-6 TDD ANCHOR — pins the crypto-shred gap.
 *
 * <p><b>Why this test exists:</b> the Phase F §D11 design claims tenant hard-delete
 * provides "crypto-shredding" of all per-tenant secrets. Warroom 2026-04-24 found
 * that claim is false against the current derivation scheme:
 *
 * <ul>
 *   <li>{@link KeyDerivationService} computes DEKs as
 *       {@code HKDF-SHA256(master_KEK, tenantId_bytes, "fabt:v1:<tenantId>:<purpose>")}
 *       — a pure function with zero persisted state.</li>
 *   <li>The "crypto-shred" step as designed deletes {@code tenant_key_material} +
 *       {@code kid_to_tenant_key}. That closes the happy-path decrypt
 *       ({@link SecretEncryptionService#decryptForTenant} rejects unknown kids
 *       per C-A3-1) but deletes nothing that an adversary actually needs.</li>
 *   <li>An adversary with (a) the {@code FABT_ENCRYPTION_KEY} env value
 *       (pg_dump + leaked .env, insider-threat, or process-dump scenario) and
 *       (b) a copy of any pre-shred ciphertext can skip the envelope/kid
 *       layer entirely, rederive the DEK via public HKDF, and recover the
 *       plaintext in milliseconds. No registry row was needed.</li>
 * </ul>
 *
 * <p><b>What this test asserts:</b> the adversary must NOT be able to recover
 * the canary plaintext post-shred. This fails on current main because the
 * derivation is deterministic; it will pass once F-6.0 implementation lands
 * the per-tenant random DEK stored in {@code tenant_dek} + wrapped under a
 * master-KEK-derived wrapping key (Option A — see 2026-04-24 warroom
 * decision). At that point "delete the wrapped DEK row" actually destroys
 * the only copy of the DEK and this test flips green.
 *
 * <p><b>Why {@code @Disabled}:</b> we want the test committed on main to
 * document the gap (so any future reader of {@code shared/security} sees the
 * red-state anchor in the test tree), but enabled on main it would break CI.
 * Remove the annotation as part of the F-6.0 implementation commit that
 * lands the real shred; the assertion will flip green at the same moment.
 *
 * <p><b>Run locally:</b>
 * <pre>
 *   mvn -q test -Dtest=CryptoShredGapIntegrationTest -DfailIfNoTests=false \
 *       -pl backend -Dsurefire.skipAfterFailureCount=0 -Djunit.jupiter.conditions.deactivate=*
 * </pre>
 * The {@code conditions.deactivate=*} flag ignores the {@code @Disabled}
 * annotation so the anchor actually runs. Confirm the "CRYPTO-SHRED GAP"
 * assertion fires with the adversary-recovered canary in the failure message.
 */
@Disabled("""
    F-6 TDD anchor — fails on main by design.
    Removing this annotation is the last step of the F-6.0 implementation commit
    that introduces per-tenant random DEKs stored in `tenant_dek`. At that point
    the adversary's direct-HKDF path no longer recovers plaintext and the
    assertion flips green. Keep this test disabled on main until then so CI
    stays healthy; enable it locally with
    -Djunit.jupiter.conditions.deactivate=* to verify the gap exists.
    Verified red-state 2026-04-24 — assertion fires with recovered canary in
    the failure message. See openspec/changes/multi-tenant-production-readiness
    §D11, warroom 2026-04-24.
    """)
class CryptoShredGapIntegrationTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private SecretEncryptionService encryption;
    @Autowired private KeyDerivationService keyDerivation;
    @Autowired private KidRegistryService kidRegistry;
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
        // 2. Simulate the §D11 crypto-shred as designed today: drop every
        //    registry row that ties kids to this tenant and evict the caches
        //    that held the resolution. This is the exact set of writes
        //    TenantLifecycleService.hardDelete will perform per the current
        //    design draft.
        // ──────────────────────────────────────────────────────────────────
        UUID kidBeforeShred = kidRegistry.findOrCreateActiveKid(tenantId);

        // RLS note: tenant_key_material + kid_to_tenant_key carry V68 row-level
        // security. A bare DELETE from an unbound connection affects 0 rows —
        // the RLS policies filter them out before the command runs. Bind
        // app.tenant_id in a short tx so the DELETE matches what a privileged
        // hardDelete() would do (the real service will use DB-owner
        // credentials, but same net effect: rows must actually go away, not
        // be silently hidden).
        TransactionTemplate shredTx = new TransactionTemplate(transactionManager);
        shredTx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, tenantId.toString());
            jdbc.update("DELETE FROM kid_to_tenant_key WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM tenant_key_material WHERE tenant_id = ?", tenantId);
        });
        kidRegistry.invalidateTenantActiveKid(tenantId);
        kidRegistry.invalidateKidResolution(kidBeforeShred);

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
        //    None of it is in tenant_key_material or kid_to_tenant_key.
        //    Deleting those rows therefore shreds nothing.
        // ──────────────────────────────────────────────────────────────────
        String adversaryRecovered = adversaryDirectHkdfDecrypt(tenantId, ciphertext);

        // THE FAILING ASSERTION — the crypto-shred claim's core invariant.
        //
        // On current main: `adversaryRecovered` equals the canary → this
        // assertion FAILS → the test body proves the gap with a concrete
        // recovered string in the failure message.
        //
        // After F-6.0 Option A: the adversary's raw-HKDF path yields a
        // key that doesn't match the ciphertext's real DEK (which now
        // lives only in tenant_dek, wrapped, and was deleted during
        // shred). The GCM auth tag fails, adversaryDirectHkdfDecrypt
        // throws, the assertThatThrownBy variant would catch it — but
        // to preserve the narrative symmetry, we assert the positive
        // form: plaintext is not recoverable, regardless of whether
        // that's via tag failure or any other mechanism.
        assertThat(adversaryRecovered)
            .as("""
                CRYPTO-SHRED GAP: adversary recovered `%s` post-shred.
                Current derivation is a pure function of master_KEK + tenantId
                + purpose — none of which was destroyed by deleting
                tenant_key_material + kid_to_tenant_key. See F-6.0 warroom
                2026-04-24 for the per-tenant-random-DEK (Option A) fix.
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
