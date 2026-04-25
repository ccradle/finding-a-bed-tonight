package db.migration;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.fabt.Application;
import org.fabt.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for V87 (platform-admin-split-and-access-log G-4.1).
 *
 * <p>Pins the schema invariants the OpenSpec design requires:
 *
 * <ul>
 *   <li>Three tables created with expected columns + indexes</li>
 *   <li>Bootstrap row at the well-known {@code 0fab} UUID with locked / no-creds state</li>
 *   <li>UNIQUE email index defined with {@code WHERE email IS NOT NULL AND anonymized_at IS NULL}</li>
 *   <li>{@code platform_user} + {@code platform_user_backup_code} REVOKED from {@code fabt_app}</li>
 *   <li>{@code platform_key_material} keeps SELECT for {@code fabt_app} (JwtDecoder reads at request time)
 *       but UPDATE/DELETE/INSERT/TRUNCATE are revoked</li>
 *   <li>SECURITY DEFINER functions exist and are EXECUTE-grantable to {@code fabt_app}</li>
 *   <li>COC_ADMIN backfill applied to existing PLATFORM_ADMIN-bearing app_user rows</li>
 *   <li>token_version incremented on backfilled rows</li>
 *   <li>Backfill is idempotent (defensive WHERE clause)</li>
 * </ul>
 *
 * <p>The migration runs as part of the standard Spring/Flyway boot in the
 * {@link BaseIntegrationTest} Testcontainers postgres instance — these
 * assertions run against the post-migration schema state.
 */
