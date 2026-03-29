package org.fabt.shared.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables @Scheduled task execution, gated by property.
 *
 * Disabled in integration tests (fabt.scheduling.enabled=false in application-lite.yml)
 * to prevent @Scheduled monitors (DV canary, stale shelter check, SSE keepalive) from
 * firing during context startup and exhausting the HikariCP connection pool.
 *
 * Production and dev profiles leave the property unset (matchIfMissing=true → enabled).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(value = "fabt.scheduling.enabled", matchIfMissing = true)
public class SchedulingConfig {
}
