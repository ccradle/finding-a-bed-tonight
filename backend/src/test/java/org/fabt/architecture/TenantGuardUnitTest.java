package org.fabt.architecture;

import java.util.Optional;
import java.util.UUID;

import org.fabt.auth.domain.ApiKey;
import org.fabt.auth.domain.TenantOAuth2Provider;
import org.fabt.auth.repository.ApiKeyRepository;
import org.fabt.auth.repository.TenantOAuth2ProviderRepository;
import org.fabt.auth.service.ApiKeyService;
import org.fabt.auth.service.TenantOAuth2ProviderService;
import org.fabt.shared.security.SafeOutboundUrlValidator;
import org.fabt.shared.security.SecretEncryptionService;
import org.fabt.shared.web.TenantContext;
import org.fabt.subscription.domain.Subscription;
import org.fabt.subscription.repository.SubscriptionRepository;
import org.fabt.subscription.repository.WebhookDeliveryLogRepository;
import org.fabt.subscription.service.SubscriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests verifying that Phase 2 service refactors actually call
 * the tenant-scoped repository method with BOTH the entity ID and the
 * tenant ID. Catches "someone accidentally drops the tenantId arg"
 * regressions that ArchUnit (compile-time call-graph) cannot detect.
 *
 * <p>Per Phase 3 task 3.8 of cross-tenant-isolation-audit.
 * Pure Mockito — no Spring context. Each test:
 * <ol>
 *   <li>Mocks the repository</li>
 *   <li>Wraps the service call in {@code TenantContext.runWithContext}</li>
 *   <li>Verifies the repo was called with {@code (id, tenantId)}</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Tenant-guard unit verification (Phase 3 task 3.8)")
class TenantGuardUnitTest {

    private static final UUID ENTITY_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();

    // ------------------------------------------------------------------
    // TenantOAuth2ProviderService
    // ------------------------------------------------------------------

    @Mock TenantOAuth2ProviderRepository providerRepo;
    @Mock SafeOutboundUrlValidator urlValidator;

    @Test
    @DisplayName("OAuth2 provider delete calls findByIdAndTenantId")
    void providerDelete_callsFindByIdAndTenantId() {
        var provider = new TenantOAuth2Provider();
        provider.setId(ENTITY_ID);
        provider.setTenantId(TENANT_ID);
        when(providerRepo.findByIdAndTenantId(ENTITY_ID, TENANT_ID))
                .thenReturn(Optional.of(provider));

        var service = new TenantOAuth2ProviderService(providerRepo, urlValidator, encryptionService);
        TenantContext.runWithContext(TENANT_ID, false, () -> service.delete(ENTITY_ID));

        verify(providerRepo).findByIdAndTenantId(eq(ENTITY_ID), eq(TENANT_ID));
    }

    @Test
    @DisplayName("OAuth2 provider update calls findByIdAndTenantId")
    void providerUpdate_callsFindByIdAndTenantId() {
        var provider = new TenantOAuth2Provider();
        provider.setId(ENTITY_ID);
        provider.setTenantId(TENANT_ID);
        when(providerRepo.findByIdAndTenantId(ENTITY_ID, TENANT_ID))
                .thenReturn(Optional.of(provider));
        when(providerRepo.save(any())).thenReturn(provider);

        var service = new TenantOAuth2ProviderService(providerRepo, urlValidator, encryptionService);
        TenantContext.runWithContext(TENANT_ID, false, () ->
                service.update(ENTITY_ID, null, null, null, null));

        verify(providerRepo).findByIdAndTenantId(eq(ENTITY_ID), eq(TENANT_ID));
    }

