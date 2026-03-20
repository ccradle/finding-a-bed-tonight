package org.fabt.tenant.api;

import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import jakarta.validation.Valid;
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

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping
    public ResponseEntity<TenantResponse> create(@Valid @RequestBody CreateTenantRequest request) {
        Tenant tenant = tenantService.create(request.name(), request.slug());
        return ResponseEntity.status(HttpStatus.CREATED).body(TenantResponse.from(tenant));
    }

    @GetMapping
    public ResponseEntity<List<TenantResponse>> listAll() {
        List<TenantResponse> tenants = StreamSupport.stream(tenantService.findAll().spliterator(), false)
                .map(TenantResponse::from)
                .toList();
        return ResponseEntity.ok(tenants);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TenantResponse> getById(@PathVariable UUID id) {
        return tenantService.findById(id)
                .map(TenantResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new java.util.NoSuchElementException("Tenant not found: " + id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TenantResponse> update(@PathVariable UUID id,
                                                  @Valid @RequestBody UpdateTenantRequest request) {
        Tenant tenant = tenantService.update(id, request.name());
        return ResponseEntity.ok(TenantResponse.from(tenant));
    }
}
