package org.fabt.auth.api;

import java.time.Instant;
import java.util.UUID;

import org.fabt.auth.domain.TenantOAuth2Provider;

public record OAuth2ProviderResponse(
        UUID id,
        String providerName,
        boolean enabled,
        String issuerUri,
        Instant createdAt
) {

    public static OAuth2ProviderResponse from(TenantOAuth2Provider provider) {
        return new OAuth2ProviderResponse(
                provider.getId(),
                provider.getProviderName(),
                provider.isEnabled(),
                provider.getIssuerUri(),
                provider.getCreatedAt()
        );
    }
}
