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

    /**
     * Derives a 32-byte key for the given tenant + purpose. Deterministic:
     * same inputs always yield the same output. Throws on null/empty inputs.
     *
     * @param tenantId  the tenant whose key is being derived (used as both salt and info component)
     * @param purpose   the key's role — one of {@code jwt-sign}, {@code totp},
     *                  {@code webhook-secret}, {@code oauth2-client-secret},
     *                  {@code hmis-api-key}, or future additions
     * @return a 32-byte {@link SecretKey} suitable for AES-256-GCM or HMAC-SHA256
     */
    public SecretKey deriveKey(UUID tenantId, String purpose) {
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
     *   <li><b>Expand</b>: T(1) = HMAC-SHA256(PRK, info || 0x01)
     *       — single block since L &le; 32 bytes for our use case</li>
     * </ol>
     * For L &gt; 32 bytes, additional T(N) blocks would chain T(N-1) || info || N.
     * We don't need that here — derived keys are always 32 bytes.
     */
    private static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int outputLength) {
        if (outputLength <= 0 || outputLength > 255 * HMAC_SHA256_OUTPUT_LENGTH) {
            throw new IllegalArgumentException("outputLength out of range: " + outputLength);
        }
        try {
            // Extract — PRK = HMAC-SHA256(salt, ikm)
            Mac extractMac = Mac.getInstance("HmacSHA256");
            extractMac.init(new SecretKeySpec(salt, "HmacSHA256"));
            byte[] prk = extractMac.doFinal(ikm);

            // Expand — T(1) = HMAC-SHA256(PRK, info || 0x01)
            Mac expandMac = Mac.getInstance("HmacSHA256");
            expandMac.init(new SecretKeySpec(prk, "HmacSHA256"));
            expandMac.update(info);
            expandMac.update((byte) 0x01);
            byte[] t1 = expandMac.doFinal();

            // Single-block path — outputLength is always &le; 32 here
            if (outputLength == HMAC_SHA256_OUTPUT_LENGTH) {
                return t1;
            }
            byte[] result = new byte[outputLength];
            System.arraycopy(t1, 0, result, 0, outputLength);
            return result;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HKDF-SHA256 failed — JDK should ship HmacSHA256", e);
        }
    }
}
