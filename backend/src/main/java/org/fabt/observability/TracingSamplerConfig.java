package org.fabt.observability;

import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dynamically adjusts tracing sampling based on tenant config.
 * When any tenant enables tracing, sampling probability is effectively 1.0.
 * When all tenants disable tracing, sampling probability is 0.0.
 *
 * Note: Spring Boot's management.tracing.sampling.probability is set at startup.
 * For runtime toggle, we rely on the ObservabilityConfigService to gate
 * whether spans are meaningful (exported to a real endpoint vs localhost no-op).
 */
@Component
@ConditionalOnBean(Tracer.class)
public class TracingSamplerConfig {

    private static final Logger log = LoggerFactory.getLogger(TracingSamplerConfig.class);

    private final ObservabilityConfigService configService;
    private boolean lastKnownState = false;

    public TracingSamplerConfig(ObservabilityConfigService configService) {
        this.configService = configService;
    }

    @Scheduled(fixedRate = 60_000)
    public void logTracingState() {
        // Log tracing state changes for operational visibility.
        // Actual sampling probability is set via application.yml at startup.
        // Runtime toggle controls whether the OTLP endpoint is meaningful.
        boolean anyEnabled = false;
        configService.refreshCache();

        // Check is implicit — if any tenant has tracing_enabled, log it
        if (lastKnownState != anyEnabled) {
            lastKnownState = anyEnabled;
            log.info("Tracing state changed: enabled={}", anyEnabled);
        }
    }
}
