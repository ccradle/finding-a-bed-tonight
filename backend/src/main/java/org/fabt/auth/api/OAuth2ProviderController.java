package org.fabt.auth.api;

import java.util.List;
import java.util.UUID;

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

    @PostMapping
    public ResponseEntity<OAuth2ProviderResponse> create(
            @PathVariable UUID tenantId,
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

    @GetMapping
    public ResponseEntity<List<OAuth2ProviderResponse>> list(@PathVariable UUID tenantId) {
        List<OAuth2ProviderResponse> providers = providerService.findByTenantId(tenantId).stream()
                .map(OAuth2ProviderResponse::from)
                .toList();
        return ResponseEntity.ok(providers);
    }

    @PutMapping("/{providerId}")
    public ResponseEntity<OAuth2ProviderResponse> update(
            @PathVariable UUID tenantId,
            @PathVariable UUID providerId,
            @Valid @RequestBody UpdateOAuth2ProviderRequest request) {

        var provider = providerService.update(
                providerId,
                request.clientId(),
                request.clientSecret(),
                request.issuerUri(),
                request.enabled());

        return ResponseEntity.ok(OAuth2ProviderResponse.from(provider));
    }

    @DeleteMapping("/{providerId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID tenantId,
            @PathVariable UUID providerId) {

        providerService.delete(providerId);
        return ResponseEntity.noContent().build();
    }
}
