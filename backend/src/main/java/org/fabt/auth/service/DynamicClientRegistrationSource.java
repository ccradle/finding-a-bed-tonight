package org.fabt.auth.service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.UUID;

import org.fabt.auth.domain.TenantOAuth2Provider;
import org.fabt.shared.security.KeyPurpose;
import org.fabt.shared.security.SecretEncryptionService;
import org.fabt.tenant.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.stereotype.Component;

/**
 * Loads OAuth2 client registrations dynamically from the tenant_oauth2_provider table.
 * Registration ID format: {slug}-{providerName} (e.g., dev-coc-google).
 * Uses OIDC discovery to resolve endpoints from issuer_uri.
 * Cached for 5 minutes.
 */
@Component
public class DynamicClientRegistrationSource implements ClientRegistrationRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamicClientRegistrationSource.class);

    private final TenantOAuth2ProviderService providerService;
    private final TenantService tenantService;
    private final SecretEncryptionService encryptionService;

    @org.fabt.shared.security.TenantUnscopedCache("pre-authentication OAuth2 provider lookup keyed by {slug}-{provider}; tenant slug is structurally part of the key so cross-tenant collision is impossible; cache is read by Spring Security filter chain before any JWT is validated")
    private final Cache<String, ClientRegistration> cache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(100)
            .build();

    public DynamicClientRegistrationSource(TenantOAuth2ProviderService providerService,
                                                TenantService tenantService,
                                                SecretEncryptionService encryptionService) {
        this.providerService = providerService;
        this.tenantService = tenantService;
        this.encryptionService = encryptionService;
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        if (registrationId == null) return null;

        ClientRegistration cached = cache.getIfPresent(registrationId);
        if (cached != null) return cached;

        // Parse {slug}-{providerName} — slug may contain hyphens, provider is the last segment
        int lastDash = registrationId.lastIndexOf('-');
        if (lastDash <= 0) return null;

        String slug = registrationId.substring(0, lastDash);
        String providerName = registrationId.substring(lastDash + 1);

        // Resolve tenant
        var tenant = tenantService.findBySlug(slug);
        if (tenant.isEmpty()) return null;

        // Resolve provider
        Optional<TenantOAuth2Provider> provider = providerService
                .findByTenantIdAndProviderName(tenant.get().getId(), providerName);
        if (provider.isEmpty() || !provider.get().isEnabled()) return null;

        TenantOAuth2Provider p = provider.get();

        ClientRegistration registration = ClientRegistration.withRegistrationId(registrationId)
                .clientId(p.getClientId())
                .clientSecret(decryptClientSecret(p.getTenantId(), p.getClientSecretEncrypted()))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/oauth2/callback/" + registrationId)
                .scope("openid", "profile", "email")
                .authorizationUri(resolveEndpoint(p.getIssuerUri(), "/protocol/openid-connect/auth"))
                .tokenUri(resolveEndpoint(p.getIssuerUri(), "/protocol/openid-connect/token"))
                .jwkSetUri(resolveEndpoint(p.getIssuerUri(), "/protocol/openid-connect/certs"))
                .userInfoUri(resolveEndpoint(p.getIssuerUri(), "/protocol/openid-connect/userinfo"))
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .clientName(p.getProviderName())
                .build();

        cache.put(registrationId, registration);
        return registration;
    }

    /**
     * Decrypts a stored OAuth2 client secret under the owning tenant's DEK.
     * Phase A5 D38: per-tenant-scoped. The v0 fallback path inside
     * {@link SecretEncryptionService#decryptForTenant} handles legacy
     * pre-V74 ciphertexts without a caller-side try/catch being required.
     *
     * <p>Plaintext-tolerance fallback preserved for direct-DB-edit cases:
     * if an admin/operator set {@code client_secret_encrypted} to raw
     * plaintext post-V74 (not encrypted, not v0, not v1), decrypt throws
     * and we pass the value through. Consistent with
     * {@code HmisConfigService.decryptApiKey}'s tolerance.
     */
    private String decryptClientSecret(UUID tenantId, String stored) {
        if (stored == null || stored.isBlank()) {
            return stored;
        }
        try {
            return encryptionService.decryptForTenant(tenantId, KeyPurpose.OAUTH2_CLIENT_SECRET, stored);
        } catch (RuntimeException e) {
            log.debug("client_secret_encrypted is not valid ciphertext; returning plaintext fallback");
            return stored;
        }
    }

    /**
     * Resolves OIDC endpoints from issuer URI.
     * For well-known providers (Google, Microsoft), uses standard OIDC discovery URLs.
     * For Keycloak, appends the realm-relative path.
     */
    private String resolveEndpoint(String issuerUri, String suffix) {
        if (issuerUri == null) return null;
        // Google: https://accounts.google.com → https://accounts.google.com/o/oauth2/v2/auth
        if (issuerUri.contains("accounts.google.com")) {
            return switch (suffix) {
                case "/protocol/openid-connect/auth" -> "https://accounts.google.com/o/oauth2/v2/auth";
                case "/protocol/openid-connect/token" -> "https://oauth2.googleapis.com/token";
                case "/protocol/openid-connect/certs" -> "https://www.googleapis.com/oauth2/v3/certs";
                case "/protocol/openid-connect/userinfo" -> "https://openidconnect.googleapis.com/v1/userinfo";
                default -> issuerUri + suffix;
            };
        }
        // Microsoft: https://login.microsoftonline.com/{tenant}/v2.0
        if (issuerUri.contains("login.microsoftonline.com")) {
            String base = issuerUri.endsWith("/v2.0") ? issuerUri.substring(0, issuerUri.length() - 5) : issuerUri;
            return switch (suffix) {
                case "/protocol/openid-connect/auth" -> base + "/oauth2/v2.0/authorize";
                case "/protocol/openid-connect/token" -> base + "/oauth2/v2.0/token";
                case "/protocol/openid-connect/certs" -> base + "/discovery/v2.0/keys";
                case "/protocol/openid-connect/userinfo" -> "https://graph.microsoft.com/oidc/userinfo";
                default -> issuerUri + suffix;
            };
        }
        // Keycloak and other OIDC-compliant providers
        return issuerUri + suffix;
    }
}
