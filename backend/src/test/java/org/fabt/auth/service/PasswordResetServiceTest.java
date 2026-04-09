package org.fabt.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for PasswordResetService — SHA-256 correctness, token format.
 * Package-private access to sha256Hex() — no need to make it public.
 */
class PasswordResetServiceTest {

    @Test
    @DisplayName("ER-32a: sha256Hex produces correct 64-char lowercase hex digest")
    void sha256Hex_producesCorrectDigest() {
        // Known SHA-256 test vector: SHA-256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        String hash = PasswordResetService.sha256Hex("hello");
        assertThat(hash).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    @DisplayName("ER-32b: sha256Hex output is always 64 chars")
    void sha256Hex_alwaysFixedLength() {
        assertThat(PasswordResetService.sha256Hex("")).hasSize(64);
        assertThat(PasswordResetService.sha256Hex("a")).hasSize(64);
        assertThat(PasswordResetService.sha256Hex("a very long string that is much longer than the output")).hasSize(64);
    }

    @Test
    @DisplayName("ER-32c: sha256Hex is deterministic")
    void sha256Hex_deterministic() {
        String input = "test-token-abc123";
        assertThat(PasswordResetService.sha256Hex(input))
                .isEqualTo(PasswordResetService.sha256Hex(input));
    }

    @Test
    @DisplayName("ER-32d: sha256Hex output is lowercase hex only")
    void sha256Hex_lowercaseHex() {
        String hash = PasswordResetService.sha256Hex("any-input");
        assertThat(hash).matches("[a-f0-9]{64}");
    }

    @Test
    @DisplayName("ER-32e: Different inputs produce different hashes")
    void sha256Hex_differentInputsDifferentOutputs() {
        assertThat(PasswordResetService.sha256Hex("token-1"))
                .isNotEqualTo(PasswordResetService.sha256Hex("token-2"));
    }
}
