package org.fabt.auth;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class DvAccessRlsTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantId;
    private UUID regularShelterId;
    private UUID dvShelterId;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant("rls-test-tenant");
        tenantId = authHelper.getTestTenantId();

        // Clean up any previous test shelters for this tenant to avoid duplicates
        // (BeforeEach runs before each test)
        jdbcTemplate.update(
                "DELETE FROM shelter WHERE tenant_id = ? AND name IN ('Regular Shelter RLS', 'DV Shelter RLS')",
                tenantId
        );

        // Insert a regular (non-DV) shelter
        regularShelterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO shelter (id, tenant_id, name, dv_shelter, created_at, updated_at) " +
                        "VALUES (?, ?, 'Regular Shelter RLS', false, NOW(), NOW())",
                regularShelterId, tenantId
        );

        // Insert a DV shelter
        dvShelterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO shelter (id, tenant_id, name, dv_shelter, created_at, updated_at) " +
                        "VALUES (?, ?, 'DV Shelter RLS', true, NOW(), NOW())",
                dvShelterId, tenantId
        );
    }

    @Test
    void test_userWithDvAccess_seesDvShelters() {
        // Query shelters with dv_access set to true
        List<Map<String, Object>> shelters = jdbcTemplate.queryForList(
                "SELECT * FROM shelter WHERE tenant_id = ? AND name IN ('Regular Shelter RLS', 'DV Shelter RLS') " +
                        "ORDER BY name",
                tenantId
        );

        // The superuser/datasource connection bypasses RLS by default.
        // To properly test RLS, we need to SET LOCAL app.dv_access within a transaction.
        // JdbcTemplate operations run as the datasource user who is the table owner.
        // RLS is enforced even for table owner because we used FORCE ROW LEVEL SECURITY.

        // Test with dv_access = true: should see both shelters
        List<Map<String, Object>> withDvAccess = jdbcTemplate.execute(
                (java.sql.Connection conn) -> {
                    conn.setAutoCommit(false);
                    try (var stmt = conn.createStatement()) {
                        stmt.execute("SET LOCAL app.dv_access = 'true'");
                        try (var rs = stmt.executeQuery(
                                "SELECT name FROM shelter WHERE tenant_id = '" + tenantId +
                                        "' AND name IN ('Regular Shelter RLS', 'DV Shelter RLS') ORDER BY name"
                        )) {
                            List<Map<String, Object>> results = new java.util.ArrayList<>();
                            while (rs.next()) {
                                results.add(Map.of("name", rs.getString("name")));
                            }
                            return results;
                        }
                    } finally {
                        conn.rollback();
                        conn.setAutoCommit(true);
                    }
                }
        );

        assertThat(withDvAccess).isNotNull();
        List<String> names = withDvAccess.stream()
                .map(m -> (String) m.get("name"))
                .toList();
        assertThat(names).contains("Regular Shelter RLS");
        assertThat(names).contains("DV Shelter RLS");
    }

    @Test
    void test_userWithoutDvAccess_cannotSeeDvShelters() {
        // Test with dv_access = false: should only see regular shelters
        List<Map<String, Object>> withoutDvAccess = jdbcTemplate.execute(
                (java.sql.Connection conn) -> {
                    conn.setAutoCommit(false);
                    try (var stmt = conn.createStatement()) {
                        stmt.execute("SET LOCAL app.dv_access = 'false'");
                        try (var rs = stmt.executeQuery(
                                "SELECT name FROM shelter WHERE tenant_id = '" + tenantId +
                                        "' AND name IN ('Regular Shelter RLS', 'DV Shelter RLS') ORDER BY name"
                        )) {
                            List<Map<String, Object>> results = new java.util.ArrayList<>();
                            while (rs.next()) {
                                results.add(Map.of("name", rs.getString("name")));
                            }
                            return results;
                        }
                    } finally {
                        conn.rollback();
                        conn.setAutoCommit(true);
                    }
                }
        );

        assertThat(withoutDvAccess).isNotNull();
        List<String> names = withoutDvAccess.stream()
                .map(m -> (String) m.get("name"))
                .toList();
        assertThat(names).contains("Regular Shelter RLS");
        assertThat(names).doesNotContain("DV Shelter RLS");
    }

    @Test
    void test_dvAccessDefault_excludesDvShelters() {
        // When app.dv_access is not set at all, current_setting('app.dv_access', true)
        // returns empty string, which when cast to boolean defaults to false.
        // DV shelters should be excluded.
        List<Map<String, Object>> defaultAccess = jdbcTemplate.execute(
                (java.sql.Connection conn) -> {
                    conn.setAutoCommit(false);
                    try (var stmt = conn.createStatement()) {
                        // Reset the setting to ensure it's not set
                        stmt.execute("RESET app.dv_access");
                        try (var rs = stmt.executeQuery(
                                "SELECT name FROM shelter WHERE tenant_id = '" + tenantId +
                                        "' AND name IN ('Regular Shelter RLS', 'DV Shelter RLS') ORDER BY name"
                        )) {
                            List<Map<String, Object>> results = new java.util.ArrayList<>();
                            while (rs.next()) {
                                results.add(Map.of("name", rs.getString("name")));
                            }
                            return results;
                        }
                    } finally {
                        conn.rollback();
                        conn.setAutoCommit(true);
                    }
                }
        );

        assertThat(defaultAccess).isNotNull();
        List<String> names = defaultAccess.stream()
                .map(m -> (String) m.get("name"))
                .toList();
        assertThat(names).contains("Regular Shelter RLS");
        assertThat(names).doesNotContain("DV Shelter RLS");
    }
}
