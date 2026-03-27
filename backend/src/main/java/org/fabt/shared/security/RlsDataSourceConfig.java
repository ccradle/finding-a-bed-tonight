package org.fabt.shared.security;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.fabt.shared.web.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    public DataSource dataSource(HikariDataSource hikariDataSource) {
        return new RlsAwareDataSource(hikariDataSource);
    }

    /**
     * DataSource decorator that injects the current user's dvAccess flag
     * into every JDBC connection before application code runs a query.
     */
    static final class RlsAwareDataSource extends DelegatingDataSource {

        RlsAwareDataSource(DataSource delegate) {
            super(delegate);
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
            try {
                // Single round-trip: SET ROLE + set_config in one statement.
                // Eliminates the 2nd round-trip that compounded under virtual thread
                // burst concurrency (p99 regression under Gatling mixed load).
                //
                // SET ROLE fabt_app: drop to restricted role so RLS enforces.
                // PostgreSQL superusers bypass RLS — SET ROLE to NOSUPERUSER role
                // ensures RLS applies in ALL environments including Testcontainers (D14).
                //
                // set_config('app.dv_access', ...): session-scoped variable for RLS policy.
                // Callers bind dvAccess via TenantContext.runWithContext() BEFORE queries.
                try (java.sql.PreparedStatement pstmt = conn.prepareStatement(
                        "SET ROLE fabt_app; SELECT set_config('app.dv_access', ?, false)")) {
                    pstmt.setString(1, String.valueOf(dvAccess));
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
