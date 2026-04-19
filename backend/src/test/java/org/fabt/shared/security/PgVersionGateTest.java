package org.fabt.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.fabt.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Testcontainers-backed assertion that the CI PostgreSQL image meets FABT's
 * minimum supported version floor (16.5 / {@code server_version_num >= 160005}).
 *
 * <p>Paired with {@link PgVersionGate} — the component runs at every JVM
 * boot and halts startup on floor breach, but only against whatever image
 * the runtime is pointed at. This test is the CI-time guard catching the
 * "someone bumped the CI image backwards" case before it reaches prod.
 */
class PgVersionGateTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PgVersionGate pgVersionGate;

    @Test
    void testImageMeetsMinimumServerVersion() {
        Integer actual = jdbcTemplate.queryForObject(
                "SELECT current_setting('server_version_num')::int", Integer.class);

        assertThat(actual)
                .as("Testcontainers Postgres image exposes server_version_num")
                .isNotNull();
        assertThat(actual)
                .as("CI image at or above FABT's 16.5 floor (bump PgVersionGate.MIN_SERVER_VERSION_NUM when raising)")
                .isGreaterThanOrEqualTo(PgVersionGate.MIN_SERVER_VERSION_NUM);
    }

    @Test
    void startupGateBeanIsRegistered() {
        assertThat(pgVersionGate)
                .as("PgVersionGate @Component registered and @PostConstruct completed without halting boot")
                .isNotNull();
    }
}
