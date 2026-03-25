package org.fabt.analytics.config;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.fabt.shared.web.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Separate HikariCP connection pool for analytics queries (Design D10).
 *
 * Analytics queries (multi-month aggregations, full scans) can evict hot index pages
 * from PostgreSQL's shared buffers, causing OLTP bed search p99 to spike. This config
 * creates an isolated pool with:
 *
 *   - max-pool-size=3 (limit analytics concurrency)
 *   - read-only=true
 *   - statement_timeout=30s (kill runaway queries)
 *   - work_mem=256MB (allow larger sorts for aggregation)
 *
 * The primary OLTP pool (10 connections) remains unchanged and is used by all
 * existing repositories. Only analytics module repositories inject this pool.
 *
 * RLS enforcement: the wrapper applies SET ROLE fabt_app + dvAccess, same as the
 * primary pool, so DV shelter data protection is maintained.
 */
@Configuration
public class AnalyticsDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsDataSourceConfig.class);

    @Bean
    @Qualifier("analyticsDataSource")
    public DataSource analyticsDataSource(DataSourceProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.getUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setPoolName("analytics-pool");
        config.setMaximumPoolSize(3);
        config.setConnectionTimeout(30_000); // 30s — analytics can wait
        config.setReadOnly(true);

        // Set statement_timeout and work_mem on every new connection
        config.setConnectionInitSql(
                "SET LOCAL statement_timeout = '30s'; SET LOCAL work_mem = '256MB'");

        HikariDataSource hikari = new HikariDataSource(config);
        return new AnalyticsRlsAwareDataSource(hikari);
    }

    /**
     * RLS-aware wrapper for the analytics pool. Same defense-in-depth pattern
     * as the primary DataSource: SET ROLE + dvAccess session variable.
     */
    static final class AnalyticsRlsAwareDataSource extends DelegatingDataSource {

        AnalyticsRlsAwareDataSource(DataSource delegate) {
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
                try (java.sql.Statement stmt = conn.createStatement()) {
                    stmt.execute("SET ROLE fabt_app");
                }
                try (java.sql.PreparedStatement pstmt = conn.prepareStatement(
                        "SELECT set_config('app.dv_access', ?, false)")) {
                    pstmt.setString(1, String.valueOf(dvAccess));
                    pstmt.execute();
                }
            } catch (SQLException e) {
                log.error("Failed to apply RLS context on analytics connection; closing", e);
                conn.close();
                throw e;
            }
        }
    }
}
