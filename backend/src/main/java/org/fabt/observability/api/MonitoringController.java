package org.fabt.observability.api;

import io.swagger.v3.oas.annotations.Operation;
import org.fabt.observability.OperationalMonitorService;
import org.fabt.observability.OperationalMonitorService.TemperatureStatus;
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
            description = "Returns the cached NOAA temperature reading, station ID, configured threshold, " +
                    "surge active state, gap detected state, and last check timestamp. Does not trigger " +
                    "an additional NOAA API call — reads from the cached state of the scheduled monitor."
    )
    @GetMapping("/temperature")
    public ResponseEntity<TemperatureStatus> getTemperatureStatus() {
        TemperatureStatus status = monitorService.getTemperatureStatus();
        if (status == null) {
            // Monitor hasn't run yet — return defaults
            return ResponseEntity.ok(new TemperatureStatus(null, null, 32.0, false, false, null));
        }
        return ResponseEntity.ok(status);
    }
}
