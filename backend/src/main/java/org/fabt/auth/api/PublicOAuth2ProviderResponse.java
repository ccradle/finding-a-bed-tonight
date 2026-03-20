package org.fabt.auth.api;

public record PublicOAuth2ProviderResponse(
        String providerName,
        String loginUrl
) {
}
