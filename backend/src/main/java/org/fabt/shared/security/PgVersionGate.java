package org.fabt.shared.security;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Startup gate asserting the live PostgreSQL server meets FABT's minimum
 * supported version. Runs once at bean initialization; throws
 * {@link IllegalStateException} if the floor is breached, which Spring's
 * bootstrap converts into a non-zero JVM exit.
 *
 * <p><b>Floor:</b> {@code server_version_num >= 160005} (PostgreSQL 16.5).
 * Task 3.2 of the multi-tenant-production-readiness change set that floor
 * during Phase B; earlier releases lacked the {@code pg_policies.permissive}
 * column this codebase relies on for FORCE RLS policy snapshots.
 *
 * <h2>Why this is needed alongside {@code PgVersionGateTest}</h2>
 * <p>The Testcontainers integration test tautologically passes whatever
 * image CI tells it to run (currently {@code postgres:16-alpine}, which
 * resolves to 16.13). It catches the class of bug where someone bumps the
 * CI image backwards but does not catch prod drift — an operator running
 * an unpatched 16.4 pgaudit image on the VM would sail past CI. This
 * startup check runs on every boot (CI + prod) against the actual live
 * server, closing that gap.
 *
 * <h2>Bumping the floor</h2>
 * Per the v0.45.0 warroom, this floor doubles as a CVE gate — revisit on
 * every PostgreSQL minor release. When bumping, update (a) this constant,
 * (b) the equivalent assertion in {@code PgVersionGateTest}, and (c) the
 * "PG version bump" checklist entry in {@code docs/runbook.md}.
 */
@Component
public class PgVersionGate {

    /**
     * Minimum acceptable {@code server_version_num} — PostgreSQL 16.5.
     * PostgreSQL encodes versions as {@code major * 10000 + minor} (since
     * 10.x); 16.5 → 160005, 16.6 → 160006.
     */
    static final int MIN_SERVER_VERSION_NUM = 160005;

    private final JdbcTemplate jdbcTemplate;

    public PgVersionGate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void assertMinimumVersion() {
        Integer actual = jdbcTemplate.queryForObject(
                "SELECT current_setting('server_version_num')::int", Integer.class);
        if (actual == null || actual < MIN_SERVER_VERSION_NUM) {
            throw new IllegalStateException(
                    "PostgreSQL server_version_num=" + actual
                            + " is below FABT's supported floor "
                            + MIN_SERVER_VERSION_NUM
                            + " (PostgreSQL 16.5). Upgrade the server or the CI image.");
        }
    }
}
