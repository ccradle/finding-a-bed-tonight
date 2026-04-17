package org.fabt.shared.security;

import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link KeyDerivationService} covering the two design
 * properties HKDF must hold for per-tenant DEK / JWT-signing-key safety:
 *
 * <ol>
 *   <li><b>Reproducibility</b> (task 2.19) — same inputs always yield the
 *       same output. This is the property that lets us stop persisting
 *       per-tenant DEKs and re-derive on every encrypt/decrypt call.</li>
 *   <li><b>Separation</b> (task 2.20) — different inputs always yield
 *       different outputs. This is the property that prevents Tenant A's
 *       TOTP DEK from accidentally matching Tenant B's TOTP DEK
 *       (cross-tenant cryptographic leak).</li>
 * </ol>
 */
@DisplayName("KeyDerivationService HKDF properties (Phase A tasks 2.19 + 2.20)")
class KeyDerivationServiceTest {

    private static final String KEY_A = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String KEY_B = "/wD/AP8A/wD/AP8A/wD/AP8A/wD/AP8A/wD/AP8A/wA=";
    private static final UUID TENANT_X = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_Y = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private Environment profile(String... active) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(active);
        return env;
    }

    private KeyDerivationService service(String key) {
        // A3 D17: KeyDerivationService now consumes MasterKekProvider; the
        // env-var validation it used to do is owned by the provider. Tests
        // wire a fresh provider per call with a non-prod profile.
        return new KeyDerivationService(new MasterKekProvider(key, profile("dev")));
    }

    // ------------------------------------------------------------------
    // Task 2.19 — Reproducibility
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Same (KEK, tenant, purpose) always produces the same derived key")
    void reproducibility_sameInputs() {
        KeyDerivationService svc = service(KEY_A);
        SecretKey k1 = svc.deriveKey(TENANT_X, "totp");
        SecretKey k2 = svc.deriveKey(TENANT_X, "totp");
        assertArrayEquals(k1.getEncoded(), k2.getEncoded(),
                "deterministic — same inputs must yield byte-identical key");
    }

    @Test
    @DisplayName("Two service instances built from the same KEK derive identical keys (no instance state leak)")
    void reproducibility_acrossInstances() {
        KeyDerivationService svcA = service(KEY_A);
        KeyDerivationService svcB = service(KEY_A);
        SecretKey k1 = svcA.deriveKey(TENANT_X, "jwt-sign");
        SecretKey k2 = svcB.deriveKey(TENANT_X, "jwt-sign");
        assertArrayEquals(k1.getEncoded(), k2.getEncoded(),
                "instance state must not affect derivation — restart-safety guarantee");
    }

    @Test
    @DisplayName("Derived key length is exactly 32 bytes")
    void reproducibility_outputLength() {
        SecretKey k = service(KEY_A).deriveKey(TENANT_X, "totp");
        assertEquals(32, k.getEncoded().length);
        assertEquals("AES", k.getAlgorithm());
    }

    // ------------------------------------------------------------------
    // Task 2.20 — Separation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Different tenants → different keys (same purpose)")
    void separation_byTenant() {
        KeyDerivationService svc = service(KEY_A);
        SecretKey kx = svc.deriveKey(TENANT_X, "totp");
        SecretKey ky = svc.deriveKey(TENANT_Y, "totp");
        assertFalse(java.util.Arrays.equals(kx.getEncoded(), ky.getEncoded()),
                "Tenant X and Tenant Y must derive different TOTP keys — cross-tenant cryptographic isolation");
    }

    @Test
    @DisplayName("Different purposes → different keys (same tenant)")
    void separation_byPurpose() {
        KeyDerivationService svc = service(KEY_A);
        SecretKey totp = svc.deriveKey(TENANT_X, "totp");
        SecretKey jwt = svc.deriveKey(TENANT_X, "jwt-sign");
        SecretKey webhook = svc.deriveKey(TENANT_X, "webhook-secret");
        SecretKey oauth = svc.deriveKey(TENANT_X, "oauth2-client-secret");
        SecretKey hmis = svc.deriveKey(TENANT_X, "hmis-api-key");

        // All five purposes pairwise distinct
        SecretKey[] keys = { totp, jwt, webhook, oauth, hmis };
        for (int i = 0; i < keys.length; i++) {
            for (int j = i + 1; j < keys.length; j++) {
                assertFalse(java.util.Arrays.equals(keys[i].getEncoded(), keys[j].getEncoded()),
                        "Purposes " + i + " and " + j + " must derive distinct keys (purpose-domain separation)");
            }
        }
    }

    @Test
    @DisplayName("Different master KEKs → different keys (same tenant + purpose)")
    void separation_byMasterKek() {
        SecretKey ka = service(KEY_A).deriveKey(TENANT_X, "totp");
        SecretKey kb = service(KEY_B).deriveKey(TENANT_X, "totp");
        assertFalse(java.util.Arrays.equals(ka.getEncoded(), kb.getEncoded()),
                "Different master KEKs must yield different derived keys (key rotation guarantee)");
    }

    // ------------------------------------------------------------------
    // Boundary + validation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Null tenantId rejected")
    void rejectsNullTenant() {
        KeyDerivationService svc = service(KEY_A);
        assertThrows(IllegalArgumentException.class, () -> svc.deriveKey(null, "totp"));
    }

    @Test
    @DisplayName("Null purpose rejected")
    void rejectsNullPurpose() {
        KeyDerivationService svc = service(KEY_A);
        assertThrows(IllegalArgumentException.class, () -> svc.deriveKey(TENANT_X, null));
    }

    @Test
    @DisplayName("Blank purpose rejected")
    void rejectsBlankPurpose() {
        KeyDerivationService svc = service(KEY_A);
        assertThrows(IllegalArgumentException.class, () -> svc.deriveKey(TENANT_X, "   "));
    }

    // ------------------------------------------------------------------
    // Note: prod-fail-fast / DEV_KEY-fallback / wrong-length / dev-key-prod
    // tests previously lived here. After A3 D17 those moved to
    // MasterKekProviderTest — KeyDerivationService no longer owns the
    // validation logic; it consumes a pre-validated MasterKekProvider.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Cross-property: derived keys are usable inputs for encryption
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Two purposes for same tenant don't collide — comparison via equals on SecretKey")
    void twoPurposesNotEqual() {
        KeyDerivationService svc = service(KEY_A);
        SecretKey a = svc.deriveKey(TENANT_X, "purpose-a");
        SecretKey b = svc.deriveKey(TENANT_X, "purpose-b");
        assertNotEquals(java.util.Arrays.toString(a.getEncoded()),
                java.util.Arrays.toString(b.getEncoded()));
    }
}
