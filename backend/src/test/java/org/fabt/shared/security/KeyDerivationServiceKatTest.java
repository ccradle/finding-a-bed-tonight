package org.fabt.shared.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Known-answer tests (KAT) for {@link KeyDerivationService#hkdfSha256} against
 * the published <a href="https://www.rfc-editor.org/rfc/rfc5869#appendix-A">
 * RFC 5869 Appendix A</a> test vectors.
 *
 * <p>Per Marcus warroom W1: code-by-inspection on a hand-rolled crypto
 * implementation is not sufficient evidence of correctness. KATs are the
 * canonical mechanism — pass the spec's published inputs, expect the spec's
 * published outputs byte-for-byte. Any future refactor of the HKDF
 * implementation will fail this test if it deviates from RFC 5869.
 *
 * <p>Three RFC test cases covered (the SHA-256 variants from Appendix A.1,
 * A.2, A.3). Test case 1 is the basic case; case 2 stresses long inputs
 * (multi-block expand); case 3 exercises the empty-info branch.
 */
@DisplayName("KeyDerivationService HKDF-SHA256 KAT vs RFC 5869 Appendix A")
class KeyDerivationServiceKatTest {

    /** Hex-decode a string like "0a0b0c" into a byte array. */
    private static byte[] hex(String h) {
        h = h.replace(" ", "").replace("\n", "");
        if (h.length() % 2 != 0) {
            throw new IllegalArgumentException("hex string length must be even: " + h);
        }
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(h.charAt(i * 2), 16);
            int lo = Character.digit(h.charAt(i * 2 + 1), 16);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    @Test
    @DisplayName("RFC 5869 A.1 — basic SHA-256 (L=42, includes salt + info)")
    void rfc5869_a1_basicSha256() {
        // Inputs per RFC 5869 §A.1
        byte[] ikm  = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = hex("000102030405060708090a0b0c");
        byte[] info = hex("f0f1f2f3f4f5f6f7f8f9");
        int    L    = 42;

        // Expected OKM per RFC 5869 §A.1
        byte[] expected = hex(
                "3cb25f25faacd57a90434f64d0362f2a"
              + "2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
              + "34007208d5b887185865");

        byte[] actual = KeyDerivationService.hkdfSha256(ikm, salt, info, L);
        assertArrayEquals(expected, actual,
                "HKDF-SHA256 must match RFC 5869 Appendix A.1 OKM byte-for-byte");
    }

    @Test
    @DisplayName("RFC 5869 A.2 — long inputs (L=82, multi-block expand)")
    void rfc5869_a2_longInputsSha256() {
        // Inputs per RFC 5869 §A.2 — 80-byte IKM, 80-byte salt, 80-byte info
        byte[] ikm = hex(
                "000102030405060708090a0b0c0d0e0f"
              + "101112131415161718191a1b1c1d1e1f"
              + "202122232425262728292a2b2c2d2e2f"
              + "303132333435363738393a3b3c3d3e3f"
              + "404142434445464748494a4b4c4d4e4f");
        byte[] salt = hex(
                "606162636465666768696a6b6c6d6e6f"
              + "707172737475767778797a7b7c7d7e7f"
              + "808182838485868788898a8b8c8d8e8f"
              + "909192939495969798999a9b9c9d9e9f"
              + "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf");
        byte[] info = hex(
                "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf"
              + "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf"
              + "d0d1d2d3d4d5d6d7d8d9dadbdcdddedf"
              + "e0e1e2e3e4e5e6e7e8e9eaebecedeeef"
              + "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff");
        int    L = 82;

        byte[] expected = hex(
                "b11e398dc80327a1c8e7f78c596a4934"
              + "4f012eda2d4efad8a050cc4c19afa97c"
              + "59045a99cac7827271cb41c65e590e09"
              + "da3275600c2f09b8367793a9aca3db71"
              + "cc30c58179ec3e87c14c01d5c1f3434f"
              + "1d87");

        byte[] actual = KeyDerivationService.hkdfSha256(ikm, salt, info, L);
        assertArrayEquals(expected, actual,
                "HKDF-SHA256 multi-block expand must match RFC 5869 Appendix A.2 OKM");
    }

    @Test
    @DisplayName("RFC 5869 A.3 — zero-length salt + info (L=42)")
    void rfc5869_a3_zeroLengthSaltInfoSha256() {
        // Inputs per RFC 5869 §A.3
        byte[] ikm  = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = new byte[0];
        byte[] info = new byte[0];
        int    L    = 42;

        byte[] expected = hex(
                "8da4e775a563c18f715f802a063c5a31"
              + "b8a11f5c5ee1879ec3454e5f3c738d2d"
              + "9d201395faa4b61a96c8");

        byte[] actual = KeyDerivationService.hkdfSha256(ikm, salt, info, L);
        assertArrayEquals(expected, actual,
                "HKDF-SHA256 with empty salt + info must match RFC 5869 Appendix A.3 OKM");
    }
}
