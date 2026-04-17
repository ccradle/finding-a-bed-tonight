package db.migration;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Re-encrypt plaintext OAuth2 client secrets and HMIS API keys that were
 * written before the encrypt-on-save wiring landed in
 * {@code TenantOAuth2ProviderService} and {@code HmisConfigService}.
 *
 * <p>Idempotent: each candidate value is tested with {@link #looksLikeCiphertext}
 * (Base64 decode + GCM shape check). A value that already decrypts cleanly is
 * left alone; only genuinely plaintext values are encrypted and written back.
 * Re-running the migration after a partial failure is safe.
 *
 * <p>Skips silently when {@code FABT_ENCRYPTION_KEY} is unset (dev / CI
 * environments without encryption configured). The runtime services already
 * tolerate plaintext-storage in that mode.
 *
 * <p>Class lives in the {@code db.migration} package because Flyway scans for
 * Java migrations on its configured location ({@code classpath:db/migration})
 * with a matching package name. Do not move it under {@code org.fabt.*}; the
 * scan will silently miss it.
 *
 * <p>Cipher parameters intentionally mirror
 * {@link org.fabt.shared.security.SecretEncryptionService} (AES-256-GCM,
 * 12-byte IV, 128-bit tag). Keep them in sync if the service changes; the
 * companion test {@code V59ReencryptPlaintextCredentialsTest} guards the
 * round-trip.
 *
 * <p>Part of multi-tenant-production-readiness Phase 0 (task 1.6).
 */
public class V59__reencrypt_plaintext_credentials extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(
            V59__reencrypt_plaintext_credentials.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public void migrate(Context context) throws Exception {
        String base64Key = System.getenv("FABT_ENCRYPTION_KEY");
        if (base64Key == null || base64Key.isBlank()) {
            base64Key = System.getenv("FABT_TOTP_ENCRYPTION_KEY");
        }
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("V59: FABT_ENCRYPTION_KEY not set — skipping re-encryption. "
                    + "This is expected in environments that store credentials in plaintext.");
            return;
        }

        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "FABT_ENCRYPTION_KEY must be 32 bytes (256 bits). Got: " + keyBytes.length);
        }
        SecretKey key = new SecretKeySpec(keyBytes, "AES");

        Connection conn = context.getConnection();

        int oauthReencrypted = reencryptOAuth2ClientSecrets(conn, key);
        int hmisReencrypted = reencryptHmisApiKeys(conn, key);

        recordAuditEvent(conn, oauthReencrypted, hmisReencrypted);

        log.info("V59: re-encrypted {} OAuth2 client secret(s) and {} HMIS API key(s)",
                oauthReencrypted, hmisReencrypted);
    }

    /**
     * Writes a single platform-level row to {@code audit_events} so the V59
     * system migration is visible in the audit trail (Marcus / G1 hash-chain
     * intent). {@code actor_user_id} and {@code tenant_id} are left NULL
     * because this is a system-initiated, platform-wide operation. Same
     * transaction as the UPDATEs — commits or rolls back atomically.
     */
    private void recordAuditEvent(Connection conn, int oauthReencrypted, int hmisReencrypted)
            throws Exception {
        String details = String.format(
                "{\"migration\":\"V59\",\"oauth2_reencrypted\":%d,\"hmis_reencrypted\":%d}",
                oauthReencrypted, hmisReencrypted);
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO audit_events (action, details) VALUES (?, ?::jsonb)")) {
            insert.setString(1, "SYSTEM_MIGRATION_V59_REENCRYPT");
            insert.setString(2, details);
            insert.executeUpdate();
        }
    }

    private int reencryptOAuth2ClientSecrets(Connection conn, SecretKey key) throws Exception {
        int count = 0;
        List<java.util.UUID> ids = new ArrayList<>();
        List<String> newValues = new ArrayList<>();

        try (PreparedStatement select = conn.prepareStatement(
                "SELECT id, client_secret_encrypted FROM tenant_oauth2_provider")) {
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    String stored = rs.getString("client_secret_encrypted");
                    if (stored == null || stored.isBlank()) continue;
                    if (looksLikeCiphertext(stored, key)) continue;
                    ids.add((java.util.UUID) rs.getObject("id"));
                    newValues.add(encrypt(stored, key));
                }
            }
        }

        if (ids.isEmpty()) return 0;

        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE tenant_oauth2_provider SET client_secret_encrypted = ? WHERE id = ?")) {
            for (int i = 0; i < ids.size(); i++) {
                update.setString(1, newValues.get(i));
                update.setObject(2, ids.get(i));
                update.addBatch();
                count++;
            }
            update.executeBatch();
        }
        return count;
    }

    private int reencryptHmisApiKeys(Connection conn, SecretKey key) throws Exception {
        int count = 0;
        List<java.util.UUID> tenantIds = new ArrayList<>();
        List<String> newConfigs = new ArrayList<>();

        try (PreparedStatement select = conn.prepareStatement(
                "SELECT id, config FROM tenant WHERE config IS NOT NULL")) {
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    String configJson = rs.getString("config");
                    if (configJson == null || configJson.isBlank()) continue;

                    JsonNode root;
                    try {
                        root = objectMapper.readTree(configJson);
                    } catch (Exception e) {
                        log.debug("V59: tenant {} has unparseable config — skipping",
                                rs.getObject("id"));
                        continue;
                    }
                    if (!root.has("hmis_vendors") || !root.get("hmis_vendors").isArray()) {
                        continue;
                    }

                    boolean mutated = false;
                    for (JsonNode vendor : root.get("hmis_vendors")) {
                        if (!(vendor instanceof ObjectNode vendorObj)) continue;
                        if (!vendor.has("api_key_encrypted")) continue;
                        String stored = vendor.get("api_key_encrypted").asText();
                        if (stored == null || stored.isBlank()) continue;
                        if (looksLikeCiphertext(stored, key)) continue;
                        vendorObj.put("api_key_encrypted", encrypt(stored, key));
                        mutated = true;
                        count++;
                    }

                    if (mutated) {
                        tenantIds.add((java.util.UUID) rs.getObject("id"));
                        newConfigs.add(objectMapper.writeValueAsString(root));
                    }
                }
            }
        }

        if (tenantIds.isEmpty()) return count;

        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE tenant SET config = ?::jsonb WHERE id = ?")) {
            for (int i = 0; i < tenantIds.size(); i++) {
                update.setString(1, newConfigs.get(i));
                update.setObject(2, tenantIds.get(i));
                update.addBatch();
            }
            update.executeBatch();
        }
        return count;
    }

    private boolean looksLikeCiphertext(String stored, SecretKey key) {
        try {
            decrypt(stored, key);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String encrypt(String plaintext, SecretKey key) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        ByteBuffer buffer = ByteBuffer.allocate(iv.length + ct.length);
        buffer.put(iv);
        buffer.put(ct);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    private String decrypt(String encrypted, SecretKey key) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(encrypted);
        if (decoded.length < GCM_IV_LENGTH + (GCM_TAG_LENGTH / 8)) {
            throw new IllegalArgumentException("too short to be GCM ciphertext");
        }
        ByteBuffer buffer = ByteBuffer.wrap(decoded);
        byte[] iv = new byte[GCM_IV_LENGTH];
        buffer.get(iv);
        byte[] ct = new byte[buffer.remaining()];
        buffer.get(ct);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        byte[] pt = cipher.doFinal(ct);
        return new String(pt, StandardCharsets.UTF_8);
    }
}
