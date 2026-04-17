package org.fabt.shared.security;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

/**
 * HKDF-SHA256 derivation of per-tenant data encryption keys (DEKs) and
 * per-tenant JWT signing keys (JWKs) from a single platform master KEK
 * (the same {@code FABT_ENCRYPTION_KEY} env var Phase 0 already validates
 * via {@link SecretEncryptionService}).
 *
 * <p>Per design D2 of multi-tenant-production-readiness:
 * <pre>
 *   derivedKey = HKDF-SHA256(
 *       ikm  = master-KEK bytes,
 *       salt = tenant-UUID bytes (16 bytes),
 *       info = "fabt:v1:&lt;tenant-uuid&gt;:&lt;purpose&gt;",
 *       L    = 32 bytes
 *   )
 * </pre>
 *
 * <p>The {@code v1} version tag in the {@code info} string is intentional:
 * future migrations to a different derivation scheme (e.g., HKDF-SHA384,
 * different salt input) bump the tag, allowing dual-derivation grace
 * windows without disturbing existing ciphertexts.
 *
 * <p>Determinism: same {@code (master-KEK, tenant-UUID, purpose)} always
 * yields the same 32-byte output. This is the core property exploited
 * by Phase A's encryption refactor — DEKs are never persisted; they are
 * recomputed on every encrypt/decrypt call. Loss of a per-tenant DEK is
 * a non-event as long as the master KEK is intact.
 *
 * <p>Separation: changing any of the three inputs produces a different
 * key. Cross-tenant contamination is cryptographically prevented because
 * Tenant A's purpose-{@code "totp"} DEK and Tenant B's purpose-{@code "totp"}
 * DEK derive from different salts.
 *
 * <p>Implementation: RFC 5869 HKDF directly via {@link Mac} HMAC-SHA256.
 * No external crypto library dependency — the algorithm is ~30 lines of
 * standard JDK calls. Avoids supply-chain risk of pulling in BouncyCastle
 * for what is fundamentally a two-step HMAC chain.
 */
@Service
public class KeyDerivationService {

    /** Per RFC 5869 §2.1 — HMAC-SHA256 outputs 32 bytes. */
    private static final int HMAC_SHA256_OUTPUT_LENGTH = 32;

    /** All derived keys are 32 bytes (AES-256 key length / HMAC-SHA256 input). */
    private static final int DERIVED_KEY_LENGTH = 32;

    /** Version tag for derivation context — bump on derivation-scheme changes. */
    private static final String CONTEXT_VERSION = "v1";

    private final byte[] masterKekBytes;

    /**
     * Reads master KEK bytes from {@link MasterKekProvider}, the single
     * source of truth for {@code FABT_ENCRYPTION_KEY} validation. Per
     * design D17 (A3 design draft) — the previous duplicate validation
     * (prod-fail-fast / non-prod-DEV_KEY-fallback / wrong-length /
     * dev-key-prod-rejection) now lives in one place.
     */
    public KeyDerivationService(MasterKekProvider masterKekProvider) {
        this.masterKekBytes = masterKekProvider.getMasterKekBytes();
    }

    // -----------------------------------------------------------------
    // Public typed derivation methods — one per canonical purpose.
    //
    // Per Marcus warroom W3: an unbounded String purpose parameter is a
    // footgun (typo "jwt-sigm" silently derives a different deterministic
    // key, breaking the JWT contract). The compiler-enforced typed API
    // forces every call site to pick from a closed set, eliminating typo
    // and made-up-purpose risks at build time.
    //
    // To add a new purpose: add a public deriveXxxKey method here and a
    // matching string constant. ArchUnit Family F (task 13.3, Phase L)
    // will additionally restrict who can extend this class.
    // -----------------------------------------------------------------

    /** Derives the per-tenant JWT signing key (HMAC-SHA256). */
    public SecretKey deriveJwtSigningKey(UUID tenantId) {
        return deriveKey(tenantId, "jwt-sign");
    }

    /** Derives the per-tenant TOTP shared-secret encryption DEK (AES-256-GCM). */
    public SecretKey deriveTotpKey(UUID tenantId) {
        return deriveKey(tenantId, "totp");
    }

    /** Derives the per-tenant webhook-callback secret encryption DEK (AES-256-GCM). */
    public SecretKey deriveWebhookSecretKey(UUID tenantId) {
        return deriveKey(tenantId, "webhook-secret");
    }

