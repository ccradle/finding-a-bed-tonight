package org.fabt.architecture;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 3.24 — pgaudit log-entry regression guard.
 *
 * <p>Verifies that the {@code deploy/pgaudit.Dockerfile} image actually
 * emits pgaudit log lines for regulated-table writes and, specifically,
 * for {@code ALTER TABLE ... NO FORCE ROW LEVEL SECURITY} — the DDL
 * pattern that the {@code FabtPhaseBNoForceRlsDdl} Alertmanager rule
 * watches for. If this test fails, the image's pgaudit preload is broken
 * or the session-parameter configuration (normally set by Flyway V73)
 * is not being honored — either way, the detection-of-last-resort
 * tripwire for FORCE RLS clears is down.
 *
 * <h2>CI tag — pgaudit-only</h2>
 * {@code @Tag("pgaudit")} so the default CI profile (running the 700+
 * test suite against {@code postgres:16-alpine}) skips this class. A
 * dedicated {@code pgaudit} CI profile {@code docker build}s
 * {@link #PGAUDIT_IMAGE} from {@code deploy/pgaudit.Dockerfile} before
 * running tests carrying this tag. Per Phase B warroom V5 — avoids
 * flaking the main suite on a cross-image test dependency.
 *
 * <h2>Image provisioning contract</h2>
 * Testcontainers-postgresql 2.0.4 has no {@code ImageFromDockerfile}
 * constructor on {@code PostgreSQLContainer} (only {@code String} and
 * {@link DockerImageName}). This test therefore <em>requires the image
 * to be pre-built</em> at tag {@link #PGAUDIT_IMAGE}. Locally that's
 * {@code docker build -t fabt-pgaudit:ci -f deploy/pgaudit.Dockerfile
 * deploy/}; in CI, a workflow step does the same before invoking the
 * {@code @Tag("pgaudit")} test subset.
 *
 * <p>{@link DockerImageName#asCompatibleSubstituteFor(String)} tells
 * Testcontainers that our custom image is a drop-in replacement for
 * {@code postgres} — otherwise its image-verification hook would reject
 * the non-official tag.
 *
 * <h2>Why a Testcontainers-native test, not a Spring integration test</h2>
 * This test exercises PostgreSQL image behavior, not FABT service
 * behavior. Spring context would add 10-20s of startup for no coverage
 * gain. The test connects directly via JDBC, runs a handful of SQL
 * statements, reads container stderr, asserts on pgaudit log content.
 * No application code under test — the image is the system under test.
 *
 * <p>Based on patterns established in
 * {@code corey-portfolio-platform} PLATFORM-STANDARDS lesson 66
 * (RLS + non-superuser role in Testcontainers) and
 * {@code BaseIntegrationTest} (Testcontainers singleton pattern;
 * here we don't need the singleton because this test is isolated).
 */
@Tag("pgaudit")
@DisplayName("Task 3.24 — pgaudit image emits AUDIT lines for regulated writes + FORCE-RLS DDL")
class PgauditLogEntryTest {

    /**
     * The pre-built image tag. MUST exist before this test runs — see
     * class-level Javadoc. If absent, the test fails fast with a Docker
     * pull error that names the image, which is more actionable than
     * silently skipping.
     */
    private static final String PGAUDIT_IMAGE = "fabt-pgaudit:ci";

    @Test
    @DisplayName("CREATE EXTENSION pgaudit + INSERT + NO FORCE RLS produces AUDIT log lines")
    void pgauditEmitsAuditLinesForRegulatedWritesAndForceRlsDdl() throws Exception {
        DockerImageName image = DockerImageName.parse(PGAUDIT_IMAGE)
                .asCompatibleSubstituteFor("postgres");
        PostgreSQLContainer pg = new PostgreSQLContainer(image)
                .withDatabaseName("fabt_pgaudit_test")
                .withUsername("postgres")
                .withPassword("pgaudit_test");
        pg.start();
        try {
            // 1) Install the extension + configure the four session
            // parameters Flyway V73 sets. Doing it inline keeps the test
            // independent of the migration chain and reproducibly tied
            // to V73's parameter set.
            try (Connection conn = DriverManager.getConnection(
                    pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                 Statement st = conn.createStatement()) {
                st.execute("CREATE EXTENSION pgaudit");
                st.execute("ALTER DATABASE fabt_pgaudit_test "
                        + "SET pgaudit.log = 'write,ddl'");
                st.execute("ALTER DATABASE fabt_pgaudit_test "
                        + "SET pgaudit.log_level = 'log'");
                st.execute("ALTER DATABASE fabt_pgaudit_test "
                        + "SET pgaudit.log_parameter = 'off'");
                st.execute("ALTER DATABASE fabt_pgaudit_test "
                        + "SET pgaudit.log_relation = 'on'");
            }

            // 2) Reconnect so the new ALTER DATABASE settings take effect
            // at session start.
            try (Connection conn = DriverManager.getConnection(
                    pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                 Statement st = conn.createStatement()) {
                // DDL pgaudit logs as class=DDL
                st.execute("CREATE TABLE pgaudit_probe (id SERIAL PRIMARY KEY, note TEXT)");
                // WRITE pgaudit logs as class=WRITE
                st.execute("INSERT INTO pgaudit_probe (note) VALUES ('regulated-write-probe')");
                // THE tripwire DDL the alert rule matches on. The table
                // needs RLS enabled first so NO FORCE is a valid op.
                st.execute("ALTER TABLE pgaudit_probe ENABLE ROW LEVEL SECURITY");
                st.execute("ALTER TABLE pgaudit_probe NO FORCE ROW LEVEL SECURITY");
            } catch (SQLException e) {
                // Tolerate NO FORCE rejection — the log line is what matters.
                if (!e.getMessage().toLowerCase().contains("force")) {
                    throw e;
                }
            }

            // Small sleep to let the async log writer flush.
            Thread.sleep(500);
            String logs = pg.getLogs();

            assertThat(logs)
                    .as("pgaudit must emit an AUDIT prefix on log lines")
                    .contains("AUDIT:");

            assertThat(logs)
                    .as("pgaudit must log the DDL class for CREATE TABLE")
                    .containsPattern("(?i)AUDIT:\\s*SESSION,[^\\n]*,DDL,[^\\n]*CREATE TABLE");

            assertThat(logs)
                    .as("pgaudit must log the WRITE class for INSERT")
                    .containsPattern("(?i)AUDIT:\\s*SESSION,[^\\n]*,WRITE,[^\\n]*INSERT");

            assertThat(logs)
                    .as("pgaudit must log the NO FORCE RLS DDL attempt — this is the "
                            + "tripwire signal the FabtPhaseBNoForceRlsDdl Alertmanager "
                            + "rule matches on. If this fails, the alert rule has no "
                            + "log source to trigger from.")
                    .containsPattern("(?i)AUDIT:\\s*SESSION,[^\\n]*NO FORCE ROW LEVEL SECURITY");
        } finally {
            pg.stop();
        }
    }
}
