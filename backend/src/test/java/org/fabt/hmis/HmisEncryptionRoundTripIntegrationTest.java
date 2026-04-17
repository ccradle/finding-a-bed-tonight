package org.fabt.hmis;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.hmis.domain.HmisVendorConfig;
import org.fabt.hmis.domain.HmisVendorType;
import org.fabt.hmis.service.HmisConfigService;
import org.fabt.shared.security.SecretEncryptionService;
import org.fabt.tenant.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test for HMIS API key encryption round-trip through tenant
 * config JSONB (multi-tenant-production-readiness task 1.8).
 *
 * <p>Exercises three contracts:
 * <ol>
 *   <li>{@code HmisConfigService.encryptApiKey} produces ciphertext that
 *       decrypts back to the original plaintext via
 *       {@link SecretEncryptionService}.</li>
 *   <li>{@code HmisConfigService.getVendors} decrypts ciphertext stored under
 *       {@code hmis_vendors[].api_key_encrypted} so adapters
 *       ({@code ClientTrackAdapter}, {@code ClarityAdapter}) receive
 *       plaintext.</li>
 *   <li>The plaintext-tolerant fallback continues to function: a legacy row
 *       stored as plaintext (pre-V59 / pre-Phase-0) still resolves to the
 *       plaintext value until V59 re-encrypts it.</li>
 * </ol>
 *
 * <p>Note: there is no typed write endpoint for HMIS vendors today
 * ({@code HmisExportController.addVendor} is stubbed 501). Writes flow
 * through the generic {@code TenantConfigController.updateConfig}, which is
 * what this test exercises directly via {@link TenantService#updateConfig}.
 * The platform-hardening change will add typed endpoints that call
 * {@code encryptApiKey} as part of the write path; until then, raw plaintext
 * writes are accepted on the generic config PUT and remain plaintext until
 * V59 picks them up on next deploy.
 */
class HmisEncryptionRoundTripIntegrationTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private HmisConfigService hmisConfigService;
    @Autowired private TenantService tenantService;
    @Autowired private SecretEncryptionService encryptionService;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        tenantId = authHelper.getTestTenantId();
    }

    @Test
    @DisplayName("encryptApiKey produces ciphertext that round-trips through SecretEncryptionService")
    void encryptApiKeyRoundTripsThroughSharedService() {
        String plaintext = "hmis-api-key-" + UUID.randomUUID();
        String ciphertext = hmisConfigService.encryptApiKey(plaintext);

        assertNotEquals(plaintext, ciphertext);
        assertEquals(plaintext, encryptionService.decrypt(ciphertext));
    }

    @Test
    @DisplayName("encryptApiKey returns null/blank inputs unchanged (safe-by-default)")
    void encryptApiKeyHandlesNullAndBlank() {
        assertEquals(null, hmisConfigService.encryptApiKey(null));
        assertEquals("", hmisConfigService.encryptApiKey(""));
        assertEquals("   ", hmisConfigService.encryptApiKey("   "));
    }

    @Test
    @DisplayName("getVendors decrypts hmis_vendors[].api_key_encrypted ciphertext back to plaintext")
    void getVendorsDecryptsCiphertextOnRead() {
        String plaintext = "round-trip-key-" + UUID.randomUUID();
        String ciphertext = hmisConfigService.encryptApiKey(plaintext);

        tenantService.updateConfig(tenantId, Map.of(
                "hmis_vendors", List.of(Map.of(
                        "id", UUID.randomUUID().toString(),
                        "type", HmisVendorType.CLARITY.name(),
                        "base_url", "https://example-clarity.test",
                        "api_key_encrypted", ciphertext,
                        "enabled", true,
                        "push_interval_hours", 6
                ))
        ));

        List<HmisVendorConfig> vendors = hmisConfigService.getVendors(tenantId);

        assertEquals(1, vendors.size(), "exactly one vendor expected");
        HmisVendorConfig vendor = vendors.get(0);
        assertNotNull(vendor.apiKeyEncrypted(), "decrypted key must be non-null");
        assertEquals(plaintext, vendor.apiKeyEncrypted(),
                "adapter receives plaintext; decrypt-on-read must reverse encrypt-on-save");
    }

    @Test
    @DisplayName("getVendors falls back to plaintext for legacy unencrypted api_key_encrypted")
    void getVendorsFallsBackToPlaintextForLegacy() {
        // Simulate a tenant whose hmis_vendors config was written before Phase 0 —
        // api_key_encrypted contains literal plaintext, not ciphertext. This is the
        // exact state V59 re-encrypts on next deploy. Until that runs, the read path
        // must still surface the working plaintext value to the adapter.
        String legacyPlaintext = "legacy-plaintext-" + UUID.randomUUID();

        tenantService.updateConfig(tenantId, Map.of(
                "hmis_vendors", List.of(Map.of(
                        "id", UUID.randomUUID().toString(),
                        "type", HmisVendorType.CLIENTTRACK.name(),
                        "base_url", "https://example-clienttrack.test",
                        "api_key_encrypted", legacyPlaintext,
                        "enabled", true,
                        "push_interval_hours", 6
                ))
        ));

        List<HmisVendorConfig> vendors = hmisConfigService.getVendors(tenantId);

        assertEquals(1, vendors.size());
        assertEquals(legacyPlaintext, vendors.get(0).apiKeyEncrypted(),
                "fallback must return the stored plaintext when decryption fails");
    }

    @Test
    @DisplayName("getEnabledVendors honours decrypted state and the enabled flag")
    void getEnabledVendorsFiltersDisabled() {
        String enabledPlain = "enabled-key-" + UUID.randomUUID();
        String disabledPlain = "disabled-key-" + UUID.randomUUID();

        tenantService.updateConfig(tenantId, Map.of(
                "hmis_vendors", List.of(
                        Map.of(
                                "id", UUID.randomUUID().toString(),
                                "type", HmisVendorType.CLARITY.name(),
                                "base_url", "https://on.test",
                                "api_key_encrypted", hmisConfigService.encryptApiKey(enabledPlain),
                                "enabled", true,
                                "push_interval_hours", 6
                        ),
                        Map.of(
                                "id", UUID.randomUUID().toString(),
                                "type", HmisVendorType.CLIENTTRACK.name(),
                                "base_url", "https://off.test",
                                "api_key_encrypted", hmisConfigService.encryptApiKey(disabledPlain),
                                "enabled", false,
                                "push_interval_hours", 6
                        )
                )
        ));

        List<HmisVendorConfig> enabled = hmisConfigService.getEnabledVendors(tenantId);

        assertEquals(1, enabled.size(), "only the enabled vendor must be returned");
        assertTrue(enabled.get(0).enabled());
        assertEquals(enabledPlain, enabled.get(0).apiKeyEncrypted(),
                "filtered vendor must still carry the decrypted key");
    }
}
