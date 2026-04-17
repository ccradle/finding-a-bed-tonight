package org.fabt.shared.security;

import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link EncryptionEnvelope} — wire format invariants, magic
 * detection, round-trip parsing.
 *
 * <p>Per A3 D18 + warroom E3 boundary case: covers the FABT-prefixed-v0
 * adversarial scenario where a legacy ciphertext happens to start with
 * the v1 magic bytes.
 */
@DisplayName("EncryptionEnvelope wire format (A3 D18)")
class EncryptionEnvelopeTest {

    private static final UUID KID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final byte[] IV_12 = new byte[12];
    private static final byte[] CT_PLUS_TAG = new byte[16 + 5]; // 16-byte tag + 5-byte ciphertext

    static {
        for (int i = 0; i < IV_12.length; i++) IV_12[i] = (byte) (0xA0 | i);
        for (int i = 0; i < CT_PLUS_TAG.length; i++) CT_PLUS_TAG[i] = (byte) (0xC0 | (i & 0x0F));
    }

    @Test
    @DisplayName("Round-trip: encode then decode yields the original envelope")
    void roundTrip() {
        EncryptionEnvelope original = new EncryptionEnvelope(KID, IV_12, CT_PLUS_TAG);
        EncryptionEnvelope parsed = EncryptionEnvelope.decode(original.encode());
        assertEquals(KID, parsed.kid());
        assertArrayEquals(IV_12, parsed.iv());
        assertArrayEquals(CT_PLUS_TAG, parsed.ciphertextWithTag());
    }

    @Test
    @DisplayName("encode produces bytes starting with FABT magic + version")
    void encodeStartsWithMagic() {
        EncryptionEnvelope env = new EncryptionEnvelope(KID, IV_12, CT_PLUS_TAG);
        byte[] decoded = Base64.getDecoder().decode(env.encode());
        assertEquals((byte) 0x46, decoded[0]); // 'F'
        assertEquals((byte) 0x41, decoded[1]); // 'A'
        assertEquals((byte) 0x42, decoded[2]); // 'B'
        assertEquals((byte) 0x54, decoded[3]); // 'T'
        assertEquals(EncryptionEnvelope.VERSION_V1, decoded[4]);
    }

    @Test
    @DisplayName("isV1Envelope detects valid v1 magic + version")
    void isV1Envelope_acceptsValid() {
        EncryptionEnvelope env = new EncryptionEnvelope(KID, IV_12, CT_PLUS_TAG);
        byte[] decoded = Base64.getDecoder().decode(env.encode());
        assertTrue(EncryptionEnvelope.isV1Envelope(decoded));
    }

    @Test
    @DisplayName("isV1Envelope rejects v0-style bytes (random short sequence with no magic)")
    void isV1Envelope_rejectsV0() {
        // v0 envelope: just iv + ciphertext + tag (28+ bytes), no magic
        byte[] v0 = new byte[28];
        for (int i = 0; i < v0.length; i++) v0[i] = (byte) i;
        assertFalse(EncryptionEnvelope.isV1Envelope(v0));
    }

    @Test
    @DisplayName("isV1Envelope rejects bytes shorter than the header")
    void isV1Envelope_rejectsShort() {
        assertFalse(EncryptionEnvelope.isV1Envelope(new byte[10]));
        assertFalse(EncryptionEnvelope.isV1Envelope(new byte[0]));
        assertFalse(EncryptionEnvelope.isV1Envelope(null));
    }

    @Test
    @DisplayName("isV1Envelope rejects FABT-prefixed but wrong version (E3a boundary)")
    void isV1Envelope_rejectsWrongVersion() {
        // Adversarial: a v0 ciphertext that happens to start with FABT followed
        // by a non-v1 byte. Must NOT be misclassified as v1.
        byte[] adversarial = new byte[33];
        adversarial[0] = 0x46; // F
        adversarial[1] = 0x41; // A
        adversarial[2] = 0x42; // B
        adversarial[3] = 0x54; // T
        adversarial[4] = (byte) 0xFF; // not VERSION_V1
        assertFalse(EncryptionEnvelope.isV1Envelope(adversarial));
    }

    @Test
    @DisplayName("isV1Envelope accepts FABT-prefixed v0 ciphertext as v1 (E3a known limitation)")
    void isV1Envelope_acceptsFabtPrefixedV0() {
        // The 1-in-1T collision case: a v0 ciphertext that happens to start
        // with exactly FABT + 0x01. The detector returns true (this is the
        // documented residual risk per A3 D21). The downstream decrypt MUST
        // then fail — not silently corrupt — because the kid lookup will
        // either return no row OR the GCM tag won't verify.
        byte[] adversarial = new byte[EncryptionEnvelope.HEADER_LENGTH + 16];
        adversarial[0] = 0x46;
        adversarial[1] = 0x41;
        adversarial[2] = 0x42;
        adversarial[3] = 0x54;
        adversarial[4] = EncryptionEnvelope.VERSION_V1;
        // Remaining bytes are zeros, which when interpreted as kid become
        // 00000000-0000-0000-0000-000000000000 — guaranteed not in
        // kid_to_tenant_key, so the registry lookup will fail clean.
        assertTrue(EncryptionEnvelope.isV1Envelope(adversarial),
                "the magic-byte detector accepts this; downstream registry/decrypt rejects it");
    }

    @Test
    @DisplayName("decode rejects too-short envelopes")
    void decode_rejectsShort() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[20]);
        assertThrows(IllegalArgumentException.class,
                () -> EncryptionEnvelope.decode(tooShort));
    }

    @Test
    @DisplayName("decode rejects missing magic")
    void decode_rejectsMissingMagic() {
        byte[] noMagic = new byte[64];
        // first 4 bytes are zeros, not FABT
        String stored = Base64.getEncoder().encodeToString(noMagic);
        assertThrows(IllegalArgumentException.class,
                () -> EncryptionEnvelope.decode(stored));
    }

    @Test
    @DisplayName("Constructor rejects null kid")
    void constructor_rejectsNullKid() {
        assertThrows(IllegalArgumentException.class,
                () -> new EncryptionEnvelope(null, IV_12, CT_PLUS_TAG));
    }

    @Test
    @DisplayName("Constructor rejects wrong-length IV (must be exactly 12 bytes)")
    void constructor_rejectsWrongIvLength() {
        assertThrows(IllegalArgumentException.class,
                () -> new EncryptionEnvelope(KID, new byte[13], CT_PLUS_TAG));
        assertThrows(IllegalArgumentException.class,
                () -> new EncryptionEnvelope(KID, new byte[0], CT_PLUS_TAG));
    }

    @Test
    @DisplayName("Constructor rejects ciphertext shorter than 16-byte GCM tag")
    void constructor_rejectsShortCiphertext() {
        assertThrows(IllegalArgumentException.class,
                () -> new EncryptionEnvelope(KID, IV_12, new byte[15]));
    }

    @Test
    @DisplayName("HEADER_LENGTH constant matches actual encoded header size")
    void headerLengthConstantConsistent() {
        assertEquals(33, EncryptionEnvelope.HEADER_LENGTH,
                "magic(4) + version(1) + kid(16) + iv(12) = 33");
    }
}
