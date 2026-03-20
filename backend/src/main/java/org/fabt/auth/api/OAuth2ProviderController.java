package org.fabt.auth.api;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.fabt.auth.service.TenantOAuth2ProviderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/oauth2-providers")
@PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
public class OAuth2ProviderController {

    private final TenantOAuth2ProviderService providerService;

    public OAuth2ProviderController(TenantOAuth2ProviderService providerService) {
        this.providerService = providerService;
    }

    @Operation(
            summary = "Register a new OAuth2 identity provider for a tenant",
            description = "Configures an OAuth2/OIDC provider (e.g., Google, Azure AD, Okta) for the " +
                    "specified tenant. Once registered and enabled, users of this tenant can log in " +
                    "via the provider's SSO flow. Required fields: providerName (a short identifier " +
                    "like 'google' or 'azure-ad' — must be unique within the tenant), clientId, " +
                    "clientSecret, and issuerUri (the OIDC discovery URL). The clientSecret is stored " +
                    "encrypted. Returns 201 with the provider record (clientSecret is not echoed). " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @PostMapping
    public ResponseEntity<OAuth2ProviderResponse> create(
            @Parameter(description = "UUID of the tenant to register the provider under") @PathVariable UUID tenantId,
            @Valid @RequestBody CreateOAuth2ProviderRequest request) {

        var provider = providerService.create(
                tenantId,
                request.providerName(),
                request.clientId(),
                request.clientSecret(),
                request.issuerUri());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(OAuth2ProviderResponse.from(provider));
    }

    @Operation(
            summary = "List all OAuth2 providers configured for a tenant",
            description = "Returns all OAuth2/OIDC provider configurations for the specified tenant, " +
                    "including both enabled and disabled providers. Each record includes providerName, " +
                    "clientId, issuerUri, enabled status, and timestamps. The clientSecret is never " +
                    "returned. Use this to audit which identity providers are configured. " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @GetMapping
    public ResponseEntity<List<OAuth2ProviderResponse>> list(
            @Parameter(description = "UUID of the tenant whose OAuth2 providers to list") @PathVariable UUID tenantId) {
        List<OAuth2ProviderResponse> providers = providerService.findByTenantId(tenantId).stream()
                .map(OAuth2ProviderResponse::from)
                .toList();
        return ResponseEntity.ok(providers);
    }

    @Operation(
            summary = "Update an OAuth2 provider's configuration",
            description = "Updates the clientId, clientSecret, issuerUri, or enabled status of an " +
                    "existing OAuth2 provider. All fields in the request body are applied — to leave " +
                    "a field unchanged, pass its current value. Setting enabled to false disables " +
                    "SSO login for this provider without deleting the configuration. Returns the " +
                    "updated provider record. Returns 404 if the provider ID does not exist. " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @PutMapping("/{providerId}")
    public ResponseEntity<OAuth2ProviderResponse> update(
            @Parameter(description = "UUID of the owning tenant") @PathVariable UUID tenantId,
            @Parameter(description = "UUID of the OAuth2 provider to update") @PathVariable UUID providerId,
            @Valid @RequestBody UpdateOAuth2ProviderRequest request) {

        var provider = providerService.update(
                providerId,
                request.clientId(),
                request.clientSecret(),
                request.issuerUri(),
                request.enabled());

        return ResponseEntity.ok(OAuth2ProviderResponse.from(provider));
    }

    @Operation(
            summary = "Delete an OAuth2 provider configuration permanently",
            description = "Permanently removes the specified OAuth2 provider from the tenant. Users " +
                    "who previously authenticated via this provider will no longer be able to use " +
                    "SSO and must use password login or another configured provider. This is a hard " +
                    "delete — to temporarily disable a provider without losing its configuration, " +
                    "use the update endpoint to set enabled=false instead. Returns 204 No Content. " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @DeleteMapping("/{providerId}")
    public ResponseEntity<Void> delete(
            @Parameter(description = "UUID of the owning tenant") @PathVariable UUID tenantId,
            @Parameter(description = "UUID of the OAuth2 provider to delete") @PathVariable UUID providerId) {

        providerService.delete(providerId);
        return ResponseEntity.noContent().build();
    }
}