    /** Derives the per-tenant OAuth2 client-secret encryption DEK (AES-256-GCM). */
    public SecretKey deriveOauth2ClientSecretKey(UUID tenantId) {
        return deriveKey(tenantId, "oauth2-client-secret");
    }

    /** Derives the per-tenant HMIS API-key encryption DEK (AES-256-GCM). */
    public SecretKey deriveHmisApiKey(UUID tenantId) {
        return deriveKey(tenantId, "hmis-api-key");
    }

    /**
     * Derives a 32-byte key for the given tenant + purpose. Private; callers
     * must use the typed {@code deriveXxxKey} methods above so the purpose
     * value is constrained at compile time. Same-input determinism is the
     * core property exploited by the encryption refactor: DEKs are never
     * persisted; they are recomputed on every encrypt/decrypt call.
     *
     * <p>Package-private rather than {@code private} only so the test in
     * the same package can exercise separation-by-purpose against arbitrary
     * purpose strings. Application code outside this package must go
     * through the typed methods above.
     */
    SecretKey deriveKey(UUID tenantId, String purpose) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must be non-null");
        }
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("purpose must be non-blank");
        }

        byte[] salt = uuidToBytes(tenantId);
        byte[] info = buildContext(tenantId, purpose);
        byte[] derivedBytes = hkdfSha256(masterKekBytes, salt, info, DERIVED_KEY_LENGTH);
        return new SecretKeySpec(derivedBytes, "AES");
    }

    /** Builds the canonical context string {@code "fabt:v1:<tenant-uuid>:<purpose>"}. */
    private static byte[] buildContext(UUID tenantId, String purpose) {
        String context = "fabt:" + CONTEXT_VERSION + ":" + tenantId + ":" + purpose;
        return context.getBytes(StandardCharsets.UTF_8);
    }

    /** Encodes a UUID as its 16-byte canonical big-endian representation. */
    private static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
        return buf.array();
    }

    /**
     * RFC 5869 HKDF-SHA256. Two steps:
     * <ol>
     *   <li><b>Extract</b>: PRK = HMAC-SHA256(salt, ikm)</li>
     *   <li><b>Expand</b>: T(N) = HMAC-SHA256(PRK, T(N-1) || info || N)
     *       — chained until L bytes accumulated; T(0) is empty.</li>
     * </ol>
     * Package-private so {@code KeyDerivationServiceKatTest} can validate
     * against RFC 5869 Appendix A test vectors directly without reflection.
     */
    static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int outputLength) {
        if (outputLength <= 0 || outputLength > 255 * HMAC_SHA256_OUTPUT_LENGTH) {
            throw new IllegalArgumentException("outputLength out of range: " + outputLength);
        }
        try {
            // Per RFC 5869 §2.2: if salt is not provided, it is set to a
            // string of HashLen (32) zeros. JCE rejects empty-byte SecretKeySpec
            // with "Empty key", so we substitute the spec-mandated default.
            byte[] effectiveSalt = (salt == null || salt.length == 0)
                    ? new byte[HMAC_SHA256_OUTPUT_LENGTH]
                    : salt;

            // Extract — PRK = HMAC-SHA256(salt, ikm)
            Mac extractMac = Mac.getInstance("HmacSHA256");
            extractMac.init(new SecretKeySpec(effectiveSalt, "HmacSHA256"));
            byte[] prk = extractMac.doFinal(ikm);

            // Expand — T(N) = HMAC-SHA256(PRK, T(N-1) || info || byte(N))
            // Chained until L bytes accumulated; T(0) is empty.
            int blocks = (outputLength + HMAC_SHA256_OUTPUT_LENGTH - 1) / HMAC_SHA256_OUTPUT_LENGTH;
            Mac expandMac = Mac.getInstance("HmacSHA256");
            expandMac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] result = new byte[outputLength];
            byte[] previousBlock = new byte[0];
            int written = 0;
            for (int i = 1; i <= blocks; i++) {
                expandMac.reset();
                expandMac.update(previousBlock);
                expandMac.update(info);
                expandMac.update((byte) i);
                previousBlock = expandMac.doFinal();
                int copyLen = Math.min(HMAC_SHA256_OUTPUT_LENGTH, outputLength - written);
                System.arraycopy(previousBlock, 0, result, written, copyLen);
                written += copyLen;
            }
            return result;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HKDF-SHA256 failed — JDK should ship HmacSHA256", e);
        }
    }
}
