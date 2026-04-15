package org.fabt.auth.service;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.fabt.auth.domain.TenantOAuth2Provider;
import org.fabt.auth.repository.TenantOAuth2ProviderRepository;
import org.fabt.shared.web.TenantContext;
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
     *
     * <p>Tenant-scoped: the provider MUST belong to the caller's tenant
     * (resolved from {@link TenantContext}). A cross-tenant id returns
     * 404 via {@link NoSuchElementException} — not 403 — to avoid
     * existence disclosure (design D3). See {@link #findByIdOrThrow(UUID)}
     * and the {@code cross-tenant-isolation-audit} change.
     */
    @Transactional
    public TenantOAuth2Provider update(UUID id, String clientId, String clientSecret,
                                        String issuerUri, Boolean enabled) {
        TenantOAuth2Provider provider = findByIdOrThrow(id);

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

    /**
     * Deletes an OAuth2 provider.
     *
     * <p>Tenant-scoped: the provider MUST belong to the caller's tenant
     * (resolved from {@link TenantContext}). A cross-tenant id returns 404
     * via {@link NoSuchElementException}. See {@link #findByIdOrThrow(UUID)}.
     *
     * <p>Consolidates the former {@code existsById(id)} + {@code deleteById(id)}
     * pair into a single tenant-scoped lookup followed by a scoped delete —
     * the prior {@code existsById} had the same class of defect as the
     * unscoped {@code findById} (design D2 scenario for {@code existsById}).
     */
    @Transactional
    public void delete(UUID id) {
        TenantOAuth2Provider provider = findByIdOrThrow(id);
        providerRepository.deleteById(provider.getId());
    }

    /**
     * Tenant-scoped single-provider lookup used by every state-mutating path
     * in this service ({@link #update} and {@link #delete}). Pulls the
     * caller's {@code tenantId} from {@link TenantContext} and delegates to
     * {@link TenantOAuth2ProviderRepository#findByIdAndTenantId(UUID, UUID)}.
     * Throws {@link NoSuchElementException} on empty — mapped to 404 by
     * {@code GlobalExceptionHandler}. All state-mutating call sites go
     * through this helper so the tenant-scoping invariant cannot be forgotten
     * at one site while the others are hardened.
     */
    private TenantOAuth2Provider findByIdOrThrow(UUID id) {
        return providerRepository.findByIdAndTenantId(id, TenantContext.getTenantId())
                .orElseThrow(() -> new NoSuchElementException("OAuth2 provider not found: " + id));
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
