package org.fabt.tenant.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.fabt.observability.ObservabilityConfigService;
import org.fabt.observability.ObservabilityConfigService.ObservabilityConfig;
import org.fabt.shared.web.TenantContext;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantLifecycleService;
import org.fabt.tenant.service.TenantService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private static final Logger log = LoggerFactory.getLogger(TenantController.class);

    private final TenantService tenantService;
    private final ObservabilityConfigService observabilityConfigService;
    /**
     * Optional — the F-4 atomic-create path. Present when
     * {@code fabt.tenant.lifecycle.enabled=true}, absent otherwise. When present,
     * {@link #create} delegates here for the full bootstrap (audit_chain_head seed,
     * eager JWT key material, TENANT_CREATED audit). When absent, falls back to the
     * legacy {@link TenantService#create} path which relies on lazy-at-first-login
     * key material bootstrap.
     */
    private final ObjectProvider<TenantLifecycleService> tenantLifecycleServiceProvider;

    public TenantController(TenantService tenantService,
                            ObservabilityConfigService observabilityConfigService,
                            ObjectProvider<TenantLifecycleService> tenantLifecycleServiceProvider) {
        this.tenantService = tenantService;
        this.observabilityConfigService = observabilityConfigService;
        this.tenantLifecycleServiceProvider = tenantLifecycleServiceProvider;
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
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @org.fabt.auth.platform.PlatformAdminOnly(
            reason = "Tenant creation — provisions a new isolation boundary; platform authority required",
            emits = org.fabt.shared.audit.AuditEventType.PLATFORM_TENANT_CREATED)
    public ResponseEntity<TenantResponse> create(@Valid @RequestBody CreateTenantRequest request) {
        // F-4: prefer the lifecycle service's atomic bootstrap when the feature
        // flag is on (TenantLifecycleService bean exists). Falls back to the
        // legacy TenantService.create path when the bean is absent — that path
        // relies on lazy-at-first-login JWT key material bootstrap and does NOT
        // seed the audit_chain_head row, but remains correct for backwards
        // compatibility while the flag rolls out.
        TenantLifecycleService lifecycle = tenantLifecycleServiceProvider.getIfAvailable();
        Tenant tenant;
        if (lifecycle != null) {
            tenant = lifecycle.create(request.name(), request.slug(), TenantContext.getUserId());
        } else {
            tenant = tenantService.create(request.name(), request.slug());
        }
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
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
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
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
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
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @org.fabt.auth.platform.PlatformAdminOnly(
            reason = "Tenant attribute update (display name) — platform authority required for any tenant-level metadata change",
            emits = org.fabt.shared.audit.AuditEventType.PLATFORM_TENANT_CREATED)
    public ResponseEntity<TenantResponse> update(
            @Parameter(description = "UUID of the tenant to update") @PathVariable UUID id,
            @Valid @RequestBody UpdateTenantRequest request) {
        // G-4.4: TenantPathGuard removed — endpoint is @PlatformAdminOnly, the
        // platform-operator JWT carries NO tenantId (Decision 3 + 13), so the
        // guard would 404 on every legitimate platform-scoped call. The
        // @PlatformAdminOnly + MFA_VERIFIED + audit chain provide the
        // cross-tenant authorization story; the guard's tenant-A-can't-touch-B
        // invariant is now enforced by role separation (COC_ADMIN can't reach
        // this method at all; only PLATFORM_OPERATOR can, and PLATFORM_OPERATOR
        // is intentionally cross-tenant). Service layer still uses the
        // path-supplied id for the lookup, so the audit chain captures the
        // exact target tenant.
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
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    public ResponseEntity<ObservabilityConfig> getObservabilityConfig(
            @Parameter(description = "UUID of the tenant") @PathVariable UUID id) {
        // G-4.4: TenantPathGuard removed — see TenantController.update for rationale.
        return ResponseEntity.ok(observabilityConfigService.getConfig(id));
    }

    @Operation(
            summary = "Update observability settings for a tenant",
            description = "Updates the observability section of the tenant's config JSONB. Accepts a map " +
                    "of observability settings (prometheus_enabled, tracing_enabled, tracing_endpoint, " +
                    "monitor intervals). Changes take effect within 60 seconds without restart."
    )
    @PutMapping("/{id}/observability")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @org.fabt.auth.platform.PlatformAdminOnly(
            reason = "Tenant observability config update — affects what monitoring + tracing flows out of the tenant; platform authority required",
            emits = org.fabt.shared.audit.AuditEventType.PLATFORM_TENANT_CREATED)
    public ResponseEntity<ObservabilityConfig> updateObservabilityConfig(
            @Parameter(description = "UUID of the tenant") @PathVariable UUID id,
            @RequestBody Map<String, Object> observabilitySettings) {
        // G-4.4: TenantPathGuard removed — see TenantController.update for rationale.
        Map<String, Object> currentConfig = tenantService.getConfig(id);
        currentConfig.put("observability", observabilitySettings);
        tenantService.updateConfig(id, currentConfig);
        observabilityConfigService.refreshCache();
        return ResponseEntity.ok(observabilityConfigService.getConfig(id));
    }

    @Operation(
            summary = "Change DV address visibility policy",
            description = "Updates the DV shelter address visibility policy for a tenant. " +
                    "Valid policies: ADMIN_AND_ASSIGNED (default), ADMIN_ONLY, ALL_DV_ACCESS, NONE. " +
                    "Requires PLATFORM_ADMIN role and X-Confirm-Policy-Change: CONFIRM header. " +
                    "INTERNAL/ADMIN-ONLY — this endpoint should not be exposed outside the corporate firewall."
    )
    @PutMapping("/{id}/dv-address-policy")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @org.fabt.auth.platform.PlatformAdminOnly(
            reason = "DV-address visibility policy change — affects who can see DV shelter physical addresses; high compliance impact (VAWA-comparable posture); platform authority required",
            emits = org.fabt.shared.audit.AuditEventType.PLATFORM_TENANT_CREATED)
    public ResponseEntity<?> updateDvAddressPolicy(
            @Parameter(description = "UUID of the tenant") @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Confirm-Policy-Change", required = false) String confirmHeader,
            Authentication authentication) {

        // G-4.4: TenantPathGuard removed — see TenantController.update for rationale.

        if (!"CONFIRM".equals(confirmHeader)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing or invalid X-Confirm-Policy-Change header. Send 'CONFIRM' to proceed."));
        }

        String policyValue = body.get("policy");
        if (policyValue == null || policyValue.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing 'policy' field", "validPolicies",
                            java.util.Arrays.stream(org.fabt.shelter.domain.DvAddressPolicy.values())
                                    .map(Enum::name).toList()));
        }

        try {
            org.fabt.shelter.domain.DvAddressPolicy.valueOf(policyValue);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid DV address policy value: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid policy: " + policyValue, "validPolicies",
                            java.util.Arrays.stream(org.fabt.shelter.domain.DvAddressPolicy.values())
                                    .map(Enum::name).toList()));
        }

        Map<String, Object> config = tenantService.getConfig(id);
        config.put("dv_address_visibility", policyValue);
        tenantService.updateConfig(id, config);

        log.warn("DV address visibility policy changed to {} for tenant {} by user {}",
                policyValue, id, authentication.getName());

        return ResponseEntity.ok(Map.of("policy", policyValue, "tenantId", id.toString()));
    }
}
