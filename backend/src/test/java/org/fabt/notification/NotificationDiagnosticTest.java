package org.fabt.notification;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.notification.domain.Notification;
import org.fabt.notification.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Diagnostic test — inspects the actual DB state for the notification table.
 * Run this to diagnose RLS INSERT failures.
 */
class NotificationDiagnosticTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void diagnoseNotificationTableState() {
        System.out.println("\n========== NOTIFICATION TABLE DIAGNOSTIC ==========\n");

        // 1. Current role
        String currentUser = jdbcTemplate.queryForObject("SELECT current_user", String.class);
        String sessionUser = jdbcTemplate.queryForObject("SELECT session_user", String.class);
        System.out.println("current_user: " + currentUser);
        System.out.println("session_user: " + sessionUser);

        // 2. RLS status
        Map<String, Object> rlsStatus = jdbcTemplate.queryForMap(
                "SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = 'notification'");
        System.out.println("relrowsecurity: " + rlsStatus.get("relrowsecurity"));
        System.out.println("relforcerowsecurity: " + rlsStatus.get("relforcerowsecurity"));

        // 3. Table owner
        String owner = jdbcTemplate.queryForObject(
                "SELECT pg_catalog.pg_get_userbyid(relowner) FROM pg_class WHERE relname = 'notification'",
                String.class);
        System.out.println("table owner: " + owner);

        // 4. Policies
        System.out.println("\n--- POLICIES ---");
        List<Map<String, Object>> policies = jdbcTemplate.queryForList(
                "SELECT policyname, permissive, roles, cmd, qual, with_check FROM pg_policies WHERE tablename = 'notification'");
        for (Map<String, Object> p : policies) {
            System.out.println("  policy: " + p.get("policyname") +
                    " | permissive: " + p.get("permissive") +
                    " | roles: " + p.get("roles") +
                    " | cmd: " + p.get("cmd") +
                    " | USING: " + p.get("qual") +
                    " | WITH CHECK: " + p.get("with_check"));
        }
        if (policies.isEmpty()) {
            System.out.println("  *** NO POLICIES FOUND ***");
        }

        // 5. Grants
        System.out.println("\n--- GRANTS ---");
        List<Map<String, Object>> grants = jdbcTemplate.queryForList(
                "SELECT grantee, privilege_type FROM information_schema.table_privileges " +
                "WHERE table_name = 'notification' AND table_schema = 'public'");
        for (Map<String, Object> g : grants) {
            System.out.println("  " + g.get("grantee") + " -> " + g.get("privilege_type"));
        }
        if (grants.isEmpty()) {
            System.out.println("  *** NO GRANTS FOUND ***");
        }

        // 6. Raw ACL
        String acl = jdbcTemplate.queryForObject(
                "SELECT relacl::text FROM pg_class WHERE relname = 'notification'", String.class);
        System.out.println("\nraw ACL: " + acl);

        // 7. GUC settings
        String dvAccess = jdbcTemplate.queryForObject(
                "SELECT current_setting('app.dv_access', true)", String.class);
        String userId = jdbcTemplate.queryForObject(
                "SELECT current_setting('app.current_user_id', true)", String.class);
        System.out.println("\napp.dv_access: " + dvAccess);
        System.out.println("app.current_user_id: " + userId);

        // 8. Check if fabt_app role exists and its attributes
        List<Map<String, Object>> roles = jdbcTemplate.queryForList(
                "SELECT rolname, rolsuper, rolbypassrls FROM pg_roles WHERE rolname IN ('fabt_app', '" + currentUser + "')");
        System.out.println("\n--- ROLES ---");
        for (Map<String, Object> r : roles) {
            System.out.println("  " + r.get("rolname") +
                    " | superuser: " + r.get("rolsuper") +
                    " | bypassrls: " + r.get("rolbypassrls"));
        }

        System.out.println("\n========== END DIAGNOSTIC ==========\n");
    }

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private TestAuthHelper authHelper;

    @Test
    void testDirectSpringDataInsert() {
        authHelper.setupTestTenant();
        var user = authHelper.setupAdminUser();

        System.out.println("\n========== SPRING DATA INSERT TEST ==========");
        try {
            Notification n = new Notification(
                    authHelper.getTestTenantId(), user.getId(),
                    "diagnostic.test", "INFO", "{}");
            System.out.println("payload field type: " + n.getPayload().getClass().getName());
            System.out.println("payload value: " + n.getPayload());

            Notification saved = notificationRepository.save(n);
            System.out.println("SUCCESS: saved with id=" + saved.getId());
        } catch (Exception e) {
            System.out.println("FAILED: " + e.getMessage());
            if (e.getCause() != null) {
                System.out.println("Caused by: " + e.getCause().getMessage());
            }
        }
        System.out.println("========== END INSERT TEST ==========\n");

        // Test raw INSERT without RETURNING (bypasses SELECT policy on RETURNING clause)
        System.out.println("\n========== RAW INSERT TEST (no RETURNING) ==========");
        try {
            jdbcTemplate.update(
                    "INSERT INTO notification (tenant_id, recipient_id, type, severity, payload) VALUES (?, ?, ?, ?, ?::jsonb)",
                    authHelper.getTestTenantId(), user.getId(), "diagnostic.raw", "INFO", "{}");
            System.out.println("SUCCESS: raw INSERT without RETURNING worked");
        } catch (Exception e) {
            System.out.println("FAILED: " + e.getMessage());
        }

        // Test INSERT with RETURNING (triggers SELECT policy check on RETURNING clause)
        System.out.println("\n--- INSERT with RETURNING * ---");
        try {
            jdbcTemplate.queryForMap(
                    "INSERT INTO notification (tenant_id, recipient_id, type, severity, payload) VALUES (?, ?, ?, ?, ?::jsonb) RETURNING *",
                    authHelper.getTestTenantId(), user.getId(), "diagnostic.returning", "INFO", "{}");
            System.out.println("SUCCESS: INSERT RETURNING * worked");
        } catch (Exception e) {
            System.out.println("FAILED: " + e.getMessage());
        }
        System.out.println("========== END RAW INSERT TEST ==========\n");
    }
}
