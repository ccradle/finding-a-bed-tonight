package org.fabt.tenant.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.fabt.observability.ObservabilityConfigService;
import org.fabt.observability.ObservabilityConfigService.ObservabilityConfig;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class TenantController {

    private final TenantService tenantService;
    private final ObservabilityConfigService observabilityConfigService;

    public TenantController(TenantService tenantService,
                            ObservabilityConfigService observabilityConfigService) {
        this.tenantService = tenantService;
        this.observabilityConfigService = observabilityConfigService;
    }

    @Operation(
            summary = "Create a new tenant (CoC organization)",
            description = "Provisions a new tenant representing a Continuum of Care organization. " +
                    "The tenant is the top-level isolation boundary — all shelters, users, and API keys " +
                    "are scoped to a tenant. The slug must be globally unique and is used in URLs and " +
                    "OAuth2 redirect URIs (e.g., 'atlanta-coc'). Returns 201 with the created tenant " +
                    "including its generated UUID. Returns 400 if the slug is already taken or if " +
                    "required fields (name, slug) are missing. Requires PLATFORM_ADMIN role."
    )
    @PostMapping
    public ResponseEntity<TenantResponse> create(@Valid @RequestBody CreateTenantRequest request) {
        Tenant tenant = tenantService.create(request.name(), request.slug());
        return ResponseEntity.status(HttpStatus.CREATED).body(TenantResponse.from(tenant));
    }

    @Operation(
            summary = "List all tenants on the platform",
            description = "Returns every tenant registered on the platform. Each tenant represents " +
                    "a Continuum of Care organization. The response includes tenant id, name, slug, " +
                    "and timestamps. This is an unfiltered, unpaginated list — suitable for platform " +
                    "admin dashboards. Requires PLATFORM_ADMIN role."
    )
    @GetMapping
    public ResponseEntity<List<TenantResponse>> listAll() {
        List<TenantResponse> tenants = StreamSupport.stream(tenantService.findAll().spliterator(), false)
                .map(TenantResponse::from)
                .toList();
        return ResponseEntity.ok(tenants);
    }

    @Operation(
            summary = "Get a single tenant by ID",
            description = "Returns the tenant with the specified UUID. Use this to retrieve tenant " +
                    "details including name, slug, and timestamps. Returns 404 (via NoSuchElementException) " +
                    "if no tenant exists with the given ID. Requires PLATFORM_ADMIN role."
    )
    @GetMapping("/{id}")
    public ResponseEntity<TenantResponse> getById(
            @Parameter(description = "UUID of the tenant to retrieve") @PathVariable UUID id) {
        return tenantService.findById(id)
                .map(TenantResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new java.util.NoSuchElementException("Tenant not found: " + id));
    }

    @Operation(
            summary = "Update a tenant's display name",
            description = "Updates the name of an existing tenant. The slug cannot be changed after " +
                    "creation because it is embedded in OAuth2 redirect URIs and API key scoping. " +
                    "Returns the full updated tenant object. Returns 404 if the tenant ID does not " +
                    "exist. Requires PLATFORM_ADMIN role."
    )
    @PutMapping("/{id}")
    public ResponseEntity<TenantResponse> update(
            @Parameter(description = "UUID of the tenant to update") @PathVariable UUID id,
            @Valid @RequestBody UpdateTenantRequest request) {
        Tenant tenant = tenantService.update(id, request.name());
        return ResponseEntity.ok(TenantResponse.from(tenant));
    }

    @Operation(
            summary = "Get observability settings for a tenant",
            description = "Returns the current observability configuration for the specified tenant, " +
                    "including Prometheus/tracing toggles and monitor intervals. Settings are read from " +
                    "tenant config JSONB and cached with a 60-second refresh interval."
    )
    @GetMapping("/{id}/observability")
    public ResponseEntity<ObservabilityConfig> getObservabilityConfig(
            @Parameter(description = "UUID of the tenant") @PathVariable UUID id) {
        tenantService.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("Tenant not found: " + id));
        return ResponseEntity.ok(observabilityConfigService.getConfig(id));
    }

    @Operation(
            summary = "Update observability settings for a tenant",
            description = "Updates the observability section of the tenant's config JSONB. Accepts a map " +
                    "of observability settings (prometheus_enabled, tracing_enabled, tracing_endpoint, " +
                    "monitor intervals). Changes take effect within 60 seconds without restart."
    )
    @PutMapping("/{id}/observability")
    public ResponseEntity<ObservabilityConfig> updateObservabilityConfig(
            @Parameter(description = "UUID of the tenant") @PathVariable UUID id,
            @RequestBody Map<String, Object> observabilitySettings) {
        Map<String, Object> currentConfig = tenantService.getConfig(id);
        currentConfig.put("observability", observabilitySettings);
        tenantService.updateConfig(id, currentConfig);
        observabilityConfigService.refreshCache();
        return ResponseEntity.ok(observabilityConfigService.getConfig(id));
    }
}
