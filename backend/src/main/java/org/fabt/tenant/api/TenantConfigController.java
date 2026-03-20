package org.fabt.tenant.api;

import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.fabt.tenant.service.TenantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/config")
@PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'COC_ADMIN')")
public class TenantConfigController {

    private final TenantService tenantService;

    public TenantConfigController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @Operation(
            summary = "Get tenant configuration key-value pairs",
            description = "Returns the full configuration map for the specified tenant. Configuration " +
                    "is stored as an arbitrary JSON object (Map<String, Object>) and may include keys " +
                    "such as feature flags, display preferences, or integration settings. Returns an " +
                    "empty map if no configuration has been set. Returns 404 if the tenant does not " +
                    "exist. Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig(
            @Parameter(description = "UUID of the tenant whose configuration to retrieve") @PathVariable UUID tenantId) {
        Map<String, Object> config = tenantService.getConfig(tenantId);
        return ResponseEntity.ok(config);
    }

    @Operation(
            summary = "Replace tenant configuration entirely",
            description = "Overwrites the entire configuration map for the specified tenant with the " +
                    "provided JSON object. This is a full replacement, not a merge — any keys omitted " +
                    "from the request body will be removed. Returns the saved configuration after the " +
                    "update. To add a single key without losing others, first GET the current config, " +
                    "merge your changes client-side, then PUT the full map. Returns 404 if the tenant " +
                    "does not exist. Requires COC_ADMIN or PLATFORM_ADMIN role."
    )
    @PutMapping
    public ResponseEntity<Map<String, Object>> updateConfig(
            @Parameter(description = "UUID of the tenant whose configuration to replace") @PathVariable UUID tenantId,
            @RequestBody Map<String, Object> config) {
        tenantService.updateConfig(tenantId, config);
        return ResponseEntity.ok(tenantService.getConfig(tenantId));
    }
}
