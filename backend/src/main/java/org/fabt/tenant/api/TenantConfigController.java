package org.fabt.tenant.api;

import java.util.Map;
import java.util.UUID;

import org.fabt.tenant.service.TenantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/config")
public class TenantConfigController {

    private final TenantService tenantService;

    public TenantConfigController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig(@PathVariable UUID tenantId) {
        Map<String, Object> config = tenantService.getConfig(tenantId);
        return ResponseEntity.ok(config);
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> updateConfig(@PathVariable UUID tenantId,
                                                             @RequestBody Map<String, Object> config) {
        tenantService.updateConfig(tenantId, config);
        return ResponseEntity.ok(tenantService.getConfig(tenantId));
    }
}
