package db.migration;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import org.flywaydb.core.api.migration.Context;
import org.fabt.Application;
import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.shared.security.KeyPurpose;
import org.fabt.shared.security.SecretEncryptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V74 re-encrypt migration integration test (task 2.13.12 — design-a5-v74 §6).
 *
 * <p>Exercises the migration end-to-end against a Testcontainers Postgres:
 * seeds v0 ciphertext directly via JDBC, invokes {@code migrate(Context)},
 * asserts the migration produced v1 envelopes that round-trip through the
 * runtime {@link SecretEncryptionService#decryptForTenant} path. Cross-tenant
 * DEK separation, idempotency, and the audit row contract are all verified
 * against real Postgres state, not mocks.
 *
 * <p>Why the env-var-property fallback: V74 reads {@code FABT_ENCRYPTION_KEY}
 * from the process environment. Tests cannot set env vars via
 * {@code @DynamicPropertySource}, so V74 additionally falls back to
 * {@code fabt.encryption-key} system property — unreachable in prod (Phase 0
 * startup guard runs first) but lets the test drive the migration directly.
 *
 * <p>Setup dance: Flyway has already run V74 on the shared Testcontainers
 * schema before this test class starts. Each test seeds fresh v0 data via
 * JDBC + directly invokes {@code migrate()} again — V74's idempotency
 * (magic-byte skip) makes this safe. No need to DELETE FROM
 * flyway_schema_history.
 */
@DisplayName("V74 re-encrypt migration (task 2.13 — design-a5)")
@SpringBootTest(classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class V74ReencryptIntegrationTest extends BaseIntegrationTest {

    private static final String PLATFORM_KEY_B64 = "dGVzdC1vbmx5LXRvdHAtZW5jcnlwdGlvbi1rZXktMzI=";

    @Autowired private DataSource dataSource;
    @Autowired private SecretEncryptionService encryptionService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestAuthHelper authHelper;

    private final SecureRandom secureRandom = new SecureRandom();

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        tenantA = authHelper.getTestTenantId();
        tenantB = authHelper.setupSecondaryTenant("v74-tenant-b-"
                + UUID.randomUUID().toString().substring(0, 8)).getId();

        // Ensure V74 can find the key when we invoke it manually.
        System.setProperty("fabt.encryption-key", PLATFORM_KEY_B64);
    }

    @AfterEach
    void cleanup() {
        System.clearProperty("fabt.encryption-key");
    }

    // ------------------------------------------------------------------
    // T1 — happy-path round-trip
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T1: v0 TOTP → v1 round-trip decrypts to original plaintext")
    void t1_happyPath_totpRoundTrip() throws Exception {
        String plaintextSecret = "HIYA-RFC6238-BASE32-ABCDEFGHIJKL";
        UUID userId = seedUserWithV0Totp(tenantA, plaintextSecret);

        invokeV74();

        String stored = jdbc.queryForObject(
                "SELECT totp_secret_encrypted FROM app_user WHERE id = ?",
                String.class, userId);
        assertTrue(isV1(stored), "post-V74 stored value must be v1 envelope");
        assertEquals(plaintextSecret,
                encryptionService.decryptForTenant(tenantA, KeyPurpose.TOTP, stored),
                "v1 ciphertext must decrypt to original plaintext under tenant DEK");
    }

    // ------------------------------------------------------------------
    // T2 — idempotency on re-run
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T2: re-running V74 on v1 rows skips them (idempotent)")
    void t2_idempotentReRun() throws Exception {
        String plaintextSecret = "IDEMPOTENT-TOTP-SECRET-DO-NOT-CRY";
        UUID userId = seedUserWithV0Totp(tenantA, plaintextSecret);

        invokeV74();
        String firstPassStored = jdbc.queryForObject(
                "SELECT totp_secret_encrypted FROM app_user WHERE id = ?",
                String.class, userId);

        invokeV74();
        String secondPassStored = jdbc.queryForObject(
                "SELECT totp_secret_encrypted FROM app_user WHERE id = ?",
                String.class, userId);

        assertEquals(firstPassStored, secondPassStored,
                "v1 row must not be re-encrypted on a second V74 pass (idempotency via magic-byte skip)");
    }

    // ------------------------------------------------------------------
    // T3 — cross-tenant DEK separation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T3: v1 TOTP for tenantA cannot be decrypted under tenantB DEK")
    void t3_crossTenantDekSeparation() throws Exception {
        String plaintextSecret = "CROSS-TENANT-SEPARATION-TEST-ABCD";
        UUID userId = seedUserWithV0Totp(tenantA, plaintextSecret);

        invokeV74();

        String stored = jdbc.queryForObject(
                "SELECT totp_secret_encrypted FROM app_user WHERE id = ?",
                String.class, userId);

        // Attempt cross-tenant decrypt — SecretEncryptionService throws
        // CrossTenantCiphertextException because the kid resolves to tenantA,
        // not tenantB.
        assertThrows(org.fabt.shared.security.CrossTenantCiphertextException.class,
                () -> encryptionService.decryptForTenant(tenantB, KeyPurpose.TOTP, stored),
                "cross-tenant decrypt must reject via kid tenant mismatch");
    }

    // ------------------------------------------------------------------
    // T5 — empty table no-op (variant: tenants with no encryptable columns)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T5: no candidate rows → V74 completes cleanly + audit row fires with zero counts")
    void t5_emptyStateNoOp() throws Exception {
        // Clear any stray TOTP + webhook rows for the test tenants to simulate
        // the no-candidate case. Don't touch other tenants — shared container.
        jdbc.update("UPDATE app_user SET totp_secret_encrypted = NULL "
                + "WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        jdbc.update("DELETE FROM subscription WHERE tenant_id IN (?, ?)", tenantA, tenantB);

        long auditRowsBefore = countV74AuditRows();
        invokeV74();
        long auditRowsAfter = countV74AuditRows();

        assertEquals(auditRowsBefore + 1, auditRowsAfter,
                "V74 must write exactly one audit row even on no-op runs");
    }

    // ------------------------------------------------------------------
    // T9 — audit row contract
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T9: audit row carries expanded JSONB (duration_ms, KEK fingerprint, per-column counts)")
    void t9_auditRowShape() throws Exception {
        String plaintextSecret = "AUDIT-ROW-SHAPE-TOTP-SECRET-GOGO";
        seedUserWithV0Totp(tenantA, plaintextSecret);

        invokeV74();

        String detailsJson = jdbc.queryForObject(
                "SELECT details::text FROM audit_events "
                + "WHERE action = 'SYSTEM_MIGRATION_V74_REENCRYPT' "
                + "ORDER BY timestamp DESC LIMIT 1",
                String.class);

        // Postgres JSONB::text serializes with a space after each colon, so
        // match on the key name only.
        assertNotNull(detailsJson, "V74 must write an audit row");
        assertTrue(detailsJson.contains("\"migration\""), detailsJson);
        assertTrue(detailsJson.contains("\"V74\""), detailsJson);
        assertTrue(detailsJson.contains("\"duration_ms\""), detailsJson);
        assertTrue(detailsJson.contains("\"master_kek_fingerprint\""), detailsJson);
        assertTrue(detailsJson.contains("\"flyway_role\""), detailsJson);
        assertTrue(detailsJson.contains("\"totp_reencrypted\""), detailsJson);
        assertTrue(detailsJson.contains("\"webhook_reencrypted\""), detailsJson);
        assertTrue(detailsJson.contains("\"oauth2_reencrypted\""), detailsJson);
        assertTrue(detailsJson.contains("\"hmis_reencrypted\""), detailsJson);
        assertTrue(detailsJson.contains("\"totp_skipped_already_v1\""), detailsJson);
        assertTrue(detailsJson.contains("\"totp_skipped_null_tenant\""), detailsJson);
    }

    // ------------------------------------------------------------------
    // T11 — KeyPurpose.values() round-trip (catches future enum additions)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T11: every KeyPurpose round-trips through encryptForTenant/decryptForTenant")
    void t11_keyPurposeEnumCoverage() {
        String plaintext = "round-trip-" + UUID.randomUUID();
        for (KeyPurpose purpose : KeyPurpose.values()) {
            String ciphertext = encryptionService.encryptForTenant(tenantA, purpose, plaintext);
            assertTrue(isV1(ciphertext),
                    purpose + " encrypt must produce v1 envelope");
            String decrypted = encryptionService.decryptForTenant(tenantA, purpose, ciphertext);
            assertEquals(plaintext, decrypted,
                    purpose + " round-trip must preserve plaintext");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Seeds a pre-existing user with a v0-shape {@code totp_secret_encrypted}.
     * Uses the V59 envelope format: {@code [iv: 12][ct+tag: N+16]} Base64.
     * Returns the user id.
     */
    private UUID seedUserWithV0Totp(UUID tenantId, String plaintext) throws Exception {
        var user = authHelper.createUserInTenant(tenantId,
                "v74-test-" + UUID.randomUUID() + "@test.fabt.org",
                "V74 Test User", new String[]{"OUTREACH_WORKER"}, false);

        String v0Ciphertext = encryptV0(plaintext);
        jdbc.update("UPDATE app_user SET totp_secret_encrypted = ? WHERE id = ?",
                v0Ciphertext, user.getId());
        return user.getId();
    }

    private String encryptV0(String plaintext) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(PLATFORM_KEY_B64);
        SecretKey key = new SecretKeySpec(keyBytes, "AES");
        byte[] iv = new byte[12];
        secureRandom.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length);
        buf.put(iv);
        buf.put(ct);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    private boolean isV1(String stored) {
        try {
            byte[] decoded = Base64.getDecoder().decode(stored);
            return decoded.length >= 5
                    && decoded[0] == 0x46 && decoded[1] == 0x41
                    && decoded[2] == 0x42 && decoded[3] == 0x54
                    && decoded[4] == 0x01;
        } catch (IllegalArgumentException badBase64) {
            return false;
        }
    }

    private long countV74AuditRows() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'SYSTEM_MIGRATION_V74_REENCRYPT'",
                Long.class);
        return count == null ? 0L : count;
    }

    /**
     * Invokes V74's {@code migrate} method against a minimal Flyway Context
     * stub. We only need {@code getConnection()} — V74 doesn't touch
     * configuration. Using a live Connection borrowed from the Spring-managed
     * DataSource is intentional: V74 runs its UPDATEs in the same session and
     * Flyway's own transaction boundary logic is not replayed here (each test
     * sees V74's effects immediately; the @AfterEach tenant cleanup handles
     * isolation).
     *
     * <p><b>Test-realism caveat:</b> we set {@code autoCommit = true} so
     * individual UPDATEs are visible to subsequent assertions without an
     * explicit commit. This means V74's {@code SET LOCAL lock_timeout} +
     * {@code statement_timeout} (C-A5-N1) emit a Postgres NOTICE and become
     * no-ops — they require a transaction block to take effect. Real Flyway
     * wraps {@code migrate()} in a transaction, so prod behavior is correct.
     * Not a regression, just a test-stub limitation worth flagging so a
     * future reader doesn't chase a phantom bug.
     */
    private void invokeV74() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            var migration = new V74__reencrypt_secrets_under_per_tenant_deks();
            migration.migrate(new StubContext(conn));
        }
    }

    /** Minimal Flyway Context — just enough for {@code getConnection()}. */
    private static final class StubContext implements Context {
        private final Connection conn;

        StubContext(Connection conn) {
            this.conn = conn;
        }

        @Override
        public org.flywaydb.core.api.configuration.Configuration getConfiguration() {
            return null;
        }

        @Override
        public Connection getConnection() {
            return conn;
        }
    }
}
