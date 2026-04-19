package org.fabt.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.fabt.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration test for {@link ForceRlsHealthGauge}. Validates the v0.45
 * W-GAUGE-3 warroom fix — the poll query now binds the regulated-table
 * list as a typed {@code java.sql.Array} via {@code createArrayOf("text",
 * ...)} rather than the legacy PostgreSQL array-literal string.
 *
 * <p>Verifies that after {@code poll()} executes:
 * <ol>
 *   <li>The query completes without SQL errors (the Array binding works
 *       against the CI Postgres image).</li>
 *   <li>Micrometer exposes one gauge per regulated table with the
 *       expected label set.</li>
 *   <li>Gauges read {@code 1} on the Testcontainers database where
 *       V69 has enabled FORCE RLS on all seven tables.</li>
 * </ol>
 */
@DisplayName("ForceRlsHealthGauge — W-GAUGE-3 java.sql.Array binding (task #166)")
class ForceRlsHealthGaugeTest extends BaseIntegrationTest {

    @Autowired private ForceRlsHealthGauge gauge;
    @Autowired private MeterRegistry meterRegistry;

    @Test
    @DisplayName("poll() completes without error and publishes one gauge per regulated table")
    void pollPublishesGaugesForAllRegulatedTables() {
        // @PostConstruct already registered the gauges; fire poll() once
        // more to exercise the Array-binding query path in isolation.
        gauge.poll();

        String[] expectedTables = {
                "audit_events",
                "hmis_audit_log",
                "hmis_outbox",
                "kid_to_tenant_key",
                "one_time_access_code",
                "password_reset_token",
                "tenant_key_material"
        };

        for (String table : expectedTables) {
            Double value = meterRegistry.find("fabt.rls.force_rls_enabled")
                    .tags(Tags.of("table", table))
                    .gauge()
                    .value();
            assertThat(value)
                    .as("fabt.rls.force_rls_enabled{table=%s} gauge is registered and reads 1 "
                            + "on Testcontainers where V69 has applied FORCE RLS to all regulated tables", table)
                    .isEqualTo(1.0);
        }
    }
}
