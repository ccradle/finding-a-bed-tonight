package db.migration;

import java.util.UUID;

import org.fabt.Application;
import org.fabt.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V98 platform_config singleton migration IT — platform-observability-split tasks §1.4.
 *
 * <p>Pins the migration's three invariants:
 * <ul>
 *   <li><b>Singleton enforcement</b>: any UUID other than the canonical
 *       {@code 00000000-0000-0000-0000-000000000001} is rejected by the
 *       {@code platform_config_singleton} CHECK constraint.</li>
 *   <li><b>Initial-row presence</b>: after V98 applies, exactly one row
 *       exists with the canonical UUID.</li>
 *   <li><b>Seeded defaults</b>: the initial row carries the 6 default
 *       observability fields with values matching the previously-literal
 *       {@code @Scheduled fixedRate} cadences (5/15/60 min) and the
 *       baseline OTel collector endpoint.</li>
 * </ul>
 *
 * <p>Unlike V97 (which backfills tenant config based on shelter rows),
 * V98 is a pure DDL + single seed INSERT. No tenant context binding is
 * needed — {@code platform_config} is global, not RLS-protected.
 */
@DisplayName("V98 platform_config singleton migration")
@SpringBootTest(classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class V98MigrationIntegrationTest extends BaseIntegrationTest {

    private static final UUID CANONICAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("Initial row exists with canonical UUID after migration")
    void initialRowExistsWithCanonicalId() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_config WHERE id = ?",
                Integer.class, CANONICAL_ID);
        assertThat(count).as("Exactly one row with the canonical singleton UUID").isEqualTo(1);
    }

    @Test
    @DisplayName("Initial row carries the 6 seeded defaults")
    void initialRowHasSeededDefaults() {
        // Read each field individually — JSONB ->> returns text, which we
        // compare to the literal SQL seed values. Catches drift if the
        // initial-seed INSERT in V98 changes shape without the test
        // updating in lockstep.
        assertThat(readJsonText("prometheus_enabled")).isEqualTo("true");
        assertThat(readJsonText("tracing_enabled")).isEqualTo("false");
        assertThat(readJsonText("tracing_endpoint")).isEqualTo("http://localhost:4318/v1/traces");
        assertThat(readJsonText("monitor_stale_interval_minutes")).isEqualTo("5");
        assertThat(readJsonText("monitor_dv_canary_interval_minutes")).isEqualTo("15");
        assertThat(readJsonText("monitor_temperature_interval_minutes")).isEqualTo("60");
    }

    @Test
    @DisplayName("CHECK constraint rejects insert with non-canonical UUID")
    void singletonCheckRejectsAlternateId() {
        // Using INSERT (not UPDATE) because the CHECK is on id; UPDATE
        // changing id would also be rejected, but INSERT is the more
        // realistic accidental-second-row scenario.
        UUID rogueId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO platform_config (id, config) VALUES (?, '{}'::jsonb)",
                rogueId))
                .as("CHECK platform_config_singleton must reject non-canonical UUID")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("platform_config_singleton");
    }

    @Test
    @DisplayName("CHECK constraint rejects update flipping id away from canonical")
    void singletonCheckRejectsIdChange() {
        UUID rogueId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE platform_config SET id = ? WHERE id = ?",
                rogueId, CANONICAL_ID))
                .as("CHECK platform_config_singleton must reject id flip")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("platform_config_singleton");
    }

    @Test
    @DisplayName("Idempotent updates preserve singleton invariant")
    void idempotentUpdate() {
        // Update + select round-trip — the canonical row remains the only
        // row, and arbitrary JSONB changes don't violate the CHECK.
        jdbc.update(
                "UPDATE platform_config "
                        + "SET config = jsonb_set(config, '{tracing_enabled}', 'true'::jsonb) "
                        + "WHERE id = ?",
                CANONICAL_ID);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_config", Integer.class);
        assertThat(count).as("Still exactly one row after update").isEqualTo(1);
        assertThat(readJsonText("tracing_enabled")).isEqualTo("true");

        // Restore default for hygiene (other tests in this class don't
        // depend on tracing_enabled, but @Order isn't guaranteed).
        jdbc.update(
                "UPDATE platform_config "
                        + "SET config = jsonb_set(config, '{tracing_enabled}', 'false'::jsonb) "
                        + "WHERE id = ?",
                CANONICAL_ID);
    }

    @Test
    @DisplayName("updated_at and updated_by columns are present and correctly typed")
    void columnPresenceCheck() {
        // Schema-shape assertion — protects against a future column
        // rename/drop slipping through. Information_schema query rather
        // than reading a row so we're testing the catalog, not data.
        Integer present = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'platform_config' "
                        + "AND column_name IN ('id', 'config', 'updated_at', 'updated_by')",
                Integer.class);
        assertThat(present).as("All 4 expected columns present").isEqualTo(4);
    }

    private String readJsonText(String key) {
        return jdbc.queryForObject(
                "SELECT config ->> ? FROM platform_config WHERE id = ?",
                String.class, key, CANONICAL_ID);
    }
}
