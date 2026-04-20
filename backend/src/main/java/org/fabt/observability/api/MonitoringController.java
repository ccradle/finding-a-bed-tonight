package org.fabt.observability.api;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import org.fabt.observability.OperationalMonitorService;
import org.fabt.observability.OperationalMonitorService.TemperatureStatus;
import org.fabt.shared.web.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/monitoring")
public class MonitoringController {

    private final OperationalMonitorService monitorService;

    public MonitoringController(OperationalMonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @Operation(
            summary = "Get current temperature monitoring status",
            description = "Returns the cached NOAA temperature reading for the caller's tenant: station ID, " +
                    "configured threshold, surge active state, gap detected state, and last check timestamp. " +
                    "Does not trigger an additional NOAA API call — reads from the per-tenant cached state of " +
                    "the scheduled monitor."
    )
    @GetMapping("/temperature")
    public ResponseEntity<TemperatureStatus> getTemperatureStatus() {
        UUID tenantId = TenantContext.getTenantId();
        TemperatureStatus status = tenantId != null ? monitorService.getTemperatureStatus(tenantId) : null;
        if (status == null) {
            return ResponseEntity.ok(new TemperatureStatus(null, null, 32.0, false, false, null));
        }
        return ResponseEntity.ok(status);
    }
}
