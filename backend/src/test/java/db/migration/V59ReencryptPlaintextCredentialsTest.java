package db.migration;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the AES-GCM helpers inside
 * {@link V59__reencrypt_plaintext_credentials}.
 *
 * <p>The migration class duplicates the cipher logic of
 * {@code SecretEncryptionService} (it cannot use Spring DI). These tests
 * guard against drift in the duplicated parameters (algorithm, IV length,
 * tag length) by exercising the round-trip and the
 * {@code looksLikeCiphertext} guard the migration relies on for idempotency.
 *
 * <p>Reflection is used because the helpers are private — kept private on
 * purpose so application code never imports them.
 */
@DisplayName("V59 re-encrypt migration AES-GCM helpers (W5)")
class V59ReencryptPlaintextCredentialsTest {

    private final V59__reencrypt_plaintext_credentials migration =
            new V59__reencrypt_plaintext_credentials();

    private SecretKey randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return new SecretKeySpec(bytes, "AES");
    }

    private String encrypt(String plaintext, SecretKey key) throws Exception {
        Method m = V59__reencrypt_plaintext_credentials.class.getDeclaredMethod(
                "encrypt", String.class, SecretKey.class);
        m.setAccessible(true);
        return (String) m.invoke(migration, plaintext, key);
    }

    private String decrypt(String ciphertext, SecretKey key) throws Exception {
        Method m = V59__reencrypt_plaintext_credentials.class.getDeclaredMethod(
                "decrypt", String.class, SecretKey.class);
        m.setAccessible(true);
        return (String) m.invoke(migration, ciphertext, key);
    }

    private boolean looksLikeCiphertext(String stored, SecretKey key) throws Exception {
        Method m = V59__reencrypt_plaintext_credentials.class.getDeclaredMethod(
                "looksLikeCiphertext", String.class, SecretKey.class);
        m.setAccessible(true);
        return (boolean) m.invoke(migration, stored, key);
    }

    @Test
    @DisplayName("encrypt → decrypt round-trip preserves plaintext")
    void roundTripPreservesPlaintext() throws Exception {
        SecretKey key = randomKey();
        String plaintext = "client-secret-abc-123-XYZ-!@#";
        String ciphertext = encrypt(plaintext, key);
        assertNotEquals(plaintext, ciphertext, "ciphertext must not equal plaintext");
        assertEquals(plaintext, decrypt(ciphertext, key));
    }

    @Test
    @DisplayName("encrypt produces a different IV per call (non-determinism)")
    void encryptIsNonDeterministic() throws Exception {
        SecretKey key = randomKey();
        String plaintext = "stable-input";
        assertNotEquals(encrypt(plaintext, key), encrypt(plaintext, key));
    }

    @Test
    @DisplayName("looksLikeCiphertext returns true for genuine ciphertext")
    void looksLikeCiphertextAcceptsGenuine() throws Exception {
        SecretKey key = randomKey();
        String ciphertext = encrypt("anything", key);
        assertTrue(looksLikeCiphertext(ciphertext, key));
    }

    @Test
    @DisplayName("looksLikeCiphertext returns false for plaintext (idempotency guard)")
    void looksLikeCiphertextRejectsPlaintext() throws Exception {
        SecretKey key = randomKey();
        assertFalse(looksLikeCiphertext("plain-old-string", key));
    }

    @Test
    @DisplayName("looksLikeCiphertext returns false for ciphertext encrypted under a different key")
    void looksLikeCiphertextRejectsForeignKey() throws Exception {
        SecretKey k1 = randomKey();
        SecretKey k2 = randomKey();
        String ciphertext = encrypt("payload", k1);
        assertFalse(looksLikeCiphertext(ciphertext, k2));
    }

    @Test
    @DisplayName("looksLikeCiphertext returns false for valid Base64 that is too short for GCM shape")
    void looksLikeCiphertextRejectsShortBase64() throws Exception {
        SecretKey key = randomKey();
        String tooShort = Base64.getEncoder().encodeToString("hi".getBytes(StandardCharsets.UTF_8));
        assertFalse(looksLikeCiphertext(tooShort, key));
    }
}
