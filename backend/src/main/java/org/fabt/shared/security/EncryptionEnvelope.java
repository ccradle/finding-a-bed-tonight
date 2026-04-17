package org.fabt.shared.security;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.UUID;

/**
 * v1 per-tenant ciphertext envelope per A3 D18 + D21. Wire format
 * (post-Base64-decode):
 *
 * <pre>
 * [magic: 4 = "FABT"][version: 1 = 0x01][kid: 16][iv: 12][ciphertext+tag: N+16]
 * </pre>
 *
 * Total fixed overhead vs the raw plaintext: 33 bytes header + 16 bytes
 * GCM auth tag = 49 bytes (Phase 0 v0 envelope was 28 bytes overhead;
 * v1 adds 21 bytes net per encrypted secret).
 *
 * <p>The magic + version pair is the format discriminator: any
 * Base64-decoded value whose first 4 bytes are {@code 0x46 0x41 0x42 0x54}
 * ("FABT" ASCII) AND whose 5th byte equals {@link #VERSION_V1} is a v1
 * envelope. Anything else is treated as legacy v0 (Phase 0
 * single-platform-key format) and routed to {@link CiphertextV0Decoder}.
 *
 * <p>Magic-byte collision probability for a random v0 ciphertext starting
 * with these 5 bytes is ~1 in 1T (4 bytes magic × 1 byte version =
 * 2^40 ≈ 1.1 × 10^12). At 1M ciphertexts in a deployment, expected
 * false-positive misclassifications per scan = ~1 × 10^-6. Negligible.
 *
 * <p>Purpose intentionally absent from the envelope per D19 — purpose is
 * implicit in the typed {@code deriveXxxKey} method the caller picks; a
 * purpose-mismatched decrypt fails on the GCM auth tag rather than
 * returning corrupt plaintext.
 *
 * <p>Immutable value class. Use {@link #encode} to serialize, {@link #decode}
 * to parse. Both throw {@link IllegalArgumentException} on shape violations
 * — never return null, never return a partially-populated envelope.
 */
public record EncryptionEnvelope(UUID kid, byte[] iv, byte[] ciphertextWithTag) {

    /** ASCII "FABT" — the format discriminator. */
    public static final byte[] MAGIC = { 0x46, 0x41, 0x42, 0x54 };

    /** Current envelope version. Bump on wire-format changes. */
    public static final byte VERSION_V1 = 0x01;

    /** Bytes consumed by magic + version + kid + iv (the fixed-size header). */
    public static final int HEADER_LENGTH = 4 + 1 + 16 + 12;

    /** AES-GCM authentication tag length, appended by the JCE Cipher. */
    public static final int GCM_TAG_LENGTH_BYTES = 16;

    public EncryptionEnvelope {
        if (kid == null) throw new IllegalArgumentException("kid must be non-null");
        if (iv == null || iv.length != 12) {
            throw new IllegalArgumentException("iv must be exactly 12 bytes (GCM standard)");
        }
        if (ciphertextWithTag == null || ciphertextWithTag.length < GCM_TAG_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "ciphertextWithTag must include the 16-byte GCM auth tag");
        }
    }

    /**
     * Returns true if the given bytes start with the v1 magic + version.
     * Intended as the format discriminator for the read path:
     * v1 routes here, anything else routes to {@link CiphertextV0Decoder}.
     */
    public static boolean isV1Envelope(byte[] decoded) {
        if (decoded == null || decoded.length < HEADER_LENGTH) return false;
        for (int i = 0; i < MAGIC.length; i++) {
            if (decoded[i] != MAGIC[i]) return false;
        }
        return decoded[MAGIC.length] == VERSION_V1;
    }

    /** Serializes the envelope to a Base64 ASCII string for DB storage. */
    public String encode() {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_LENGTH + ciphertextWithTag.length);
        buf.put(MAGIC);
        buf.put(VERSION_V1);
        buf.putLong(kid.getMostSignificantBits());
        buf.putLong(kid.getLeastSignificantBits());
        buf.put(iv);
        buf.put(ciphertextWithTag);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    /**
     * Parses a Base64 stored value into an envelope. Throws on:
     * <ul>
     *   <li>Base64 decode failure (delegated to JDK)</li>
     *   <li>Length too short for header + tag</li>
     *   <li>Magic mismatch</li>
     *   <li>Unsupported version byte</li>
     * </ul>
     * Caller checks {@link #isV1Envelope(byte[])} on the Base64-decoded
     * bytes first to choose between this method and the v0 fallback path;
     * this method assumes v1 was already detected.
     */
    public static EncryptionEnvelope decode(String stored) {
        byte[] decoded = Base64.getDecoder().decode(stored);
        if (decoded.length < HEADER_LENGTH + GCM_TAG_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "envelope too short: " + decoded.length + " bytes (need at least "
                    + (HEADER_LENGTH + GCM_TAG_LENGTH_BYTES) + ")");
        }
        if (!isV1Envelope(decoded)) {
            throw new IllegalArgumentException(
                    "missing FABT magic or unsupported version byte — "
                    + "this is not a v1 envelope");
        }
        ByteBuffer buf = ByteBuffer.wrap(decoded);
        buf.position(MAGIC.length + 1); // skip magic + version
        long kidHigh = buf.getLong();
        long kidLow = buf.getLong();
        UUID kid = new UUID(kidHigh, kidLow);
        byte[] iv = new byte[12];
        buf.get(iv);
        byte[] ciphertextWithTag = new byte[buf.remaining()];
        buf.get(ciphertextWithTag);
        return new EncryptionEnvelope(kid, iv, ciphertextWithTag);
    }
}
