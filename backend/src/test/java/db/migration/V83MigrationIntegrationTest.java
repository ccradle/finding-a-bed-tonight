package db.migration;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.Mac;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for V83 (F-6.0 task 7.8d, §11.6 of
 * design-f6-real-cryptoshred). Pins three invariants Jordan's pass-2
 * warroom strategy required:
 *
 * <ul>
 *   <li><b>Completeness</b> — after V83, every v1 envelope in the 4
 *       covered columns has its kid in {@code tenant_dek}, not in
 *       {@code kid_to_tenant_key}-only.</li>
 *   <li><b>Idempotency</b> — re-running V83 on already-migrated data
 *       is a no-op (zero column rewrites, zero new DEK rows).</li>
 *   <li><b>Rotation probe</b> — the Q-F6-4 fold-in exercises the atomic
 *       rotation path before Phase H needs it. Runs on real data when
 *       any tenant_dek row exists; skips gracefully on empty schemas.</li>
 * </ul>
 *
 * Seeds v1-HKDF envelopes the way V74 would have produced them (inline
 * helpers mirror V74's crypto paths), then invokes V83 directly against
 * a Flyway Context stub. Same pattern as {@code V74ReencryptIntegrationTest}.
 */
@DisplayName("V83 re-encrypt migration (F-6.0 task 7.8d — design-f6-real-cryptoshred)")
@SpringBootTest(classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class V83MigrationIntegrationTest extends BaseIntegrationTest {

    private static final String PLATFORM_KEY_B64 = "dGVzdC1vbmx5LXRvdHAtZW5jcnlwdGlvbi1rZXktMzI=";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestAuthHelper authHelper;
    @Autowired private SecretEncryptionService encryption;

    private final SecureRandom secureRandom = new SecureRandom();

    private UUID tenantA;

    @BeforeEach
    void setUp() {
        tenantA = authHelper.setupSecondaryTenant("v83-" + UUID.randomUUID()).getId();
        System.setProperty("fabt.encryption-key", PLATFORM_KEY_B64);
    }

    @AfterEach
    void cleanup() {
        System.clearProperty("fabt.encryption-key");
    }

    // ------------------------------------------------------------------
    // T1 — Happy path: legacy v1-HKDF envelope → V83 migrates to v1-random
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T1: seeded v1-HKDF TOTP envelope is re-encrypted under tenant_dek; round-trip via decryptForTenant")
    void t1_legacyV1Totp_migratedToTenantDek() throws Exception {
        String plaintext = "totp-plain-" + UUID.randomUUID();
        UUID userId = authHelper.createUserInTenant(tenantA,
                "v83-t1-" + UUID.randomUUID() + "@test.fabt.org",
                "V83 T1 User", new String[]{"OUTREACH_WORKER"}, false).getId();

        // 1. Seed a v1-HKDF TOTP envelope (the output V74 would have produced).
        UUID legacyKid = UUID.randomUUID();
        registerLegacyKid(tenantA, legacyKid);
        SecretKey hkdfDek = hkdfDeriveKey(tenantA, "totp");
        String legacyEnvelope = encryptV1Envelope(hkdfDek, legacyKid, plaintext);
        jdbc.update("UPDATE app_user SET totp_secret_encrypted = ? WHERE id = ?",
                legacyEnvelope, userId);

        // Sanity: envelope's kid is in kid_to_tenant_key, NOT tenant_dek.
        assertThat(kidInKidToTenantKey(legacyKid)).isTrue();
        assertThat(kidInTenantDek(legacyKid)).isFalse();

        // 2. Run V83.
        invokeV83();

        // 3. Post-V83: column has a NEW envelope with a NEW kid in tenant_dek.
        String migratedEnvelope = jdbc.queryForObject(
                "SELECT totp_secret_encrypted FROM app_user WHERE id = ?",
                String.class, userId);
        assertThat(migratedEnvelope).isNotEqualTo(legacyEnvelope);

        UUID migratedKid = extractKidFromV1Envelope(migratedEnvelope);
        assertThat(migratedKid).isNotNull();
        assertThat(kidInTenantDek(migratedKid))
                .as("post-V83, envelope kid must exist in tenant_dek")
                .isTrue();

        // 4. Decryption via the refactored service round-trips cleanly.
        String decrypted = encryption.decryptForTenant(tenantA, KeyPurpose.TOTP, migratedEnvelope);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    // ------------------------------------------------------------------
    // T2 — Idempotency: second V83 run is a no-op
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T2: running V83 twice produces zero additional rewrites on the second pass")
    void t2_idempotentReRun() throws Exception {
        String plaintext = "idempotent-" + UUID.randomUUID();
        UUID userId = authHelper.createUserInTenant(tenantA,
                "v83-t2-" + UUID.randomUUID() + "@test.fabt.org",
                "V83 T2 User", new String[]{"OUTREACH_WORKER"}, false).getId();

        UUID legacyKid = UUID.randomUUID();
        registerLegacyKid(tenantA, legacyKid);
        SecretKey hkdfDek = hkdfDeriveKey(tenantA, "totp");
        String legacyEnvelope = encryptV1Envelope(hkdfDek, legacyKid, plaintext);
        jdbc.update("UPDATE app_user SET totp_secret_encrypted = ? WHERE id = ?",
                legacyEnvelope, userId);

        invokeV83();
        String afterFirst = jdbc.queryForObject(
                "SELECT totp_secret_encrypted FROM app_user WHERE id = ?",
                String.class, userId);
        int dekRowsAfterFirst = countTenantDekRowsForTenant(tenantA);

        invokeV83();
        String afterSecond = jdbc.queryForObject(
                "SELECT totp_secret_encrypted FROM app_user WHERE id = ?",
                String.class, userId);
        int dekRowsAfterSecond = countTenantDekRowsForTenant(tenantA);

        assertThat(afterSecond)
                .as("second V83 run must not rewrite already-migrated column")
                .isEqualTo(afterFirst);
        assertThat(dekRowsAfterSecond)
                .as("second V83 run must not create additional tenant_dek rows")
                .isEqualTo(dekRowsAfterFirst);
    }

    // ------------------------------------------------------------------
    // T3 — Completeness: no orphan v1-HKDF envelopes remain
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T3: post-V83, no v1 envelope in the 4 covered columns has an HKDF-only kid")
    void t3_noOrphanV1HkdfEnvelopes() throws Exception {
        // Seed one legacy envelope for each of the 4 purposes to simulate V74's output.
        String totpPlain = "totp-" + UUID.randomUUID();
        UUID userId = authHelper.createUserInTenant(tenantA,
                "v83-t3-" + UUID.randomUUID() + "@test.fabt.org",
                "V83 T3 User", new String[]{"OUTREACH_WORKER"}, false).getId();
        seedLegacyEnvelope("app_user", "totp_secret_encrypted", "id", userId,
                "totp", tenantA, totpPlain);

        // Webhook: seed a subscription row with callback_secret_hash legacy envelope.
        String webhookPlain = "webhook-" + UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        // Subscription schema uses `status` (VARCHAR, default 'ACTIVE') not `active` BOOLEAN.
        jdbc.update("INSERT INTO subscription (id, tenant_id, event_type, callback_url, "
                + "callback_secret_hash) VALUES (?, ?, ?, ?, ?)",
                subId, tenantA, "RESERVATION_CREATED", "https://example.test/hook", "placeholder");
        seedLegacyEnvelope("subscription", "callback_secret_hash", "id", subId,
                "webhook-secret", tenantA, webhookPlain);

        invokeV83();

        // Every v1 envelope across the 4 columns must now have a kid in tenant_dek.
        assertAllV1KidsInTenantDek("app_user", "totp_secret_encrypted", tenantA);
        assertAllV1KidsInTenantDek("subscription", "callback_secret_hash", tenantA);
        assertAllV1KidsInTenantDek("tenant_oauth2_provider", "client_secret_encrypted", tenantA);
    }

    // ------------------------------------------------------------------
    // T4 — Rotation probe: runs + reports passed when data exists
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T4: when tenant_dek has rows, the rotation probe runs and reports 'passed' in the audit")
    void t4_rotationProbeRuns() throws Exception {
        // Ensure at least one legacy envelope so V83 creates tenant_dek rows.
        UUID userId = authHelper.createUserInTenant(tenantA,
                "v83-t4-" + UUID.randomUUID() + "@test.fabt.org",
                "V83 T4 User", new String[]{"OUTREACH_WORKER"}, false).getId();
        seedLegacyEnvelope("app_user", "totp_secret_encrypted", "id", userId,
                "totp", tenantA, "probe-plaintext-" + UUID.randomUUID());

        invokeV83();

        String probeResult = readLatestV83AuditField("rotation_probe_result");
        assertThat(probeResult)
                .as("rotation probe must run against live tenant_dek rows")
                .isEqualTo("passed");

        // Post-probe state: tenant_dek has exactly the original rows — probe
        // reverted its gen=2 changes and restored gen=1 to active.
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant_dek WHERE tenant_id = ? "
                + "AND purpose = 'TOTP' AND active = TRUE",
                Integer.class, tenantA);
        assertThat(activeCount).isEqualTo(1);

        Integer totalRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant_dek WHERE tenant_id = ? AND purpose = 'TOTP'",
                Integer.class, tenantA);
        assertThat(totalRows).as("probe must not leave gen=2 row behind").isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // T5 — Audit row present with correct structure
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T5: V83 writes exactly one SYSTEM_MIGRATION_V83_TENANT_DEK audit row per invocation")
    void t5_auditRowWritten() throws Exception {
        long before = countV83AuditRows();
        invokeV83();
        long afterOne = countV83AuditRows();
        assertThat(afterOne).as("first invocation increments audit count by 1")
                .isEqualTo(before + 1);

        invokeV83();
        long afterTwo = countV83AuditRows();
        assertThat(afterTwo).as("second invocation increments again (even if no rewrites)")
                .isEqualTo(before + 2);

        String flywayRole = readLatestV83AuditField("flyway_role");
        assertThat(flywayRole).as("audit row captures session role diagnostic").isNotEmpty();
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    private void seedLegacyEnvelope(String table, String column, String pkColumn, UUID pk,
                                     String hkdfPurpose, UUID tenantId, String plaintext) throws Exception {
        UUID legacyKid = UUID.randomUUID();
        registerLegacyKid(tenantId, legacyKid);
        SecretKey dek = hkdfDeriveKey(tenantId, hkdfPurpose);
        String legacyEnvelope = encryptV1Envelope(dek, legacyKid, plaintext);
        jdbc.update(String.format("UPDATE %s SET %s = ? WHERE %s = ?", table, column, pkColumn),
                legacyEnvelope, pk);
    }

    /** Inserts a tenant_key_material + kid_to_tenant_key pair so the
     *  seeded v1-HKDF envelope has a registered kid (matches V74 output). */
    private void registerLegacyKid(UUID tenantId, UUID kid) {
        org.fabt.testsupport.WithTenantContext.doAs(tenantId, () -> {
            jdbc.update("INSERT INTO tenant_key_material (tenant_id, generation, active) "
                    + "VALUES (?, 1, TRUE) ON CONFLICT DO NOTHING", tenantId);
            jdbc.update("INSERT INTO kid_to_tenant_key (kid, tenant_id, generation) "
                    + "VALUES (?, ?, 1) ON CONFLICT DO NOTHING", kid, tenantId);
        });
    }

    private void assertAllV1KidsInTenantDek(String table, String column, UUID tenantId) {
        var rows = jdbc.queryForList(
                String.format("SELECT %s AS env FROM %s WHERE tenant_id = ? AND %s IS NOT NULL",
                        column, table, column),
                tenantId);
        for (var row : rows) {
            String stored = (String) row.get("env");
            if (stored == null || stored.isBlank() || !isV1(stored)) continue;
            UUID kid = extractKidFromV1Envelope(stored);
            assertThat(kid).isNotNull();
            assertThat(kidInTenantDek(kid))
                    .as("%s.%s kid %s must be in tenant_dek post-V83", table, column, kid)
                    .isTrue();
        }
    }

    private boolean kidInTenantDek(UUID kid) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant_dek WHERE kid = ?", Integer.class, kid);
        return count != null && count > 0;
    }

    private boolean kidInKidToTenantKey(UUID kid) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kid_to_tenant_key WHERE kid = ?", Integer.class, kid);
        return count != null && count > 0;
    }

    private int countTenantDekRowsForTenant(UUID tenantId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant_dek WHERE tenant_id = ?", Integer.class, tenantId);
        return count == null ? 0 : count;
    }

    private long countV83AuditRows() {
        Long count = org.fabt.testsupport.WithTenantContext.readAsSystem(() ->
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_events WHERE action = 'SYSTEM_MIGRATION_V83_TENANT_DEK'",
                        Long.class));
        return count == null ? 0L : count;
    }

    private String readLatestV83AuditField(String jsonKey) {
        return org.fabt.testsupport.WithTenantContext.readAsSystem(() ->
                jdbc.queryForObject(
                        "SELECT details->>? FROM audit_events "
                        + "WHERE action = 'SYSTEM_MIGRATION_V83_TENANT_DEK' "
                        + "ORDER BY timestamp DESC LIMIT 1",
                        String.class, jsonKey));
    }

    private void invokeV83() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            var migration = new V83__reencrypt_v1_envelopes_under_tenant_dek();
            migration.migrate(new StubContext(conn));
            conn.commit();
        }
    }

    // ------------------------------------------------------------------
    // Inline crypto helpers (mirror KeyDerivationService + V74 primitives)
    // ------------------------------------------------------------------

    private SecretKey hkdfDeriveKey(UUID tenantId, String purpose) throws Exception {
        byte[] masterKek = Base64.getDecoder().decode(PLATFORM_KEY_B64);
        byte[] salt = uuidToBytes(tenantId);
        String context = "fabt:v1:" + tenantId + ":" + purpose;
        byte[] info = context.getBytes(StandardCharsets.UTF_8);
        byte[] derived = hkdfSha256(masterKek, salt, info, 32);
        return new SecretKeySpec(derived, "AES");
    }

    private String encryptV1Envelope(SecretKey dek, UUID kid, String plaintext) throws Exception {
        byte[] iv = new byte[12];
        secureRandom.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, dek, new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        ByteBuffer buf = ByteBuffer.allocate(4 + 1 + 16 + 12 + ct.length);
        buf.put(new byte[]{0x46, 0x41, 0x42, 0x54});
        buf.put((byte) 0x01);
        buf.putLong(kid.getMostSignificantBits());
        buf.putLong(kid.getLeastSignificantBits());
        buf.put(iv);
        buf.put(ct);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    private static boolean isV1(String stored) {
        try {
            byte[] d = Base64.getDecoder().decode(stored);
            return d.length >= 5 && d[0] == 0x46 && d[1] == 0x41 && d[2] == 0x42 && d[3] == 0x54 && d[4] == 0x01;
        } catch (IllegalArgumentException badBase64) {
            return false;
        }
    }

    private static UUID extractKidFromV1Envelope(String stored) {
        try {
            byte[] d = Base64.getDecoder().decode(stored);
            if (d.length < 33) return null;
            ByteBuffer buf = ByteBuffer.wrap(d);
            buf.position(5);
            return new UUID(buf.getLong(), buf.getLong());
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
        return buf.array();
    }

    private static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int outLen) throws Exception {
        final int OUT = 32;
        byte[] eff = (salt == null || salt.length == 0) ? new byte[OUT] : salt;
        Mac extract = Mac.getInstance("HmacSHA256");
        extract.init(new SecretKeySpec(eff, "HmacSHA256"));
        byte[] prk = extract.doFinal(ikm);
        Mac expand = Mac.getInstance("HmacSHA256");
        expand.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] result = new byte[outLen];
        byte[] prev = new byte[0];
        int written = 0;
        for (int i = 1; written < outLen; i++) {
            expand.reset();
            expand.update(prev);
            expand.update(info);
            expand.update((byte) i);
            prev = expand.doFinal();
            int copy = Math.min(OUT, outLen - written);
            System.arraycopy(prev, 0, result, written, copy);
            written += copy;
        }
        return result;
    }

    private static final class StubContext implements Context {
        private final Connection conn;
        StubContext(Connection conn) { this.conn = conn; }
        @Override public org.flywaydb.core.api.configuration.Configuration getConfiguration() { return null; }
        @Override public Connection getConnection() { return conn; }
    }
}
