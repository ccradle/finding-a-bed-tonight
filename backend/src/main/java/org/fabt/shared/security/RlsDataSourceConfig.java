package org.fabt.shared.security;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.fabt.shared.web.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Configures an RLS-aware DataSource that wraps HikariCP.
 *
 * Every time a JDBC connection is borrowed from the pool, the wrapper executes:
 *
 *   SET LOCAL app.dv_access = 'true' (or 'false')
 *
 * SET LOCAL is transaction-scoped in PostgreSQL — it is automatically cleared
 * when the transaction commits or rolls back, making it safe with connection pooling.
 *
 * The RLS policy (V8_1) reads this session variable:
 *
 *   COALESCE(NULLIF(current_setting('app.dv_access', true), '')::boolean, false) = true
 *
 * Pattern validated by: DvAccessRlsTest.java (uses SET LOCAL ROLE + SET LOCAL app.dv_access)
 * Pattern sourced from: github.com/AbdennasserBentaleb/Multi-Tenant-JWT-Security-Gateway
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
            // Use set_config with is_local=false (session-scoped) so the setting
            // persists for all queries on this connection regardless of autoCommit.
            // The value is reset on every getConnection() call, so pooled connections
            // always get the correct value for the current request.
            try (java.sql.PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT set_config('app.dv_access', ?, false)")) {
                pstmt.setString(1, String.valueOf(dvAccess));
                pstmt.execute();
            } catch (SQLException e) {
                log.error("Failed to set app.dv_access on connection; closing to prevent data leak", e);
                conn.close();
                throw e;
            }
        }
    }
}
