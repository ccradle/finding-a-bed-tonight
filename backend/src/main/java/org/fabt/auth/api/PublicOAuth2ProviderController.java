package org.fabt.auth.api;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.fabt.auth.service.TenantOAuth2ProviderService;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoint for listing available OAuth2 login options for a tenant.
 * No authentication required — this is called by the login page before the user is signed in.
 */
@RestController
@RequestMapping("/api/v1/tenants/{slug}/oauth2-providers/public")
public class PublicOAuth2ProviderController {

    private final TenantOAuth2ProviderService providerService;
    private final TenantService tenantService;

    public PublicOAuth2ProviderController(TenantOAuth2ProviderService providerService,
                                           TenantService tenantService) {
        this.providerService = providerService;
        this.tenantService = tenantService;
    }

    @Operation(
            summary = "List enabled OAuth2 login options for a tenant's login page",
            description = "Returns the enabled OAuth2/OIDC providers available for the tenant " +
                    "identified by its URL slug. This is a public, unauthenticated endpoint " +
                    "designed to be called by the login page before the user has signed in. " +
                    "Each entry includes the provider name and the OAuth2 authorization URL to " +
                    "redirect the user to. If the slug does not match any tenant, an empty list " +
                    "is returned (not a 404) to avoid leaking whether a tenant exists. Only " +
                    "providers with enabled=true are included."
    )
    @GetMapping
    public ResponseEntity<List<PublicOAuth2ProviderResponse>> listEnabledProviders(
            @Parameter(description = "URL slug of the tenant (e.g., 'atlanta-coc'), as used in the login page URL")
            @PathVariable String slug) {

        // Return empty list for unknown tenants — don't leak tenant existence
        // via different HTTP status codes on a public endpoint
        Tenant tenant = tenantService.findBySlug(slug).orElse(null);
        if (tenant == null) {
            return ResponseEntity.ok(List.of());
        }

        List<PublicOAuth2ProviderResponse> providers = providerService
                .findEnabledByTenantId(tenant.getId()).stream()
                .map(p -> new PublicOAuth2ProviderResponse(
                        p.getProviderName(),
                        "/oauth2/authorization/" + slug + "-" + p.getProviderName()))
                .toList();

        return ResponseEntity.ok(providers);
    }
}
