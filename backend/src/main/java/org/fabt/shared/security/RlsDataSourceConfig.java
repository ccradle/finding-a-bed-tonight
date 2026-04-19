package org.fabt.shared.security;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.fabt.shared.web.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Configures an RLS-aware DataSource that wraps HikariCP.
 *
 * Every time a JDBC connection is borrowed from the pool, the wrapper:
 *
 *   1. SET ROLE fabt_app — drops to the restricted application role so RLS enforces.
 *      PostgreSQL superusers bypass RLS entirely; this ensures enforcement in all
 *      environments including Testcontainers (which creates a SUPERUSER).
 *
 *   2. set_config('app.dv_access', 'true'/'false', false) — sets the session variable
 *      that the RLS policy checks. Session-scoped (is_local=false) so it persists for
 *      all queries on this connection. Reset on every getConnection() call.
 *
 * The RLS policy (V8_1) reads this session variable:
 *   COALESCE(NULLIF(current_setting('app.dv_access', true), '')::boolean, false) = true
 *
 * Defense-in-depth: service-layer checks (e.g., ReferralTokenService) also verify
 * TenantContext.getDvAccess() independently of RLS (Design D14).
 *
 * @see org.fabt.shared.web.TenantContext#getDvAccess()
 */
@Configuration
public class RlsDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(RlsDataSourceConfig.class);

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource hikariDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @Primary
    public DataSource dataSource(HikariDataSource hikariDataSource,
                                 ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new RlsAwareDataSource(hikariDataSource, meterRegistryProvider.getIfAvailable());
    }

    /**
     * DataSource decorator that injects the current user's dvAccess flag
     * into every JDBC connection before application code runs a query.
     *
     * <p>Emits {@code fabt.rls.tenant_context.empty.count} (Phase B
     * observability) every time a connection is borrowed while
     * {@link TenantContext#getTenantId()} is null. Non-zero steady-state
     * after v0.43 + 7 days means a hot code path is taking connections
     * without binding tenant first — the kind of pattern that produces
     * silent SYSTEM_TENANT_ID audit rows + FORCE-RLS rejections.</p>
     */
    static final class RlsAwareDataSource extends DelegatingDataSource {

        private final Counter tenantContextEmptyCounter;

        RlsAwareDataSource(DataSource delegate, MeterRegistry meterRegistry) {
            super(delegate);
            this.tenantContextEmptyCounter = meterRegistry != null
                    ? Counter.builder("fabt.rls.tenant_context.empty.count")
                            .description("JDBC connections borrowed while TenantContext is unbound "
                                    + "(Phase B D55 signal; non-zero = publisher / scheduler forgot runWithContext)")
                            .register(meterRegistry)
                    : null;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection conn = super.getConnection();
            applyRlsContext(conn);
            return conn;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            Connection conn = super.getConnection(username, password);
            applyRlsContext(conn);
            return conn;
        }

        private void applyRlsContext(Connection conn) throws SQLException {
            boolean dvAccess = TenantContext.getDvAccess();
            java.util.UUID userId = TenantContext.getUserId();
            java.util.UUID tenantId = TenantContext.getTenantId();
            if (tenantId == null && tenantContextEmptyCounter != null) {
                tenantContextEmptyCounter.increment();
            }
            try {
                // Single round-trip: SET ROLE + set_config in one statement.
                // Eliminates extra round-trips that compound under virtual thread
                // burst concurrency (p99 regression under Gatling mixed load).
                //
                // SET ROLE fabt_app: drop to restricted role so RLS enforces.
                // PostgreSQL superusers bypass RLS — SET ROLE to NOSUPERUSER role
                // ensures RLS applies in ALL environments including Testcontainers (D14).
                //
                // set_config('app.dv_access', ...): session-scoped variable for DV shelter RLS.
                // set_config('app.current_user_id', ...): session-scoped variable for notification RLS.
                //   - Nil UUID when no user context (scheduled jobs, API key auth) → fails closed
                //     (notification SELECT returns nothing, which is correct for system operations).
                // set_config('app.tenant_id', ...): session-scoped variable installed as
                //   infrastructure for multi-tenant-production-readiness D14 (tenant-RLS
                //   on regulated tables). No current RLS policy reads it — Elena's
                //   defense-in-depth insistence (D13). Empty string when null (scheduled-
                //   task / batch-job case where TenantContext is unset).
                // set_config('application_name', 'fabt:tenant:<uuid>', ...): tags every
                //   pgaudit log line + pg_stat_activity row with the bound tenant so an
                //   operator reading the audit log can correlate a statement to its tenant
                //   without joining by timestamp+pid (task 3.11 follow-up). Co-located with
                //   app.tenant_id in the SAME prepared statement so the two values cannot
                //   drift — a divergence between them would mean pgaudit logs the wrong
                //   tenant while RLS enforces the right one, which is worse than no tag.
                //   Drift-safety is covered by PgauditApplicationNameDriftTest.
                // Callers bind context via TenantContext.runWithContext() BEFORE queries.
                String userIdStr = userId != null ? userId.toString() : "00000000-0000-0000-0000-000000000000";
                String tenantIdStr = tenantId != null ? tenantId.toString() : "";
                String applicationNameStr = "fabt:tenant:" + (tenantId != null ? tenantIdStr : "none");
                try (java.sql.PreparedStatement pstmt = conn.prepareStatement(
                        "SET ROLE fabt_app; SELECT set_config('app.dv_access', ?, false), "
                                + "set_config('app.current_user_id', ?, false), "
                                + "set_config('app.tenant_id', ?, false), "
                                + "set_config('application_name', ?, false)")) {
                    pstmt.setString(1, String.valueOf(dvAccess));
                    pstmt.setString(2, userIdStr);
                    pstmt.setString(3, tenantIdStr);
                    pstmt.setString(4, applicationNameStr);
                    pstmt.execute();
                }
            } catch (SQLException e) {
                log.error("Failed to apply RLS context on connection; closing to prevent data leak", e);
                conn.close();
                throw e;
            }
        }
    }
}
