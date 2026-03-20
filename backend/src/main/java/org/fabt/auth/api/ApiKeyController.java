package org.fabt.auth.api;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

    @Operation(
            summary = "Create a new API key for shelter data submission",
            description = "Generates a new API key scoped to the caller's tenant, optionally bound to " +
                    "a specific shelter via shelterId. The plaintext key is returned exactly once in " +
                    "the response — it is stored as a BCrypt hash and cannot be retrieved again. " +
                    "Callers must persist the plaintext key immediately. The response includes the " +
                    "key's UUID (for management operations), the plaintext key, and a suffix (last " +
                    "4 characters) for identification in logs and UI. The optional label field is " +
                    "a human-readable name for the key. Returns 201. " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @PostMapping
    public ResponseEntity<ApiKeyCreateResponse> createApiKey(@Valid @RequestBody CreateApiKeyRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        ApiKeyService.ApiKeyCreateResult result = apiKeyService.create(tenantId, request.shelterId(), request.label());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiKeyCreateResponse(result.id(), result.plaintextKey(), result.suffix()));
    }

    @Operation(
            summary = "List all API keys for the authenticated tenant",
            description = "Returns metadata for all API keys belonging to the caller's tenant, " +
                    "including id, suffix, label, shelterId binding, active status, and timestamps. " +
                    "The plaintext key is never returned — only the suffix is available for " +
                    "identification. Deactivated keys are included in the response (check the " +
                    "active field). Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @GetMapping
    public ResponseEntity<List<ApiKeyResponse>> listApiKeys() {
        UUID tenantId = TenantContext.getTenantId();
        List<ApiKeyResponse> keys = apiKeyService.findByTenantId(tenantId).stream()
                .map(ApiKeyResponse::from)
                .toList();
        return ResponseEntity.ok(keys);
    }

    @Operation(
            summary = "Deactivate an API key (soft delete)",
            description = "Marks the specified API key as inactive. The key will immediately stop " +
                    "authenticating requests, but the record is retained for audit purposes. This " +
                    "operation is idempotent — deactivating an already-inactive key returns 204 " +
                    "without error. Returns 204 No Content on success. " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivateApiKey(
            @Parameter(description = "UUID of the API key to deactivate") @PathVariable UUID id) {
        apiKeyService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Rotate an API key, generating a new secret",
            description = "Generates a new plaintext key for the specified API key record, " +
                    "invalidating the previous secret. The new plaintext key is returned exactly " +
                    "once — store it immediately. The key UUID, label, and shelter binding remain " +
                    "unchanged. Use this for periodic credential rotation or after a suspected " +
                    "key compromise. Returns 200 with the new key details. " +
                    "Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @PostMapping("/{id}/rotate")
    public ResponseEntity<ApiKeyCreateResponse> rotateApiKey(
            @Parameter(description = "UUID of the API key to rotate") @PathVariable UUID id) {
        ApiKeyService.ApiKeyCreateResult result = apiKeyService.rotate(id);
        return ResponseEntity.ok(new ApiKeyCreateResponse(result.id(), result.plaintextKey(), result.suffix()));
    }
}