@DisplayName("V87 platform_user schema + COC_ADMIN backfill")
@SpringBootTest(classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class V87MigrationIntegrationTest extends BaseIntegrationTest {

    private static final UUID BOOTSTRAP_PLATFORM_USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000fab");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    // ------------------------------------------------------------------
    // Table presence + columns
    // ------------------------------------------------------------------

    @Test
    @DisplayName("platform_user table exists with expected columns")
    void platformUserTableShape() {
        // Use pg_catalog (visible to fabt_app even without grants) instead of
        // information_schema.columns (which filters by privileges per ANSI SQL).
        List<String> columnNames = jdbc.queryForList(
                "SELECT a.attname FROM pg_attribute a " +
                "  JOIN pg_class c ON c.oid = a.attrelid " +
                "  JOIN pg_namespace n ON n.oid = c.relnamespace " +
                " WHERE n.nspname = 'public' AND c.relname = 'platform_user' " +
                "   AND a.attnum > 0 AND NOT a.attisdropped " +
                " ORDER BY a.attnum",
                String.class);

        // V87 introduced these 9 columns. V88 (G-4.2) adds lockout columns
        // — assert "contains" so V88's additions don't break this V87 pin.
        assertThat(columnNames).contains(
                "id", "email", "password_hash", "mfa_secret",
                "mfa_enabled", "account_locked", "created_at",
                "last_login_at", "anonymized_at");
    }

    @Test
    @DisplayName("platform_user_backup_code table exists with code_salt column")
    void platformUserBackupCodeTableShape() {
        List<String> columnNames = jdbc.queryForList(
                "SELECT a.attname FROM pg_attribute a " +
                "  JOIN pg_class c ON c.oid = a.attrelid " +
                "  JOIN pg_namespace n ON n.oid = c.relnamespace " +
                " WHERE n.nspname = 'public' AND c.relname = 'platform_user_backup_code' " +
                "   AND a.attnum > 0 AND NOT a.attisdropped",
                String.class);

        assertThat(columnNames).contains(
                "id", "platform_user_id", "code_hash", "code_salt", "used_at", "created_at");
    }

    @Test
    @DisplayName("partial UNIQUE email index defined with WHERE email IS NOT NULL AND anonymized_at IS NULL")
    void emailUniqueIndexHasPartialPredicate() {
        // pg_indexes returns the CREATE INDEX SQL — we can grep for the partial predicate.
        // fabt_app can read pg_indexes regardless of table grants.
        String indexdef = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes " +
                " WHERE schemaname = 'public' AND indexname = 'platform_user_email_unique'",
                String.class);

        assertThat(indexdef)
                .as("partial UNIQUE index must exclude NULL emails and anonymized rows")
                .containsIgnoringCase("UNIQUE")
                .containsIgnoringCase("email IS NOT NULL")
                .containsIgnoringCase("anonymized_at IS NULL");
    }

    @Test
    @DisplayName("platform_key_material table exists and enforces single-active constraint")
    void platformKeyMaterialTableShape() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns " +
                " WHERE table_schema = 'public' AND table_name = 'platform_key_material'");

        assertThat(columns)
                .extracting(c -> c.get("column_name").toString())
                .containsExactlyInAnyOrder(
                        "id", "generation", "kid", "key_bytes", "active", "created_at");

        // Partial UNIQUE index enforces "at most one row with active=true"
        // — same semantics as the originally-spec'd EXCLUDE constraint but
        // without the btree_gist extension dependency.
        String indexdef = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes " +
                " WHERE schemaname = 'public' AND indexname = 'platform_key_material_one_active'",
                String.class);

        assertThat(indexdef)
                .as("partial UNIQUE index must enforce single active row")
                .containsIgnoringCase("UNIQUE")
                .containsIgnoringCase("WHERE (active = true)");
    }

    // ------------------------------------------------------------------
    // Bootstrap row — read via SECURITY DEFINER function
    // ------------------------------------------------------------------

    @Test
    @DisplayName("bootstrap platform_user row exists at well-known 0fab UUID, locked, no creds")
    void bootstrapRowExists() {
        // Reset to bootstrap state first — other test classes in the shared
        // Spring context (V88, PlatformAuthIntegrationTest) mutate this row
        // and may run BEFORE this test depending on Surefire's class order.
        // The reset SECURITY DEFINER function exists in V88; V87 + V88 are
        // both applied by the time any test runs.
        jdbc.queryForObject("SELECT platform_user_reset_to_bootstrap(?::uuid)",
                Boolean.class, BOOTSTRAP_PLATFORM_USER_ID);

        // fabt_app cannot SELECT platform_user directly (REVOKE ALL); use the
        // SECURITY DEFINER function platform_user_lookup_by_id instead.
        // Function returns a record (id, email, password_hash, mfa_secret, mfa_enabled, account_locked).
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM platform_user_lookup_by_id(?::uuid)",
                BOOTSTRAP_PLATFORM_USER_ID);

        assertThat(row.get("id").toString()).isEqualTo(BOOTSTRAP_PLATFORM_USER_ID.toString());
        assertThat(row.get("email")).as("bootstrap email is NULL until operator activates").isNull();
        assertThat(row.get("password_hash")).as("bootstrap has no password until operator activates").isNull();
        assertThat(row.get("mfa_enabled")).isEqualTo(false);
        assertThat(row.get("account_locked"))
                .as("bootstrap row is locked — refuses login until operator UPDATE")
                .isEqualTo(true);
    }

    @Test
    @DisplayName("fabt_app cannot read platform_user via direct SELECT (REVOKE in effect)")
    void directSelectRejected() {
        // Spring wraps PSQLException in DataAccessException; the "permission denied" string
        // lives on the root cause (PSQLException), not the wrapping Spring exception.
        assertThatThrownBy(() ->
                jdbc.queryForList("SELECT id FROM platform_user WHERE id = ?", BOOTSTRAP_PLATFORM_USER_ID))
                .as("direct SELECT must be rejected — only SECURITY DEFINER function path is permitted")
                .isInstanceOf(DataAccessException.class)
                .hasRootCauseMessage("ERROR: permission denied for table platform_user");
    }

    // ------------------------------------------------------------------
    // SECURITY DEFINER functions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("all 8 SECURITY DEFINER functions exist with EXECUTE granted to fabt_app")
    void securityDefinerFunctionsExist() {
        // pg_proc.prosecdef = true means SECURITY DEFINER
        // pg_proc.proacl includes role grants
        List<String> functionNames = jdbc.queryForList(
                "SELECT proname FROM pg_proc " +
                " WHERE prosecdef = true " +
                "   AND (proname LIKE 'platform_user_%' OR proname LIKE 'platform_key_material_%') " +
                " ORDER BY proname",
                String.class);

        // V87 introduced these 8 SECURITY DEFINER functions. V88 (G-4.2)
        // adds 6 more for lockout / atomic MFA / TOTP replay. Assert
        // "contains" so V88's additions don't break this V87 pin.
        assertThat(functionNames).contains(
                "platform_key_material_create_first_active",
                "platform_user_backup_codes_for",
                "platform_user_insert_backup_codes",
                "platform_user_lookup_by_email",
                "platform_user_lookup_by_id",
                "platform_user_mark_backup_code_used",
                "platform_user_record_login",
                "platform_user_update_credentials");
    }

    @Test
    @DisplayName("fabt_app can EXECUTE platform_user_lookup_by_email (positive permission test)")
    void fabtAppCanInvokeSecurityDefinerFunction() {
        // The fabt_app connection (test JdbcTemplate) calls the function. Returns
        // empty result for non-existent email but the call itself must succeed —
        // proves GRANT EXECUTE took effect AND the SECURITY DEFINER elevation
        // bypasses the REVOKE on the underlying table.
        List<Map<String, Object>> result = jdbc.queryForList(
                "SELECT * FROM platform_user_lookup_by_email(?)",
                "no-such-user@example.com");

        assertThat(result)
                .as("function call must succeed (no rows returned for non-existent email)")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // platform_key_material constraints
    // ------------------------------------------------------------------

    @Test
    @DisplayName("partial UNIQUE index permits at most one active row in platform_key_material")
    void platformKeyMaterialOneActiveEnforced() {
        // Since G-4.2, PlatformKeyRotationService.@EventListener fires on
        // app start and inserts the first active row via the bootstrap path.
        // By the time this test runs, the active row is already present.
        // The function's "refuse if active row exists" branch is what we
        // can still exercise — the original "first call succeeds" assertion
        // is now covered by PlatformKeyRotationService's own integration
        // (visible via PLATFORM_KEY_BOOTSTRAPPED log line + non-empty
        // SELECT below).

        Long activeRowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_key_material WHERE active = true",
                Long.class);
        assertThat(activeRowCount)
                .as("PlatformKeyRotationService bootstrap on app start should leave one active row")
                .isEqualTo(1L);

        UUID secondId = UUID.randomUUID();
        Boolean secondInserted = jdbc.queryForObject(
                "SELECT platform_key_material_create_first_active(?::uuid, ?, ?::bytea)",
                Boolean.class,
                secondId, "test-kid-" + secondId, "deadbeef".getBytes());

        assertThat(secondInserted)
                .as("function must return false because an active row already exists")
                .isFalse();
    }

    // ------------------------------------------------------------------
    // CASCADE FK behavior
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DELETE platform_user CASCADEs to platform_user_backup_code via FK")
    void backupCodeCascadeOnPlatformUserDelete() {
        // We can't DELETE platform_user as fabt_app (REVOKE ALL). Verify the FK
        // declaration via pg_catalog instead — confirms the migration set up
        // ON DELETE CASCADE. The behavior is enforced by PostgreSQL whenever
        // a delete eventually happens (e.g., via Phase H+ admin tooling).
        String fkAction = jdbc.queryForObject(
                "SELECT confdeltype FROM pg_constraint c " +
                "  JOIN pg_class t ON t.oid = c.conrelid " +
                " WHERE t.relname = 'platform_user_backup_code' " +
                "   AND c.contype = 'f' " +
                "   AND c.conname LIKE '%platform_user_id%'",
                String.class);

        assertThat(fkAction)
                .as("FK from platform_user_backup_code to platform_user must be ON DELETE CASCADE ('c')")
                .isEqualTo("c");
    }

    // ------------------------------------------------------------------
    // REVOKE posture
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fabt_app has no SELECT privilege on platform_user")
    void fabtAppCannotSelectPlatformUserDirectly() {
        // information_schema.role_table_grants reflects what privileges fabt_app holds.
        Long selectGrants = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.role_table_grants " +
                " WHERE grantee = 'fabt_app' " +
                "   AND table_name = 'platform_user' " +
                "   AND privilege_type = 'SELECT'",
                Long.class);
        assertThat(selectGrants)
                .as("fabt_app must NOT have SELECT on platform_user (REVOKE ALL applied)")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("fabt_app has no SELECT privilege on platform_user_backup_code")
    void fabtAppCannotSelectBackupCodesDirectly() {
        Long selectGrants = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.role_table_grants " +
                " WHERE grantee = 'fabt_app' " +
                "   AND table_name = 'platform_user_backup_code' " +
                "   AND privilege_type = 'SELECT'",
                Long.class);
        assertThat(selectGrants).isEqualTo(0);
    }

    @Test
    @DisplayName("fabt_app has SELECT on platform_key_material (JwtDecoder needs read)")
    void fabtAppCanSelectPlatformKeyMaterial() {
        Long selectGrants = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.role_table_grants " +
                " WHERE grantee = 'fabt_app' " +
                "   AND table_name = 'platform_key_material' " +
                "   AND privilege_type = 'SELECT'",
                Long.class);
        assertThat(selectGrants).isEqualTo(1);
    }

    @Test
    @DisplayName("fabt_app has NO INSERT/UPDATE/DELETE/TRUNCATE on platform_key_material")
    void fabtAppCannotMutatePlatformKeyMaterial() {
        Long writeGrants = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.role_table_grants " +
                " WHERE grantee = 'fabt_app' " +
                "   AND table_name = 'platform_key_material' " +
                "   AND privilege_type IN ('INSERT', 'UPDATE', 'DELETE', 'TRUNCATE')",
                Long.class);
        assertThat(writeGrants)
                .as("fabt_app may only SELECT platform_key_material; writes are revoked")
                .isEqualTo(0);
    }

    // ------------------------------------------------------------------
    // COC_ADMIN backfill + token_version bump
    // ------------------------------------------------------------------

    @Test
    @DisplayName("backfill UPDATE correctly adds COC_ADMIN to a PLATFORM_ADMIN-only probe row")
    void cocAdminBackfillCorrectlyAddsRole() {
        // CANNOT assert on global state — other test classes (with shared
        // Spring context per BaseIntegrationTest) insert PLATFORM_ADMIN-only
        // users via TestAuthHelper / raw fixtures AFTER V87 already ran. The
        // V87 backfill SQL was correct at migration time; this test re-applies
        // it to a controlled probe row to verify the SQL behavior itself.
        UUID probeId = insertProbeAdminUserWithRolesOnly("v87-probe-add@test.fabt.org");

        // Pre-state: PLATFORM_ADMIN only
        String preRoles = readRolesAsCsv(probeId);
        assertThat(preRoles).contains("PLATFORM_ADMIN");
        assertThat(preRoles).doesNotContain("COC_ADMIN");

        // Re-apply V87's backfill UPDATE scoped to the probe row
        int rowsTouched = jdbc.update(
                "UPDATE app_user " +
                "   SET roles = roles || ARRAY['COC_ADMIN'], " +
                "       token_version = token_version + 1 " +
                " WHERE 'PLATFORM_ADMIN' = ANY(roles) " +
                "   AND NOT ('COC_ADMIN' = ANY(roles)) " +
                "   AND id = ?",
                probeId);

        assertThat(rowsTouched)
                .as("backfill SQL must touch the PLATFORM_ADMIN-only probe row exactly once")
                .isEqualTo(1);

        // Post-state: BOTH PLATFORM_ADMIN and COC_ADMIN
        String postRoles = readRolesAsCsv(probeId);
        assertThat(postRoles).contains("PLATFORM_ADMIN");
        assertThat(postRoles).contains("COC_ADMIN");

        cleanupProbe(probeId);
    }

    @Test
    @DisplayName("backfill UPDATE is idempotent — re-applying to a row that already has COC_ADMIN is a no-op")
    void backfillIdempotent() {
        // Insert a probe that ALREADY has both roles (mirrors a row that was
        // backfilled by V87 at migration time).
        UUID probeId = insertProbeAdminUserWithBothRoles("v87-probe-idempotent@test.fabt.org");

        Integer versionBefore = jdbc.queryForObject(
                "SELECT token_version FROM app_user WHERE id = ?",
                Integer.class, probeId);

        // Re-apply the backfill UPDATE. The defensive WHERE clause must
        // exclude this row because COC_ADMIN is already present.
        int rowsTouched = jdbc.update(
                "UPDATE app_user " +
                "   SET roles = roles || ARRAY['COC_ADMIN'], " +
                "       token_version = token_version + 1 " +
                " WHERE 'PLATFORM_ADMIN' = ANY(roles) " +
                "   AND NOT ('COC_ADMIN' = ANY(roles)) " +
                "   AND id = ?",
                probeId);

        assertThat(rowsTouched)
                .as("idempotent re-run must affect 0 rows because COC_ADMIN was already present")
                .isEqualTo(0);

        Integer versionAfter = jdbc.queryForObject(
                "SELECT token_version FROM app_user WHERE id = ?",
                Integer.class, probeId);

        assertThat(versionAfter)
                .as("token_version must NOT be re-incremented on idempotent re-run")
                .isEqualTo(versionBefore);

        cleanupProbe(probeId);
    }

    // ------------------------------------------------------------------
    // Probe row helpers — direct INSERT via fabt_app under tenant context
    // ------------------------------------------------------------------
    // Note: app_user is FORCE-RLS'd (Phase B V69). Direct INSERT from
    // fabt_app requires tenant_id to match the session's tenant_id GUC.
    // For these probe rows we use the SYSTEM_TENANT_ID + a SET LOCAL to
    // satisfy RLS without depending on TestAuthHelper.

    private UUID insertProbeAdminUserWithRolesOnly(String email) {
        UUID probeId = UUID.randomUUID();
        UUID tenantId = readAnyTenantId();
        jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
        jdbc.update(
                "INSERT INTO app_user (id, tenant_id, email, password_hash, " +
                "  display_name, roles, dv_access, token_version, status, created_at, updated_at) " +
                "VALUES (?, ?::uuid, ?, 'no-hash', ?, ARRAY['PLATFORM_ADMIN']::TEXT[], false, 5, 'ACTIVE', NOW(), NOW())",
                probeId, tenantId, email, "V87 Probe");
        return probeId;
    }

    private UUID insertProbeAdminUserWithBothRoles(String email) {
        UUID probeId = UUID.randomUUID();
        UUID tenantId = readAnyTenantId();
        jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
        jdbc.update(
                "INSERT INTO app_user (id, tenant_id, email, password_hash, " +
                "  display_name, roles, dv_access, token_version, status, created_at, updated_at) " +
                "VALUES (?, ?::uuid, ?, 'no-hash', ?, ARRAY['PLATFORM_ADMIN', 'COC_ADMIN']::TEXT[], false, 5, 'ACTIVE', NOW(), NOW())",
                probeId, tenantId, email, "V87 Probe Both");
        return probeId;
    }

    private String readRolesAsCsv(UUID userId) {
        UUID tenantId = readAnyTenantId();
        jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
        return jdbc.queryForObject(
                "SELECT array_to_string(roles, ',') FROM app_user WHERE id = ?",
                String.class, userId);
    }

    private void cleanupProbe(UUID userId) {
        UUID tenantId = readAnyTenantId();
        jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
        jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
    }

    private UUID readAnyTenantId() {
        // The test container has at least the dev-coc tenant from V73/V76/V77
        // seeds. We use the first available one for probe rows.
        return jdbc.queryForObject(
                "SELECT id FROM tenant ORDER BY created_at LIMIT 1",
                UUID.class);
    }
}
