package org.fabt.auth.api;

import jakarta.validation.constraints.NotBlank;

public record CreateOAuth2ProviderRequest(
        @NotBlank String providerName,
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        String issuerUri
) {
}
