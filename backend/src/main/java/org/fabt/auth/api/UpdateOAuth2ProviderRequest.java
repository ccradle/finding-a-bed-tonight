package org.fabt.auth.api;

public record UpdateOAuth2ProviderRequest(
        String clientId,
        String clientSecret,
        String issuerUri,
        Boolean enabled
) {
}
