package org.fabt.auth;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.shared.web.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests PostgreSQL Row Level Security for DV shelter data protection.
 *
 * RLS with FORCE ROW LEVEL SECURITY applies to ALL roles INCLUDING the table owner,
 * BUT PostgreSQL superusers bypass RLS entirely. Testcontainers creates a superuser
 * by default, so we create a non-superuser role for RLS testing.
 */
class DvAccessRlsTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant("rls-test-tenant");
        tenantId = authHelper.getTestTenantId();

        // Test setup needs dvAccess=true to INSERT DV shelters (RLS enforces via SET ROLE fabt_app)
        TenantContext.runWithContext(tenantId, true, () -> {
            // Clean up previous test shelters
            jdbcTemplate.update(
                    "DELETE FROM shelter WHERE tenant_id = ? AND name IN ('Regular Shelter RLS', 'DV Shelter RLS')",
                    tenantId
            );

            // Insert test shelters
            jdbcTemplate.update(
                    "INSERT INTO shelter (id, tenant_id, name, dv_shelter, created_at, updated_at) " +
                            "VALUES (?, ?, 'Regular Shelter RLS', false, NOW(), NOW())",
                    UUID.randomUUID(), tenantId
            );
            jdbcTemplate.update(
                    "INSERT INTO shelter (id, tenant_id, name, dv_shelter, created_at, updated_at) " +
                            "VALUES (?, ?, 'DV Shelter RLS', true, NOW(), NOW())",
                    UUID.randomUUID(), tenantId
            );
        });

        // fabt_app role (created in V16) is NOSUPERUSER — RLS enforces.
        // No need to create a separate test role; SET ROLE fabt_app is already applied
        // on every connection by RlsDataSourceConfig (D14).
    }

    @Test
    void test_userWithDvAccess_seesDvShelters() {
        List<String> names = querySheltersAs(true);
        assertThat(names).contains("Regular Shelter RLS");
        assertThat(names).contains("DV Shelter RLS");
    }

    @Test
    void test_userWithoutDvAccess_cannotSeeDvShelters() {
        List<String> names = querySheltersAs(false);
        assertThat(names).contains("Regular Shelter RLS");
        assertThat(names).doesNotContain("DV Shelter RLS");
    }

    @Test
    void test_dvAccessDefault_excludesDvShelters() {
        // When app.dv_access is not set, default is false → DV shelters excluded
        List<String> names = querySheltersAsDefault();
        assertThat(names).contains("Regular Shelter RLS");
        assertThat(names).doesNotContain("DV Shelter RLS");
    }

    /**
     * Query shelters using a non-superuser connection that respects RLS,
     * with the specified dv_access setting.
     */
    private List<String> querySheltersAs(boolean dvAccess) {
        return jdbcTemplate.execute((java.sql.Connection conn) -> {
            boolean origAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (var stmt = conn.createStatement()) {
                stmt.execute("SET LOCAL ROLE fabt_app");
                stmt.execute("SET LOCAL app.dv_access = '" + dvAccess + "'");
                try (var rs = stmt.executeQuery(
                        "SELECT name FROM shelter WHERE tenant_id = '" + tenantId +
                                "' AND name IN ('Regular Shelter RLS', 'DV Shelter RLS') ORDER BY name"
                )) {
                    List<String> results = new java.util.ArrayList<>();
                    while (rs.next()) {
                        results.add(rs.getString("name"));
                    }
                    return results;
                }
            } finally {
                conn.rollback();
                conn.setAutoCommit(origAutoCommit);
            }
        });
    }

    private List<String> querySheltersAsDefault() {
        return jdbcTemplate.execute((java.sql.Connection conn) -> {
            boolean origAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (var stmt = conn.createStatement()) {
                stmt.execute("SET LOCAL ROLE fabt_app");
                stmt.execute("RESET app.dv_access");
                try (var rs = stmt.executeQuery(
                        "SELECT name FROM shelter WHERE tenant_id = '" + tenantId +
                                "' AND name IN ('Regular Shelter RLS', 'DV Shelter RLS') ORDER BY name"
                )) {
                    List<String> results = new java.util.ArrayList<>();
                    while (rs.next()) {
                        results.add(rs.getString("name"));
                    }
                    return results;
                }
            } finally {
                conn.rollback();
                conn.setAutoCommit(origAutoCommit);
            }
        });
    }
}
