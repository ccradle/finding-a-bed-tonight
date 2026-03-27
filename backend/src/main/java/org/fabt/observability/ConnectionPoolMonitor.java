package org.fabt.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Monitors HikariCP connection pool pressure under virtual thread concurrency.
 *
 * Virtual threads can issue many concurrent I/O operations that all compete for
 * pooled connections. This monitor logs a WARNING when pending connection requests
 * exceed half the pool size, indicating the pool is becoming a bottleneck.
 */
@Component
public class ConnectionPoolMonitor {

    private static final Logger log = LoggerFactory.getLogger(ConnectionPoolMonitor.class);

    private final MeterRegistry meterRegistry;

    public ConnectionPoolMonitor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedRate = 30_000)
    public void checkConnectionPoolPressure() {
        checkPool("HikariPool-1");
    }

    private void checkPool(String poolName) {
        Gauge pending = meterRegistry.find("hikaricp.connections.pending")
                .tag("pool", poolName)
                .gauge();
        Gauge max = meterRegistry.find("hikaricp.connections.max")
                .tag("pool", poolName)
                .gauge();
        Gauge active = meterRegistry.find("hikaricp.connections.active")
                .tag("pool", poolName)
                .gauge();

        if (pending == null || max == null) {
            return;
        }

        double pendingCount = pending.value();
        double maxSize = max.value();
        double activeCount = active != null ? active.value() : 0;
        double threshold = maxSize / 2;

        if (pendingCount > threshold) {
            log.warn("Connection pool pressure: pool={}, pending={}, active={}, max={}",
                    poolName, (int) pendingCount, (int) activeCount, (int) maxSize);
        }
    }
}
