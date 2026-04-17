package org.fabt.shared.security;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
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

    private static final Logger log = LoggerFactory.getLogger(KeyDerivationService.class);

    /** Per RFC 5869 §2.1 — HMAC-SHA256 outputs 32 bytes. */
    private static final int HMAC_SHA256_OUTPUT_LENGTH = 32;

    /** All derived keys are 32 bytes (AES-256 key length / HMAC-SHA256 input). */
    private static final int DERIVED_KEY_LENGTH = 32;

    /** Version tag for derivation context — bump on derivation-scheme changes. */
    private static final String CONTEXT_VERSION = "v1";

    /** Dev-start.sh key, committed to the public repo. Mirrors {@link SecretEncryptionService#DEV_KEY}. */
    private static final String DEV_KEY = "s4FgjCrVQONb65lQmfYHyuvC7AL2VnkVufwB9ZihvlA=";

    private final byte[] masterKekBytes;

    /**
     * Reads the same {@code FABT_ENCRYPTION_KEY} env var that
     * {@link SecretEncryptionService} validates. Mirrors that service's
     * prod-fail-fast / non-prod-DEV_KEY-fallback semantics so both services
     * agree on which bytes constitute the master KEK in any given environment.
     *
     * <p>Phase A task 2.6 will refactor both services to share a single
     * {@code MasterKekProvider} bean. Until then, the duplicate validation
     * is intentional and the two implementations stay in lockstep.
     */
    public KeyDerivationService(
            @Value("${fabt.encryption-key:${fabt.totp.encryption-key:}}") String base64Key,
            Environment environment) {
        java.util.Set<String> profiles = java.util.Set.of(environment.getActiveProfiles());
        boolean prodProfile = profiles.contains("prod");

        if (base64Key == null || base64Key.isBlank()) {
            if (prodProfile) {
                throw new IllegalStateException(
                        "FABT_ENCRYPTION_KEY is required in the prod profile. "
                        + "Generate with: openssl rand -base64 32. "
                        + "Phase A's KeyDerivationService cannot derive per-tenant DEKs without a master KEK.");
            }
            log.warn("FABT_ENCRYPTION_KEY not set in a non-prod profile — KeyDerivationService falling back to the committed dev key. "
                    + "Do not deploy with this configuration.");
            base64Key = DEV_KEY;
        }

        if (DEV_KEY.equals(base64Key) && prodProfile) {
            throw new IllegalStateException(
                    "FABT_ENCRYPTION_KEY must not use the dev-start.sh key in production. "
                    + "Generate a unique key with: openssl rand -base64 32");
        }

        byte[] decoded = Base64.getDecoder().decode(base64Key);
        if (decoded.length != 32) {
            throw new IllegalArgumentException(
                    "FABT_ENCRYPTION_KEY must be 32 bytes (256 bits). Got: " + decoded.length);
        }
        this.masterKekBytes = decoded;
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
