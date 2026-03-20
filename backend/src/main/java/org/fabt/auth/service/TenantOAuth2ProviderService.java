package org.fabt.auth.service;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.fabt.auth.domain.TenantOAuth2Provider;
import org.fabt.auth.repository.TenantOAuth2ProviderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantOAuth2ProviderService {

    private final TenantOAuth2ProviderRepository providerRepository;

    public TenantOAuth2ProviderService(TenantOAuth2ProviderRepository providerRepository) {
        this.providerRepository = providerRepository;
    }

    /**
     * Registers a new OAuth2 provider for a tenant.
     *
     * @param tenantId     the tenant this provider belongs to
     * @param providerName e.g. "google", "microsoft", "okta"
     * @param clientId     the OAuth2 client ID
     * @param clientSecret the OAuth2 client secret
     * @param issuerUri    the OIDC issuer URI (e.g. "https://accounts.google.com")
     * @return the created provider entity
     */
    @Transactional
    public TenantOAuth2Provider create(UUID tenantId, String providerName, String clientId,
                                        String clientSecret, String issuerUri) {
        // Check for duplicate provider name within tenant
        if (providerRepository.findByTenantIdAndProviderName(tenantId, providerName).isPresent()) {
            throw new IllegalStateException(
                    "Provider '" + providerName + "' already configured for this tenant");
        }

        TenantOAuth2Provider provider = new TenantOAuth2Provider();
        // ID left null — database generates via gen_random_uuid() (Lesson 64)
        provider.setTenantId(tenantId);
        provider.setProviderName(providerName);
        provider.setClientId(clientId);
        // TODO: Encrypt client secret with Vault/KMS before storing in production.
        // For MVP, storing as-is. This is a known technical debt item.
        provider.setClientSecretEncrypted(clientSecret);
        provider.setIssuerUri(issuerUri);
        provider.setEnabled(true);
        provider.setCreatedAt(Instant.now());

        return providerRepository.save(provider);
    }

    /**
     * Updates an existing OAuth2 provider configuration.
     * Only non-null fields are updated.
     */
    @Transactional
    public TenantOAuth2Provider update(UUID id, String clientId, String clientSecret,
                                        String issuerUri, Boolean enabled) {
        TenantOAuth2Provider provider = providerRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("OAuth2 provider not found: " + id));

        if (clientId != null) {
            provider.setClientId(clientId);
        }
        if (clientSecret != null) {
            // TODO: Encrypt client secret with Vault/KMS before storing in production.
            provider.setClientSecretEncrypted(clientSecret);
        }
        if (issuerUri != null) {
            provider.setIssuerUri(issuerUri);
        }
        if (enabled != null) {
            provider.setEnabled(enabled);
        }

        return providerRepository.save(provider);
    }

    @Transactional
    public void delete(UUID id) {
        if (!providerRepository.existsById(id)) {
            throw new NoSuchElementException("OAuth2 provider not found: " + id);
        }
        providerRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public List<TenantOAuth2Provider> findByTenantId(UUID tenantId) {
        return providerRepository.findByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    public List<TenantOAuth2Provider> findEnabledByTenantId(UUID tenantId) {
        return providerRepository.findByTenantIdAndEnabledTrue(tenantId);
    }

    @Transactional(readOnly = true)
    public Optional<TenantOAuth2Provider> findByTenantIdAndProviderName(UUID tenantId,
                                                                         String providerName) {
        return providerRepository.findByTenantIdAndProviderName(tenantId, providerName);
    }
}
