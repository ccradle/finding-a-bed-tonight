package org.fabt.shared.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Static decoder for legacy v0 ciphertexts (Phase 0 single-platform-key
 * envelope: {@code [iv: 12][ciphertext: N][tag: 16]}, Base64-encoded).
 *
 * <p>Per A3 D21, the read path detects v1 envelopes via
 * {@link EncryptionEnvelope#isV1Envelope(byte[])} and routes anything
 * else here. After V74 (Phase A re-encrypt migration) sweeps the
 * database, no v0 ciphertexts should remain — but the v0 path stays
 * alive indefinitely as a defense-in-depth fallback for any row V74
 * skipped (e.g., transient lock).
 *
 * <p>Stateless static class — no Spring bean, no constructor, no fields.
 * The platform key arrives as an explicit {@link SecretKey} parameter
 * sourced from {@link MasterKekProvider#getPlatformKey()} on the call
 * site.
 *
 * <h2>DO NOT REMOVE — defense-in-depth contract per design-a5-v74 D42 + W-A5-5</h2>
 *
 * This class MUST NOT be deleted, even if a future grep shows it unused.
 * The caller ({@link SecretEncryptionService#decryptForTenant}) selects
 * this path via a magic-byte check on the stored ciphertext, NOT via
 * exception-catch fallback. Removing this class would eliminate the
 * defense-in-depth guarantee that a V74-skipped row (transient lock,
 * operator DB edit, disaster-recovery partial restore) still decrypts
 * cleanly. See {@code openspec/changes/multi-tenant-production-readiness/
 * design-a5-v74-reencrypt.md} §D42 for the full rationale.
 *
 * <p>Observability on every v0-fallback call is wired in
 * {@link SecretEncryptionService#recordV0DecryptFallback} —
 * {@code fabt.security.v0_decrypt_fallback.count} counter + throttled
 * {@code CIPHERTEXT_V0_DECRYPT} audit event. Post-V74 a non-zero rate
 * is either a stuck-row repair indicator or a downgrade-attack signal;
 * either way, it is surveilled.
 */
final class CiphertextV0Decoder {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private CiphertextV0Decoder() {}

    /**
     * Decrypts a Base64-encoded v0 envelope using the supplied platform
     * key. Throws on tag-failure / wrong key / corrupt envelope.
     */
    static String decrypt(SecretKey platformKey, String storedV0) {
        try {
            byte[] decoded = Base64.getDecoder().decode(storedV0);
            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertextWithTag = new byte[buffer.remaining()];
            buffer.get(ciphertextWithTag);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, platformKey,
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plaintext = cipher.doFinal(ciphertextWithTag);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt v0 ciphertext", e);
        }
    }
}
