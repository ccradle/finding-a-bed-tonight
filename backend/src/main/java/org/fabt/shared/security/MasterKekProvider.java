package org.fabt.shared.security;

import java.util.Base64;
import java.util.Set;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for the {@code FABT_ENCRYPTION_KEY} env var.
 * Both {@link SecretEncryptionService} (Phase 0 platform-key encryption) and
 * {@link KeyDerivationService} (Phase A HKDF-derived per-tenant DEKs)
 * consume this provider so the validation rules — prod-fail-fast,
 * non-prod DEV_KEY fallback, dev-key-prod-rejection, length check — live
 * in exactly one place.
 *
 * <p>Per design D17 of multi-tenant-production-readiness Checkpoint A3:
 * extracting this provider eliminates the duplicate validation that
 * Phase 0 + Phase A2 each carried independently. A future change to
 * (say) the key length, the dev key, or the prod-rejection rule
 * propagates everywhere via the single bean.
 *
 * <p><b>Visibility contract (E1):</b>
 * <ul>
 *   <li>{@link #getPlatformKey()} — public. Returns a {@link SecretKey}
 *       suitable for AES-GCM init; raw bytes never escape this class.
 *       This is the safe surface for backward-compat v0 ciphertext
 *       decryption ({@link CiphertextV0Decoder}, when added in A3.2).</li>
 *   <li>{@link #getMasterKekBytes()} — package-private. Only callable
 *       from inside {@code org.fabt.shared.security}; in practice only
 *       {@link KeyDerivationService} calls it (HKDF needs raw bytes for
 *       the HMAC-SHA256 extract step). An ArchUnit Family A rule will
 *       be added in A3.2 to prevent extra-package callers from
 *       accidentally surfacing the master KEK to a logger or response
 *       body.</li>
 * </ul>
 *
 * <p>Memory hygiene: returns a defensive {@link byte[]#clone()} per call
 * so a downstream zeroize won't disturb the canonical copy. The canonical
 * copy lives in heap until JVM exit; kernel-keyring + zeroize-on-finalize
 * discipline is regulated-tier territory (design D3) and out of scope here.
 */
@Component
public class MasterKekProvider {

    private static final Logger log = LoggerFactory.getLogger(MasterKekProvider.class);

    /**
     * The dev-start.sh key — committed to the public repo. MUST be rejected
     * in the prod profile. Available as a fallback in non-prod profiles so
     * dev / CI continue to function without env-var churn.
     */
    static final String DEV_KEY = "s4FgjCrVQONb65lQmfYHyuvC7AL2VnkVufwB9ZihvlA=";

    /** All derived keys + the platform key are 256-bit AES. */
    private static final int KEY_LENGTH_BYTES = 32;

    private final byte[] keyBytes;

    public MasterKekProvider(
            @Value("${fabt.encryption-key:${fabt.totp.encryption-key:}}") String base64Key,
            Environment environment) {
        Set<String> profiles = Set.of(environment.getActiveProfiles());
        boolean prodProfile = profiles.contains("prod");

        if (base64Key == null || base64Key.isBlank()) {
            if (prodProfile) {
                throw new IllegalStateException(
                        "FABT_ENCRYPTION_KEY is required in the prod profile. "
                        + "Generate with: openssl rand -base64 32. "
                        + "Phase 0 of multi-tenant-production-readiness made credential encryption mandatory.");
            }
            log.warn("FABT_ENCRYPTION_KEY not set in a non-prod profile — falling back to the committed dev key. "
                    + "Do not deploy with this configuration.");
            base64Key = DEV_KEY;
        }

        if (DEV_KEY.equals(base64Key) && prodProfile) {
            throw new IllegalStateException(
                    "FABT_ENCRYPTION_KEY must not use the dev-start.sh key in production. "
                    + "Generate a unique key with: openssl rand -base64 32");
        }

        byte[] decoded = Base64.getDecoder().decode(base64Key);
        if (decoded.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "FABT_ENCRYPTION_KEY must be 32 bytes (256 bits). Got: " + decoded.length);
        }
        this.keyBytes = decoded;
    }

    /**
     * Returns a {@link SecretKey} bound to the master KEK bytes, suitable
     * for AES-256-GCM cipher init. Safe public surface — raw bytes never
     * escape this class through this method.
     */
    public SecretKey getPlatformKey() {
        return new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Package-private accessor for the raw master KEK bytes. Only
     * {@link KeyDerivationService}'s HKDF-Extract step calls this. An
     * ArchUnit Family A rule (A3.2) prevents extra-package callers.
     *
     * <p>Returns a defensive clone — callers that zeroize the array don't
     * disturb the canonical copy.
     */
    byte[] getMasterKekBytes() {
        return keyBytes.clone();
    }
}
