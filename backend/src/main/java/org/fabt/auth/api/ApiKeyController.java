package org.fabt.auth.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.fabt.auth.service.ApiKeyService;
import org.fabt.shared.web.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/api-keys")
@PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @PostMapping
    public ResponseEntity<ApiKeyCreateResponse> createApiKey(@Valid @RequestBody CreateApiKeyRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        ApiKeyService.ApiKeyCreateResult result = apiKeyService.create(tenantId, request.shelterId(), request.label());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiKeyCreateResponse(result.id(), result.plaintextKey(), result.suffix()));
    }

    @GetMapping
    public ResponseEntity<List<ApiKeyResponse>> listApiKeys() {
        UUID tenantId = TenantContext.getTenantId();
        List<ApiKeyResponse> keys = apiKeyService.findByTenantId(tenantId).stream()
                .map(ApiKeyResponse::from)
                .toList();
        return ResponseEntity.ok(keys);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivateApiKey(@PathVariable UUID id) {
        apiKeyService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/rotate")
    public ResponseEntity<ApiKeyCreateResponse> rotateApiKey(@PathVariable UUID id) {
        ApiKeyService.ApiKeyCreateResult result = apiKeyService.rotate(id);
        return ResponseEntity.ok(new ApiKeyCreateResponse(result.id(), result.plaintextKey(), result.suffix()));
    }
}
