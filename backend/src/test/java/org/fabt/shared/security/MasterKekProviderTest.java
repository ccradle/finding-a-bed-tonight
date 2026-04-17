package org.fabt.shared.security;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MasterKekProvider} — the single source of truth for
 * {@code FABT_ENCRYPTION_KEY} validation. These were originally
 * {@code SecretEncryptionServiceConstructorTest} (Phase 0 C2 hardening
 * tests) and migrated here in Checkpoint A3 D17 when the validation logic
 * extracted out of {@link SecretEncryptionService}.
 *
 * <p>Pure constructor logic — no Spring context. Uses {@link MockEnvironment}
 * to vary active profiles.
 */
@DisplayName("MasterKekProvider — FABT_ENCRYPTION_KEY validation (D17, ex-C2)")
class MasterKekProviderTest {

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
                () -> new MasterKekProvider(null, prod));
        assertTrue(ex.getMessage().contains("required in the prod profile"),
                "error must explain the prod requirement; was: " + ex.getMessage());
    }

    @Test
    @DisplayName("prod profile + blank key → throws IllegalStateException")
    void prodWithBlankKeyThrows() {
        Environment prod = profile("prod");
        assertThrows(IllegalStateException.class,
                () -> new MasterKekProvider("   ", prod));
    }

    @Test
    @DisplayName("prod profile + dev key → throws (existing protection)")
    void prodWithDevKeyThrows() {
        Environment prod = profile("prod");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new MasterKekProvider(DEV_KEY, prod));
        assertTrue(ex.getMessage().contains("must not use the dev-start.sh key"),
                "error must mention the dev-key block; was: " + ex.getMessage());
    }

    @Test
    @DisplayName("prod profile + real 32-byte key → constructed, both accessors work")
    void prodWithRealKeyWorks() {
        Environment prod = profile("prod");
        MasterKekProvider provider = new MasterKekProvider(REAL_KEY, prod);
        SecretKey platformKey = provider.getPlatformKey();
        assertNotNull(platformKey);
        assertEquals(32, platformKey.getEncoded().length);
        assertEquals("AES", platformKey.getAlgorithm());
        assertEquals(32, provider.getMasterKekBytes().length);
    }

    @Test
    @DisplayName("non-prod profile + no key → falls back to DEV_KEY")
    void nonProdWithoutKeyFallsBackToDevKey() {
        Environment dev = profile("dev");
        MasterKekProvider provider = new MasterKekProvider(null, dev);
        assertNotNull(provider.getPlatformKey());
    }

    @Test
    @DisplayName("test profile + no key → falls back to DEV_KEY (CI / Testcontainers path)")
    void testProfileWithoutKeyFallsBackToDevKey() {
        Environment test = profile("test", "lite");
        MasterKekProvider provider = new MasterKekProvider(null, test);
        assertNotNull(provider.getPlatformKey());
    }

    @Test
    @DisplayName("non-prod profile + dev key explicit → accepted")
    void nonProdWithDevKeyExplicitWorks() {
        Environment dev = profile("dev");
        MasterKekProvider provider = new MasterKekProvider(DEV_KEY, dev);
        assertNotNull(provider.getPlatformKey());
    }

    @Test
    @DisplayName("any profile + key with wrong byte length → IllegalArgumentException")
    void wrongLengthKeyThrows() {
        // 16 bytes, not 32
        String shortKey = java.util.Base64.getEncoder().encodeToString(new byte[16]);
        Environment dev = profile("dev");
        assertThrows(IllegalArgumentException.class,
                () -> new MasterKekProvider(shortKey, dev));
    }

    @Test
    @DisplayName("no profiles active + no key → falls back to DEV_KEY (default-profile case)")
    void noProfileWithoutKeyFallsBackToDevKey() {
        Environment empty = profile();
        MasterKekProvider provider = new MasterKekProvider(null, empty);
        assertNotNull(provider.getPlatformKey());
    }

    @Test
    @DisplayName("getMasterKekBytes returns a defensive clone — caller mutation does not affect provider state")
    void getMasterKekBytesReturnsDefensiveClone() {
        MasterKekProvider provider = new MasterKekProvider(REAL_KEY, profile("dev"));
        byte[] firstCall = provider.getMasterKekBytes();
        byte[] secondCall = provider.getMasterKekBytes();
        assertNotSame(firstCall, secondCall, "each call must return a fresh array");
        // Mutate the first copy
        java.util.Arrays.fill(firstCall, (byte) 0);
        // Provider's internal state must be intact — verified via second-call equality with a fresh derivation
        byte[] thirdCall = provider.getMasterKekBytes();
        assertEquals(java.util.Arrays.toString(secondCall), java.util.Arrays.toString(thirdCall),
                "mutating the returned array must not affect the canonical copy");
    }
}