    @Test
    @DisplayName("OAuth2 provider create with non-null secret invokes encryption (W6 happy path)")
    void providerCreate_encryptsNonNullSecret() {
        org.fabt.shared.security.SafeOutboundUrlValidator urlValidator =
                org.mockito.Mockito.mock(org.fabt.shared.security.SafeOutboundUrlValidator.class);
        when(providerRepo.findByTenantIdAndProviderName(TENANT_ID, "okta"))
                .thenReturn(Optional.empty());
        when(providerRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // Phase A5 D38: encrypt flow now goes through encryptForTenant with
        // KeyPurpose.OAUTH2_CLIENT_SECRET. Stub the new typed API and verify
        // the same method is invoked.
        when(encryptionService.encryptForTenant(
                TENANT_ID,
                org.fabt.shared.security.KeyPurpose.OAUTH2_CLIENT_SECRET,
                "plain-secret")).thenReturn("CIPHERTEXT");

        var service = new TenantOAuth2ProviderService(providerRepo, urlValidator, encryptionService);
        TenantContext.runWithContext(TENANT_ID, false, () ->
                service.create("okta", "client-x", "plain-secret",
                        "https://login.microsoftonline.com/common/v2.0"));

        verify(encryptionService).encryptForTenant(
                TENANT_ID,
                org.fabt.shared.security.KeyPurpose.OAUTH2_CLIENT_SECRET,
                "plain-secret");
    }

    @Test
    @DisplayName("OAuth2 provider create with null secret stores null without calling encrypt (W6 guard)")
    void providerCreate_nullSecretSkipsEncrypt() {
        org.fabt.shared.security.SafeOutboundUrlValidator urlValidator =
                org.mockito.Mockito.mock(org.fabt.shared.security.SafeOutboundUrlValidator.class);
        when(providerRepo.findByTenantIdAndProviderName(TENANT_ID, "google"))
                .thenReturn(Optional.empty());
        when(providerRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var service = new TenantOAuth2ProviderService(providerRepo, urlValidator, encryptionService);
        TenantContext.runWithContext(TENANT_ID, false, () ->
                service.create("google", "client-y", null,
                        "https://accounts.google.com"));

        org.mockito.Mockito.verifyNoInteractions(encryptionService);
    }

    // ------------------------------------------------------------------
    // ApiKeyService
    // ------------------------------------------------------------------

    @Mock ApiKeyRepository apiKeyRepo;
    @Mock SecretEncryptionService encryptionService;

    @Test
    @DisplayName("API key rotate calls findByIdAndTenantId")
    void apiKeyRotate_callsFindByIdAndTenantId() {
        var key = new ApiKey();
        key.setId(ENTITY_ID);
        key.setTenantId(TENANT_ID);
        key.setKeyHash("old-hash");
        key.setActive(true);
        when(apiKeyRepo.findByIdAndTenantId(ENTITY_ID, TENANT_ID))
                .thenReturn(Optional.of(key));
        when(apiKeyRepo.save(any())).thenReturn(key);

        var service = new ApiKeyService(apiKeyRepo);
        TenantContext.runWithContext(TENANT_ID, false, () -> service.rotate(ENTITY_ID));

        verify(apiKeyRepo).findByIdAndTenantId(eq(ENTITY_ID), eq(TENANT_ID));
    }

    @Test
    @DisplayName("API key deactivate calls findByIdAndTenantId")
    void apiKeyDeactivate_callsFindByIdAndTenantId() {
        var key = new ApiKey();
        key.setId(ENTITY_ID);
        key.setTenantId(TENANT_ID);
        when(apiKeyRepo.findByIdAndTenantId(ENTITY_ID, TENANT_ID))
                .thenReturn(Optional.of(key));
        when(apiKeyRepo.save(any())).thenReturn(key);

        var service = new ApiKeyService(apiKeyRepo);
        TenantContext.runWithContext(TENANT_ID, false, () -> service.deactivate(ENTITY_ID));

        verify(apiKeyRepo).findByIdAndTenantId(eq(ENTITY_ID), eq(TENANT_ID));
    }

    // ------------------------------------------------------------------
    // SubscriptionService
    // ------------------------------------------------------------------

    @Mock SubscriptionRepository subRepo;
    @Mock WebhookDeliveryLogRepository deliveryLogRepo;
    @Mock SafeOutboundUrlValidator subUrlValidator;
    @Mock ObjectMapper objectMapper;

    @Test
    @DisplayName("Subscription delete calls findByIdAndTenantId")
    void subscriptionDelete_callsFindByIdAndTenantId() {
        var sub = new Subscription();
        sub.setId(ENTITY_ID);
        sub.setTenantId(TENANT_ID);
        sub.setStatus("ACTIVE");
        when(subRepo.findByIdAndTenantId(ENTITY_ID, TENANT_ID))
                .thenReturn(Optional.of(sub));
        when(subRepo.save(any())).thenReturn(sub);

        var service = new SubscriptionService(subRepo, deliveryLogRepo,
                encryptionService, subUrlValidator, objectMapper);
        TenantContext.runWithContext(TENANT_ID, false, () -> service.delete(ENTITY_ID));

        verify(subRepo).findByIdAndTenantId(eq(ENTITY_ID), eq(TENANT_ID));
    }
}
