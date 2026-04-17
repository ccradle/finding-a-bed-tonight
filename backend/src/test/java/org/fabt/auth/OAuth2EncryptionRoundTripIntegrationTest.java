package org.fabt.auth;

import java.util.UUID;

import javax.sql.DataSource;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.TenantOAuth2Provider;
import org.fabt.auth.repository.TenantOAuth2ProviderRepository;
import org.fabt.auth.service.DynamicClientRegistrationSource;
import org.fabt.auth.service.TenantOAuth2ProviderService;
import org.fabt.shared.security.KeyPurpose;
import org.fabt.shared.security.SecretEncryptionService;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test that the Phase 0 encrypt-on-save / decrypt-on-read wiring
 * correctly round-trips OAuth2 client secrets through the database.
 *
 * <p>Exercises three contracts (multi-tenant-production-readiness task 1.7):
 * <ol>
 *   <li>{@code TenantOAuth2ProviderService.create} encrypts before persist —
 *       the raw column is ciphertext, never plaintext.</li>
 *   <li>{@code DynamicClientRegistrationSource.findByRegistrationId} returns
 *       the plaintext secret in the {@link ClientRegistration}.</li>
 *   <li>The C1 plaintext-tolerant fallback continues to function: a legacy
 *       row stored as plaintext (pre-V59 / pre-Phase-0) still resolves to a
 *       working {@code ClientRegistration} until V59 re-encrypts it.</li>
 * </ol>
 */
class OAuth2EncryptionRoundTripIntegrationTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private TenantOAuth2ProviderService providerService;
    @Autowired private TenantOAuth2ProviderRepository providerRepository;
    @Autowired private DynamicClientRegistrationSource registrationSource;
    @Autowired private SecretEncryptionService encryptionService;
    @Autowired private DataSource dataSource;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        tenantId = authHelper.getTestTenantId();
    }

    @Test
    @DisplayName("create() persists ciphertext; raw column is decryptable to original plaintext")
    void createPersistsCiphertext() throws Exception {
        String plaintextSecret = "round-trip-secret-" + UUID.randomUUID();

        TenantOAuth2Provider created = TenantContext.callWithContext(tenantId, false, () ->
                providerService.create("rtprov", "rt-client-id", plaintextSecret,
                        "https://login.microsoftonline.com/common/v2.0"));

        // Reload via repository to bypass any in-memory state from create()
        TenantOAuth2Provider reloaded = providerRepository.findById(created.getId()).orElseThrow();
        String stored = reloaded.getClientSecretEncrypted();

        assertNotNull(stored, "stored secret must be non-null");
        assertNotEquals(plaintextSecret, stored,
                "stored secret must be ciphertext, not plaintext");
        // Phase A5 D38: v1 envelope — use decryptForTenant with OAUTH2_CLIENT_SECRET.
        assertEquals(plaintextSecret,
                encryptionService.decryptForTenant(tenantId, KeyPurpose.OAUTH2_CLIENT_SECRET, stored),
                "ciphertext must decrypt to the original plaintext");
    }

    @Test
    @DisplayName("findByRegistrationId returns plaintext clientSecret in ClientRegistration")
    void findByRegistrationIdReturnsPlaintext() {
        String plaintextSecret = "lookup-secret-" + UUID.randomUUID();

        TenantContext.runWithContext(tenantId, false, () ->
                providerService.create("lookupprov", "lookup-client-id", plaintextSecret,
                        "https://login.microsoftonline.com/common/v2.0"));

        String registrationId = authHelper.getTestTenantSlug() + "-lookupprov";
        ClientRegistration reg = registrationSource.findByRegistrationId(registrationId);

        assertNotNull(reg);
        assertEquals(plaintextSecret, reg.getClientSecret(),
                "ClientRegistration.clientSecret must be the decrypted plaintext");
    }

    @Test
    @DisplayName("update() re-encrypts a new client secret; old ciphertext is replaced")
    void updateRewrapsClientSecret() throws Exception {
        String firstSecret = "first-" + UUID.randomUUID();
        String secondSecret = "second-" + UUID.randomUUID();

        TenantOAuth2Provider created = TenantContext.callWithContext(tenantId, false, () ->
                providerService.create("upprov", "up-client-id", firstSecret,
                        "https://login.microsoftonline.com/common/v2.0"));
        String firstStored = providerRepository.findById(created.getId()).orElseThrow()
                .getClientSecretEncrypted();

        TenantContext.runWithContext(tenantId, false, () ->
                providerService.update(created.getId(), null, secondSecret, null, null));
        String secondStored = providerRepository.findById(created.getId()).orElseThrow()
                .getClientSecretEncrypted();

        assertNotEquals(firstStored, secondStored, "stored ciphertext must change on update");
        assertEquals(secondSecret,
                encryptionService.decryptForTenant(tenantId, KeyPurpose.OAUTH2_CLIENT_SECRET, secondStored));
    }

    @Test
    @DisplayName("Legacy plaintext row resolves via C1 plaintext-tolerant fallback")
    void legacyPlaintextRowResolvesViaFallback() throws Exception {
        // Simulate pre-V59 state: insert a plaintext value directly bypassing the service.
        // Note: works today because tenant_oauth2_provider has no RLS (V10 omitted it).
        // When Phase B task 3.4 adds RLS to this table, this test must wrap the raw
        // INSERT in a TenantContext or run on an owner-role connection.
        String legacyPlaintext = "legacy-plain-secret-" + UUID.randomUUID();
        UUID providerId;
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO tenant_oauth2_provider "
                     + "(tenant_id, provider_name, client_id, client_secret_encrypted, "
                     + " issuer_uri, enabled, created_at) "
                     + "VALUES (?, ?, ?, ?, ?, true, now()) RETURNING id")) {
            ps.setObject(1, tenantId);
            ps.setString(2, "legacy");
            ps.setString(3, "legacy-client-id");
            ps.setString(4, legacyPlaintext);
            ps.setString(5, "https://login.microsoftonline.com/common/v2.0");
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                providerId = (UUID) rs.getObject("id");
            }
        }

        try {
            String registrationId = authHelper.getTestTenantSlug() + "-legacy";
            ClientRegistration reg = registrationSource.findByRegistrationId(registrationId);

            assertNotNull(reg, "legacy plaintext provider must still resolve (C1 fallback)");
            assertEquals(legacyPlaintext, reg.getClientSecret(),
                    "fallback must return the stored plaintext when decryption fails");
        } finally {
            // Cleanup runs even if assertions fail, preventing test-data pollution
            // of the shared Testcontainers Postgres
            try (var conn = dataSource.getConnection();
                 var ps = conn.prepareStatement(
                         "DELETE FROM tenant_oauth2_provider WHERE id = ?")) {
                ps.setObject(1, providerId);
                ps.executeUpdate();
            }
        }
    }
}
