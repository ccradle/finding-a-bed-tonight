package org.fabt.shared.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the C2 hardening of {@link SecretEncryptionService}'s
 * constructor: prod profile rejects missing/dev keys; non-prod profile
 * silently falls back to the committed dev key; {@code configured=true}
 * is the new invariant.
 *
 * <p>Pure constructor logic — no Spring context. Uses {@link MockEnvironment}
 * to vary active profiles.
 */
@DisplayName("SecretEncryptionService constructor (C2)")
class SecretEncryptionServiceConstructorTest {

    private static final String DEV_KEY = "s4FgjCrVQONb65lQmfYHyuvC7AL2VnkVufwB9ZihvlA=";
    private static final String REAL_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private Environment profile(String... active) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(active);
        return env;
    }

    @Test
    @DisplayName("prod profile + no key → throws IllegalStateException")
    void prodWithoutKeyThrows() {
        Environment prod = profile("prod");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new SecretEncryptionService(null, prod));
        assertTrue(ex.getMessage().contains("required in the prod profile"),
                "error must explain the prod requirement; was: " + ex.getMessage());
    }

    @Test
    @DisplayName("prod profile + blank key → throws IllegalStateException")
    void prodWithBlankKeyThrows() {
        Environment prod = profile("prod");
        assertThrows(IllegalStateException.class,
                () -> new SecretEncryptionService("   ", prod));
    }

    @Test
    @DisplayName("prod profile + dev key → throws (existing protection)")
    void prodWithDevKeyThrows() {
        Environment prod = profile("prod");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new SecretEncryptionService(DEV_KEY, prod));
        assertTrue(ex.getMessage().contains("must not use the dev-start.sh key"),
                "error must mention the dev-key block; was: " + ex.getMessage());
    }

    @Test
    @DisplayName("prod profile + real 32-byte key → constructed, configured=true, round-trip works")
    void prodWithRealKeyWorks() {
        Environment prod = profile("prod");
        SecretEncryptionService svc = new SecretEncryptionService(REAL_KEY, prod);
        assertTrue(svc.isConfigured());
        assertEquals("hello", svc.decrypt(svc.encrypt("hello")));
    }

    @Test
    @DisplayName("non-prod profile + no key → falls back to DEV_KEY, configured=true")
    void nonProdWithoutKeyFallsBackToDevKey() {
        Environment dev = profile("dev");
        SecretEncryptionService svc = new SecretEncryptionService(null, dev);
        assertTrue(svc.isConfigured());
        // Round-trip works using the implicit dev fallback
        assertEquals("hello", svc.decrypt(svc.encrypt("hello")));
    }

    @Test
    @DisplayName("test profile + no key → falls back to DEV_KEY (CI / Testcontainers path)")
    void testProfileWithoutKeyFallsBackToDevKey() {
        Environment test = profile("test", "lite");
        SecretEncryptionService svc = new SecretEncryptionService(null, test);
        assertTrue(svc.isConfigured());
    }

    @Test
    @DisplayName("non-prod profile + dev key explicit → accepted")
    void nonProdWithDevKeyExplicitWorks() {
        Environment dev = profile("dev");
        SecretEncryptionService svc = new SecretEncryptionService(DEV_KEY, dev);
        assertTrue(svc.isConfigured());
    }

    @Test
    @DisplayName("any profile + key with wrong byte length → IllegalArgumentException")
    void wrongLengthKeyThrows() {
        // 16 bytes, not 32
        String shortKey = java.util.Base64.getEncoder().encodeToString(new byte[16]);
        Environment dev = profile("dev");
        assertThrows(IllegalArgumentException.class,
                () -> new SecretEncryptionService(shortKey, dev));
    }

    @Test
    @DisplayName("no profiles active + no key → falls back to DEV_KEY (default-profile case)")
    void noProfileWithoutKeyFallsBackToDevKey() {
        Environment empty = profile();
        SecretEncryptionService svc = new SecretEncryptionService(null, empty);
        assertTrue(svc.isConfigured());
    }
}
