package db.migration;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * V74 — Re-encrypt existing v0 ciphertexts under per-tenant HKDF-derived DEKs
 * (Phase A task 2.13, design-a5-v74-reencrypt). Covers the four per-tenant
 * encrypted columns per A3 D22:
 *
 * <ol>
 *   <li>{@code app_user.totp_secret_encrypted} — TOTP shared secrets</li>
 *   <li>{@code subscription.callback_secret_hash} — webhook HMAC secrets</li>
 *   <li>{@code tenant_oauth2_provider.client_secret_encrypted} — OAuth2 client secrets</li>
 *   <li>{@code tenant.config → hmis_vendors[].api_key_encrypted} — HMIS API keys (JSONB)</li>
 * </ol>
 *
 * <h2>Idempotency (D31)</h2>
 * Each candidate value is Base64-decoded and checked for the v1 envelope magic
 * ({@code "FABT\x01"}). Already-v1 rows are counted as {@code skipped_already_v1}
 * and left untouched. Only v0 rows (no FABT magic) are decrypted under the
 * platform key and re-encrypted under the tenant DEK. Re-run safe.
 *
 * <h2>Transaction scope (D32)</h2>
 * Single Flyway transaction. At pilot scale (≤10 users × 1 TOTP + ≤5 subscriptions
 * per tenant + handful of OAuth2 providers + handful of HMIS vendors ≈ tens of KB
 * in-flight) this is trivially small. Row-exclusive locks taken on every modified
 * row are held for migration duration; at pilot scale this is under a second.
 *
 * <p><b>Memory footprint:</b> all candidate ciphertexts are loaded into JVM heap
 * for round-trip decrypt+encrypt. At pilot scale this is negligible. If the
 * target schema has &gt; 10k re-encryptable rows per column, re-evaluate this
 * migration in favor of batched per-row transactions with an outer retry loop.
 * (W-A5-7)
 *
 * <h2>Per-row round-trip verify (C-A5-N3)</h2>
 * After every v0→v1 conversion, the new v1 bytes are immediately decrypted under
 * the same per-tenant DEK and compared to the original plaintext. Catches
 * tenant_id drift, GCM key misalignment, HKDF salt misconfiguration. Mismatch
 * fails the whole Flyway transaction.
 *
 * <h2>Observability (D35 / C-A5-N10)</h2>
 * Writes a single {@code SYSTEM_MIGRATION_V74_REENCRYPT} audit row at the end
 * of the migration with expanded JSONB: counts per column (re-encrypted /
 * skipped-already-v1 / skipped-null-tenant), timing, master-KEK fingerprint,
 * and the Flyway session role. JSONB is produced via {@link JsonMapper} to
 * avoid SQL-injection-adjacent {@code String.format} patterns (W-A5-1).
 *
 * <h2>Dev-skip (C-A5-N9)</h2>
 * If {@code FABT_ENCRYPTION_KEY} is unset, logs WARN and returns cleanly. No
 * audit row. Flyway still marks V74 as APPLIED — if an operator later sets the
 * env var in dev, V74 will NOT re-run automatically. Recovery: {@code DELETE
 * FROM flyway_schema_history WHERE version = '74'} and restart the stack. In
 * prod, the Phase 0 C2 + Phase A W-A4-3 startup guards fail-fast before Flyway
 * even runs without a key, so this branch is unreachable in prod.
 *
 * <h2>Rollback (D37)</h2>
 * Flyway atomic transaction → partial failure = full rollback = DB unchanged.
 * Post-commit, V74 is effectively one-way: there is no reverse migration.
 * Operator rollback requires restoring from the pre-deploy {@code pg_dump}
 * (runbook 2.16 mandates this as a deploy precondition).
 *
 * <h2>Flyway role (C-A5-N6)</h2>
 * Writes to {@code audit_events}, {@code kid_to_tenant_key}, {@code tenant_key_material},
 * {@code app_user}, {@code subscription}, {@code tenant_oauth2_provider}, {@code tenant}.
 * Must run as the table owner role (typically {@code fabt}) or a role with
 * BYPASSRLS. The current session role + its BYPASSRLS / superuser flags are
 * logged at {@code migrate()} start and captured in the audit row's
 * {@code flyway_role} field so operators can verify pre-deploy via
 * {@code \dp} that grants are correct. Restricted-role fail-loudly coverage
 * is deferred — runbook 2.16 mandates the operator-side {@code \dp} check.
 *
 * <h2>Phase A preflight (C-A5-N7)</h2>
 * Throws if {@code flyway_schema_history} does not contain successful V60 and
 * V61 rows. Protects against a release accidentally shipping V74 without the
 * Phase A schema that it depends on.
 */
public class V74__reencrypt_secrets_under_per_tenant_deks extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(
            V74__reencrypt_secrets_under_per_tenant_deks.class);

    // ------------------------------------------------------------------
    // Constants mirror SecretEncryptionService + EncryptionEnvelope +
    // KeyDerivationService. Changes in runtime code MUST be reflected
    // here — the V74 migration replays the runtime encrypt path inline
    // because Flyway runs pre-Spring-context.
    // ------------------------------------------------------------------

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    /** v1 envelope magic bytes — must match {@code EncryptionEnvelope.MAGIC}. */
    private static final byte[] V1_MAGIC = { 0x46, 0x41, 0x42, 0x54 };
    private static final byte V1_VERSION = 0x01;

    /** HKDF derivation context version — must match {@code KeyDerivationService}. */
    private static final String HKDF_CONTEXT_VERSION = "v1";

    private static final String PURPOSE_TOTP = "totp";
    private static final String PURPOSE_WEBHOOK = "webhook-secret";
    private static final String PURPOSE_OAUTH2 = "oauth2-client-secret";
    private static final String PURPOSE_HMIS = "hmis-api-key";

    /**
     * C-A5-N5 hardened ObjectMapper: bounds nesting depth + string length so
     * a malicious {@code tenant.config} JSONB cannot blow Jackson's defaults
     * and crash the migration mid-flight. In Jackson 3 the constraints live
     * on the {@link JsonFactory}, not the {@link JsonMapper.Builder}.
     */
    private final ObjectMapper objectMapper = JsonMapper.builder(
            JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(64)
                            .maxStringLength(1_048_576)     // 1 MB
                            .maxNumberLength(1_000)
                            .build())
                    .build())
            .build();

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public void migrate(Context context) throws Exception {
        Instant started = Instant.now();
        Connection conn = context.getConnection();

        // D36 / C-A5-N9: dev-skip path. Prod boot already fails-fast without
        // the key, so this branch is only reachable in dev/CI. The system-
        // property fallback exists to give integration tests a knob (where
        // env vars are hard to set) without relaxing prod semantics — the
        // Phase 0 startup guard already runs in prod before Flyway.
        String base64Key = System.getenv("FABT_ENCRYPTION_KEY");
        if (base64Key == null || base64Key.isBlank()) {
            base64Key = System.getenv("FABT_TOTP_ENCRYPTION_KEY");
        }
        if (base64Key == null || base64Key.isBlank()) {
            base64Key = System.getProperty("fabt.encryption-key");
        }
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("V74: FABT_ENCRYPTION_KEY not set — skipping re-encryption. "
                    + "Flyway will mark V74 as APPLIED; set the key + DELETE FROM "
                    + "flyway_schema_history WHERE version = '74' to retry.");
            return;
        }

        byte[] masterKekBytes = Base64.getDecoder().decode(base64Key);
        if (masterKekBytes.length != 32) {
            throw new IllegalStateException(
                    "FABT_ENCRYPTION_KEY must be 32 bytes (256 bits). Got: " + masterKekBytes.length);
        }
        SecretKey platformKey = new SecretKeySpec(masterKekBytes, "AES");

        // C-A5-N1: bound migration lock time + statement time so a stray
        // background lock cannot pin the deploy indefinitely.
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET LOCAL lock_timeout = '30s'");
            stmt.execute("SET LOCAL statement_timeout = '5min'");
        }

        // C-A5-N7 Phase A preflight — both V60 and V61 must be applied + successful.
        try (PreparedStatement check = conn.prepareStatement(
                "SELECT version, success FROM flyway_schema_history "
                + "WHERE version IN ('60', '61')")) {
            ResultSet rs = check.executeQuery();
            int seen = 0;
            while (rs.next()) {
                if (!rs.getBoolean("success")) {
                    throw new IllegalStateException(
                            "V74 preflight failed: Phase A migration "
                            + rs.getString("version") + " was not successful. "
                            + "V74 cannot run without V60 + V61.");
                }
                seen++;
            }
            if (seen < 2) {
                throw new IllegalStateException(
                        "V74 preflight failed: Phase A migrations V60 + V61 must both be applied "
                        + "before V74 can run (found " + seen + " of 2). "
                        + "This usually indicates a release bundling error — v0.42 MUST include "
                        + "Phase A. See design-a5-v74-reencrypt §C-A5-N7 / §C-A5-N8.");
            }
        }

        // Diagnostic: surface the session role so operators can cross-check against
        // the required role grants (must be BYPASSRLS or table owner — C-A5-N6).
        String sessionRole = fetchSessionRole(conn);
        log.info("V74 migrating as DB role: {}", sessionRole);

        Counts totp = reencryptTotpSecrets(conn, masterKekBytes, platformKey);
        Counts webhook = reencryptWebhookSecrets(conn, masterKekBytes, platformKey);
        Counts oauth2 = reencryptOAuth2Secrets(conn, masterKekBytes, platformKey);
        Counts hmis = reencryptHmisSecrets(conn, masterKekBytes, platformKey);

        Instant completed = Instant.now();
        long durationMs = completed.toEpochMilli() - started.toEpochMilli();
        String kekFingerprint = fingerprintMasterKek(masterKekBytes);

        writeAuditRow(conn, sessionRole, started, completed, durationMs, kekFingerprint,
                totp, webhook, oauth2, hmis);

        // W-A5-6: distinctive structured log line so an operator grepping
        // journalctl can determine whether V74 committed independent of
        // Spring startup logs.
        log.info(
                "V74 COMMITTED — re-encrypted {} TOTP / {} webhook / {} OAuth2 / {} HMIS rows in {}ms",
                totp.reencrypted, webhook.reencrypted, oauth2.reencrypted, hmis.reencrypted, durationMs);
    }

    // ------------------------------------------------------------------
    // Column-specific migration paths
    // ------------------------------------------------------------------

    private Counts reencryptTotpSecrets(Connection conn, byte[] masterKekBytes,
                                         SecretKey platformKey) throws Exception {
        Counts counts = new Counts();

        // C-A5-N2: WHERE tenant_id IS NOT NULL + preflight count of NULL-tenant rows.
        counts.skippedNullTenant = countSkippedNullTenant(conn,
                "SELECT COUNT(*) FROM app_user "
                + "WHERE totp_secret_encrypted IS NOT NULL AND tenant_id IS NULL");
        if (counts.skippedNullTenant > 0) {
            log.warn("V74: {} app_user rows skipped due to NULL tenant_id "
                    + "(cannot derive per-tenant DEK without tenantId)", counts.skippedNullTenant);
        }

        // W-A5-4: SELECT ... FOR UPDATE to take explicit row-exclusive locks.
        List<UUID> ids = new ArrayList<>();
        List<UUID> tenantIds = new ArrayList<>();
        List<String> newCiphertexts = new ArrayList<>();
        List<String> plaintexts = new ArrayList<>();

        try (PreparedStatement select = conn.prepareStatement(
                "SELECT id, tenant_id, totp_secret_encrypted FROM app_user "
                + "WHERE totp_secret_encrypted IS NOT NULL AND tenant_id IS NOT NULL "
                + "FOR UPDATE")) {
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    UUID userId = (UUID) rs.getObject("id");
                    String stored = rs.getString("totp_secret_encrypted");
                    if (stored == null || stored.isBlank()) continue;
                    if (isV1(stored)) {
                        counts.skippedAlreadyV1++;
                        continue;
                    }
                    UUID tenantId = (UUID) rs.getObject("tenant_id");
                    String plaintext;
                    try {
                        plaintext = decryptV0(platformKey, stored);
                    } catch (RuntimeException legacyPlaintext) {
                        // v0.42.1 plaintext-tolerance (uniform with OAuth2/HMIS pattern).
                        // TOTP has been encrypted since V31, so plaintext here implies
                        // a test seed or corruption. Treat as plaintext + wrap in v1 so
                        // the migration completes; operator follows up via the
                        // totp_plaintext_fallback count in the audit row.
                        log.warn("V74: app_user.id={} totp_secret_encrypted not valid v0 — "
                                + "treating as plaintext and encrypting-v1", userId);
                        plaintext = stored;
                        counts.plaintextFallback++;
                    }
                    UUID kid = findOrCreateActiveKid(conn, tenantId);
                    SecretKey dek = deriveKey(masterKekBytes, tenantId, PURPOSE_TOTP);
                    String reencrypted = encryptV1(dek, kid, plaintext);

                    // C-A5-N3 round-trip verify
                    String verify = decryptV1(dek, reencrypted);
                    if (!plaintext.equals(verify)) {
                        throw new IllegalStateException(
                                "V74 TOTP round-trip mismatch for app_user.id=" + userId);
                    }

                    ids.add(userId);
                    tenantIds.add(tenantId);
                    newCiphertexts.add(reencrypted);
                    plaintexts.add(plaintext);
                }
            }
        }

        if (ids.isEmpty()) return counts;

        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE app_user SET totp_secret_encrypted = ? WHERE id = ?")) {
            for (int i = 0; i < ids.size(); i++) {
                update.setString(1, newCiphertexts.get(i));
                update.setObject(2, ids.get(i));
                update.addBatch();
                counts.reencrypted++;
            }
            update.executeBatch();
        }
        // Clear plaintext refs promptly; minor memory-hygiene gesture.
        plaintexts.clear();
        return counts;
    }

    private Counts reencryptWebhookSecrets(Connection conn, byte[] masterKekBytes,
                                            SecretKey platformKey) throws Exception {
        Counts counts = new Counts();

        counts.skippedNullTenant = countSkippedNullTenant(conn,
                "SELECT COUNT(*) FROM subscription "
                + "WHERE callback_secret_hash IS NOT NULL AND tenant_id IS NULL");
        if (counts.skippedNullTenant > 0) {
            log.warn("V74: {} subscription rows skipped due to NULL tenant_id",
                    counts.skippedNullTenant);
        }

        List<UUID> ids = new ArrayList<>();
        List<String> newCiphertexts = new ArrayList<>();

        try (PreparedStatement select = conn.prepareStatement(
                "SELECT id, tenant_id, callback_secret_hash FROM subscription "
                + "WHERE callback_secret_hash IS NOT NULL AND tenant_id IS NOT NULL "
                + "FOR UPDATE")) {
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    UUID subId = (UUID) rs.getObject("id");
                    String stored = rs.getString("callback_secret_hash");
                    if (stored == null || stored.isBlank()) continue;
                    if (isV1(stored)) {
                        counts.skippedAlreadyV1++;
                        continue;
                    }
                    UUID tenantId = (UUID) rs.getObject("tenant_id");
                    String plaintext;
                    try {
                        plaintext = decryptV0(platformKey, stored);
                    } catch (RuntimeException legacyPlaintext) {
                        // v0.42.1 plaintext-tolerance (uniform with OAuth2/HMIS pattern).
                        // Seed data may carry placeholder plaintexts (e.g., from original
                        // multi-tenant-infrastructure change set). Preserves runtime
                        // webhook HMAC-signing behavior — the plaintext is kept verbatim
                        // inside the v1 envelope. Operator follows up via
                        // webhook_plaintext_fallback count in the audit row.
                        log.warn("V74: subscription.id={} callback_secret_hash not valid v0 — "
                                + "treating as plaintext and encrypting-v1", subId);
                        plaintext = stored;
                        counts.plaintextFallback++;
                    }
                    UUID kid = findOrCreateActiveKid(conn, tenantId);
                    SecretKey dek = deriveKey(masterKekBytes, tenantId, PURPOSE_WEBHOOK);
                    String reencrypted = encryptV1(dek, kid, plaintext);

                    String verify = decryptV1(dek, reencrypted);
                    if (!plaintext.equals(verify)) {
                        throw new IllegalStateException(
                                "V74 webhook round-trip mismatch for subscription.id=" + subId);
                    }

                    ids.add(subId);
                    newCiphertexts.add(reencrypted);
                }
            }
        }

        if (ids.isEmpty()) return counts;

        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE subscription SET callback_secret_hash = ? WHERE id = ?")) {
            for (int i = 0; i < ids.size(); i++) {
                update.setString(1, newCiphertexts.get(i));
                update.setObject(2, ids.get(i));
                update.addBatch();
                counts.reencrypted++;
            }
            update.executeBatch();
        }
        return counts;
    }

    private Counts reencryptOAuth2Secrets(Connection conn, byte[] masterKekBytes,
                                           SecretKey platformKey) throws Exception {
        Counts counts = new Counts();

        counts.skippedNullTenant = countSkippedNullTenant(conn,
                "SELECT COUNT(*) FROM tenant_oauth2_provider "
                + "WHERE client_secret_encrypted IS NOT NULL AND tenant_id IS NULL");
        if (counts.skippedNullTenant > 0) {
            log.warn("V74: {} tenant_oauth2_provider rows skipped due to NULL tenant_id",
                    counts.skippedNullTenant);
        }

        List<UUID> ids = new ArrayList<>();
        List<String> newCiphertexts = new ArrayList<>();

        try (PreparedStatement select = conn.prepareStatement(
                "SELECT id, tenant_id, client_secret_encrypted FROM tenant_oauth2_provider "
                + "WHERE client_secret_encrypted IS NOT NULL AND tenant_id IS NOT NULL "
                + "FOR UPDATE")) {
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    String stored = rs.getString("client_secret_encrypted");
                    if (stored == null || stored.isBlank()) continue;
                    if (isV1(stored)) {
                        counts.skippedAlreadyV1++;
                        continue;
                    }
                    UUID providerId = (UUID) rs.getObject("id");
                    UUID tenantId = (UUID) rs.getObject("tenant_id");
                    String plaintext;
                    try {
                        plaintext = decryptV0(platformKey, stored);
                    } catch (RuntimeException legacyPlaintext) {
                        // OAuth2 + HMIS have a plaintext-tolerance fallback in the
                        // runtime read path. If V74 encounters a row that doesn't
                        // decrypt under the platform key, assume it was stored as
                        // plaintext (legacy, pre-V59) and pass it through the
                        // encrypt-v1 path. Matches the decryptClientSecret
                        // try/catch contract in DynamicClientRegistrationSource.
                        log.warn("V74: tenant_oauth2_provider.id={} not valid v0 ciphertext — "
                                + "treating as plaintext and encrypting-v1", providerId);
                        plaintext = stored;
                        counts.plaintextFallback++;
                    }
                    UUID kid = findOrCreateActiveKid(conn, tenantId);
                    SecretKey dek = deriveKey(masterKekBytes, tenantId, PURPOSE_OAUTH2);
                    String reencrypted = encryptV1(dek, kid, plaintext);

                    String verify = decryptV1(dek, reencrypted);
                    if (!plaintext.equals(verify)) {
                        throw new IllegalStateException(
                                "V74 OAuth2 round-trip mismatch for tenant_oauth2_provider.id="
                                + providerId);
                    }

                    ids.add(providerId);
                    newCiphertexts.add(reencrypted);
                }
            }
        }

        if (ids.isEmpty()) return counts;

        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE tenant_oauth2_provider SET client_secret_encrypted = ? WHERE id = ?")) {
            for (int i = 0; i < ids.size(); i++) {
                update.setString(1, newCiphertexts.get(i));
                update.setObject(2, ids.get(i));
                update.addBatch();
                counts.reencrypted++;
            }
            update.executeBatch();
        }
        return counts;
    }

    private Counts reencryptHmisSecrets(Connection conn, byte[] masterKekBytes,
                                         SecretKey platformKey) throws Exception {
        Counts counts = new Counts();

        // HMIS is JSONB-embedded; no separate tenant_id column to NULL-check.
        // Iterate tenant rows directly.
        List<UUID> tenantIds = new ArrayList<>();
        List<String> newConfigs = new ArrayList<>();

        try (PreparedStatement select = conn.prepareStatement(
                "SELECT id, config FROM tenant "
                + "WHERE id IS NOT NULL AND config IS NOT NULL "
                + "FOR UPDATE")) {
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    String configJson = rs.getString("config");
                    if (configJson == null || configJson.isBlank()) continue;
                    UUID tenantId = (UUID) rs.getObject("id");

                    JsonNode root;
                    try {
                        root = objectMapper.readTree(configJson);
                    } catch (Exception parseErr) {
                        log.debug("V74: tenant {} has unparseable config — skipping",
                                tenantId);
                        continue;
                    }
                    if (!root.has("hmis_vendors") || !root.get("hmis_vendors").isArray()) {
                        continue;
                    }
                    JsonNode vendorsNode = root.get("hmis_vendors");
                    if (vendorsNode.isEmpty()) continue;

                    boolean mutated = false;
                    for (JsonNode vendor : vendorsNode) {
                        if (!(vendor instanceof ObjectNode vendorObj)) continue;
                        if (!vendor.has("api_key_encrypted")) continue;
                        String stored = vendor.get("api_key_encrypted").asText();
                        if (stored == null || stored.isBlank()) continue;
                        if (isV1(stored)) {
                            counts.skippedAlreadyV1++;
                            continue;
                        }
                        String plaintext;
                        try {
                            plaintext = decryptV0(platformKey, stored);
                        } catch (RuntimeException legacyPlaintext) {
                            // Plaintext-tolerance fallback; see OAuth2 case above.
                            log.warn("V74: tenant {} api_key_encrypted not valid v0 — "
                                    + "treating as plaintext and encrypting-v1", tenantId);
                            plaintext = stored;
                            counts.plaintextFallback++;
                        }
                        UUID kid = findOrCreateActiveKid(conn, tenantId);
                        SecretKey dek = deriveKey(masterKekBytes, tenantId, PURPOSE_HMIS);
                        String reencrypted = encryptV1(dek, kid, plaintext);

                        String verify = decryptV1(dek, reencrypted);
                        if (!plaintext.equals(verify)) {
                            throw new IllegalStateException(
                                    "V74 HMIS round-trip mismatch for tenant.id=" + tenantId);
                        }

                        vendorObj.put("api_key_encrypted", reencrypted);
                        mutated = true;
                        counts.reencrypted++;
                    }

                    if (mutated) {
                        tenantIds.add(tenantId);
                        newConfigs.add(objectMapper.writeValueAsString(root));
                    }
                }
            }
        }

        if (tenantIds.isEmpty()) return counts;

        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE tenant SET config = ?::jsonb WHERE id = ?")) {
            for (int i = 0; i < tenantIds.size(); i++) {
                update.setString(1, newConfigs.get(i));
                update.setObject(2, tenantIds.get(i));
                update.addBatch();
            }
            update.executeBatch();
        }
        return counts;
    }

    // ------------------------------------------------------------------
    // Audit row (D35 + C-A5-N10)
    // ------------------------------------------------------------------

    /**
     * Writes the V74 audit row. Task 3.26: bind app.tenant_id to
     * SYSTEM_TENANT_ID before the INSERT so the FORCE-RLS policy on
     * audit_events accepts the row. V74 is a system-origin migration —
     * not tenant-scoped — so SYSTEM_TENANT_ID (00000000-...-0001) is the
     * correct attribution per D55.
     */
    private void writeAuditRow(Connection conn, String sessionRole,
                                Instant started, Instant completed, long durationMs,
                                String kekFingerprint,
                                Counts totp, Counts webhook, Counts oauth2, Counts hmis)
            throws SQLException {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("migration", "V74");
        details.put("started_at", started.toString());
        details.put("completed_at", completed.toString());
        details.put("duration_ms", durationMs);
        details.put("master_kek_fingerprint", kekFingerprint);
        details.put("flyway_role", sessionRole);
        details.put("totp_reencrypted", totp.reencrypted);
        details.put("totp_skipped_already_v1", totp.skippedAlreadyV1);
        details.put("totp_skipped_null_tenant", totp.skippedNullTenant);
        details.put("totp_plaintext_fallback", totp.plaintextFallback);
        details.put("webhook_reencrypted", webhook.reencrypted);
        details.put("webhook_skipped_already_v1", webhook.skippedAlreadyV1);
        details.put("webhook_skipped_null_tenant", webhook.skippedNullTenant);
        details.put("webhook_plaintext_fallback", webhook.plaintextFallback);
        details.put("oauth2_reencrypted", oauth2.reencrypted);
        details.put("oauth2_skipped_already_v1", oauth2.skippedAlreadyV1);
        details.put("oauth2_skipped_null_tenant", oauth2.skippedNullTenant);
        details.put("oauth2_plaintext_fallback", oauth2.plaintextFallback);
        details.put("hmis_reencrypted", hmis.reencrypted);
        details.put("hmis_skipped_already_v1", hmis.skippedAlreadyV1);
        details.put("hmis_plaintext_fallback", hmis.plaintextFallback);

        String detailsJson;
        try {
            detailsJson = objectMapper.writeValueAsString(details);
        } catch (Exception serializeErr) {
            // Should be impossible — Map<String, Object> with primitives.
            throw new IllegalStateException("V74 audit JSONB serialization failed", serializeErr);
        }

        // Task 3.26: bind to SYSTEM_TENANT_ID before the audit INSERT +
        // write tenant_id on the row so the FORCE-RLS policy accepts it.
        UUID systemTenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        bindTenantGuc(conn, systemTenantId);
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO audit_events (action, tenant_id, details) VALUES (?, ?, ?::jsonb)")) {
            insert.setString(1, "SYSTEM_MIGRATION_V74_REENCRYPT");
            insert.setObject(2, systemTenantId);
            insert.setString(3, detailsJson);
            insert.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    // Kid registry inline path (Q3 + Marcus W-A5 enhancement)
    //
    // Mirrors KidRegistryService.findOrCreateActiveKid's INSERT-ON-CONFLICT-
    // DO-NOTHING + re-SELECT pattern. Race-free by construction because Flyway
    // runs pre-application-boot — no concurrent first-encrypts exist yet.
    // ------------------------------------------------------------------

    private UUID findOrCreateActiveKid(Connection conn, UUID tenantId) throws SQLException {
        // Task 3.26 (Phase B): bind app.tenant_id to this tenant BEFORE any
        // INSERT into tenant_key_material / kid_to_tenant_key. V68/V69 apply
        // RESTRICTIVE write policies to both tables — without this binding,
        // the INSERTs fail under FORCE RLS. Parameterized set_config avoids
        // SQL injection (per Marcus C-A5-N2 / Jordan); is_local=true scopes
        // to the Flyway tx (autoCommit=false under Flyway default).
        bindTenantGuc(conn, tenantId);
        ensureActiveGeneration(conn, tenantId);
        int activeGeneration = getActiveGeneration(conn, tenantId);
        return findOrCreateKid(conn, tenantId, activeGeneration);
    }

    /**
     * Task 3.26 helper: binds {@code app.tenant_id} to {@code tenantId}
     * for the current transaction via parameterized {@code set_config} with
     * {@code is_local=true}. Per Phase B D46: never string-interpolate the
     * tenant UUID into SQL — always bind parameter.
     */
    private void bindTenantGuc(Connection conn, UUID tenantId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT set_config('app.tenant_id', ?, true)")) {
            ps.setString(1, tenantId == null ? "" : tenantId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
    }

    private void ensureActiveGeneration(Connection conn, UUID tenantId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tenant_key_material (tenant_id, generation, active) "
                + "VALUES (?, 1, TRUE) "
                + "ON CONFLICT DO NOTHING")) {
            ps.setObject(1, tenantId);
            ps.executeUpdate();
        }
    }

    private int getActiveGeneration(Connection conn, UUID tenantId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT generation FROM tenant_key_material "
                + "WHERE tenant_id = ? AND active = TRUE")) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "tenant_key_material has no active generation for tenant "
                            + tenantId + " — ensureActiveGeneration should have created one");
                }
                return rs.getInt("generation");
            }
        }
    }

    private UUID findOrCreateKid(Connection conn, UUID tenantId, int generation) throws SQLException {
        try (PreparedStatement lookup = conn.prepareStatement(
                "SELECT kid FROM kid_to_tenant_key WHERE tenant_id = ? AND generation = ?")) {
            lookup.setObject(1, tenantId);
            lookup.setInt(2, generation);
            try (ResultSet rs = lookup.executeQuery()) {
                if (rs.next()) {
                    return (UUID) rs.getObject("kid");
                }
            }
        }
        UUID newKid = UUID.randomUUID();
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO kid_to_tenant_key (kid, tenant_id, generation) "
                + "VALUES (?, ?, ?) "
                + "ON CONFLICT (tenant_id, generation) DO NOTHING")) {
            insert.setObject(1, newKid);
            insert.setObject(2, tenantId);
            insert.setInt(3, generation);
            insert.executeUpdate();
        }
        try (PreparedStatement reSelect = conn.prepareStatement(
                "SELECT kid FROM kid_to_tenant_key WHERE tenant_id = ? AND generation = ?")) {
            reSelect.setObject(1, tenantId);
            reSelect.setInt(2, generation);
            try (ResultSet rs = reSelect.executeQuery()) {
                if (rs.next()) {
                    return (UUID) rs.getObject("kid");
                }
                throw new IllegalStateException(
                        "kid_to_tenant_key re-select returned empty after INSERT — "
                        + "tenant " + tenantId + " generation " + generation);
            }
        }
    }

    // ------------------------------------------------------------------
    // Envelope + crypto helpers
    //
    // Mirror runtime EncryptionEnvelope + KeyDerivationService inline. Any
    // change in the runtime wire format MUST be reflected here — V74 is
    // versioned, immutable, and cannot be amended post-deploy.
    // ------------------------------------------------------------------

    private static boolean isV1(String stored) {
        try {
            byte[] decoded = Base64.getDecoder().decode(stored);
            if (decoded.length < V1_MAGIC.length + 1) return false;
            for (int i = 0; i < V1_MAGIC.length; i++) {
                if (decoded[i] != V1_MAGIC[i]) return false;
            }
            return decoded[V1_MAGIC.length] == V1_VERSION;
        } catch (IllegalArgumentException badBase64) {
            return false;
        }
    }

    private static String decryptV0(SecretKey platformKey, String stored) {
        try {
            byte[] decoded = Base64.getDecoder().decode(stored);
            if (decoded.length < GCM_IV_LENGTH + 16) {
                throw new IllegalArgumentException("v0 too short");
            }
            ByteBuffer buf = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buf.get(iv);
            byte[] ct = new byte[buf.remaining()];
            buf.get(ct);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, platformKey,
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception cryptoErr) {
            throw new RuntimeException("v0 decrypt failed", cryptoErr);
        }
    }

    private String encryptV1(SecretKey dek, UUID kid, String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ctWithTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buf = ByteBuffer.allocate(V1_MAGIC.length + 1 + 16 + GCM_IV_LENGTH + ctWithTag.length);
            buf.put(V1_MAGIC);
            buf.put(V1_VERSION);
            buf.putLong(kid.getMostSignificantBits());
            buf.putLong(kid.getLeastSignificantBits());
            buf.put(iv);
            buf.put(ctWithTag);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception cryptoErr) {
            throw new RuntimeException("v1 encrypt failed", cryptoErr);
        }
    }

    /**
     * Round-trip verify helper (C-A5-N3). Parses the v1 envelope we just
     * produced and decrypts it under the same DEK — confirms the written
     * bytes decrypt back to the original plaintext before we commit them.
     */
    private static String decryptV1(SecretKey dek, String stored) {
        try {
            byte[] decoded = Base64.getDecoder().decode(stored);
            int headerLen = V1_MAGIC.length + 1 + 16; // magic + version + kid
            ByteBuffer buf = ByteBuffer.wrap(decoded);
            buf.position(headerLen);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buf.get(iv);
            byte[] ctWithTag = new byte[buf.remaining()];
            buf.get(ctWithTag);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ctWithTag), StandardCharsets.UTF_8);
        } catch (Exception cryptoErr) {
            throw new RuntimeException("v1 round-trip decrypt failed", cryptoErr);
        }
    }

    private static SecretKey deriveKey(byte[] masterKekBytes, UUID tenantId, String purpose) {
        try {
            byte[] salt = uuidToBytes(tenantId);
            String context = "fabt:" + HKDF_CONTEXT_VERSION + ":" + tenantId + ":" + purpose;
            byte[] info = context.getBytes(StandardCharsets.UTF_8);
            byte[] derived = hkdfSha256(masterKekBytes, salt, info, 32);
            return new SecretKeySpec(derived, "AES");
        } catch (Exception derivationErr) {
            throw new RuntimeException("HKDF derivation failed", derivationErr);
        }
    }

    private static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
        return buf.array();
    }

    /**
     * RFC 5869 HKDF-SHA256. Direct port of {@code KeyDerivationService.hkdfSha256}.
     */
    private static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int outputLength)
            throws Exception {
        final int OUT = 32;
        byte[] effectiveSalt = (salt == null || salt.length == 0) ? new byte[OUT] : salt;

        Mac extract = Mac.getInstance("HmacSHA256");
        extract.init(new SecretKeySpec(effectiveSalt, "HmacSHA256"));
        byte[] prk = extract.doFinal(ikm);

        Mac expand = Mac.getInstance("HmacSHA256");
        expand.init(new SecretKeySpec(prk, "HmacSHA256"));
        int blocks = (outputLength + OUT - 1) / OUT;
        byte[] result = new byte[outputLength];
        byte[] previous = new byte[0];
        int written = 0;
        for (int i = 1; i <= blocks; i++) {
            expand.reset();
            expand.update(previous);
            expand.update(info);
            expand.update((byte) i);
            previous = expand.doFinal();
            int copyLen = Math.min(OUT, outputLength - written);
            System.arraycopy(previous, 0, result, written, copyLen);
            written += copyLen;
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Diagnostic helpers
    // ------------------------------------------------------------------

    private static String fetchSessionRole(Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT current_user, rolbypassrls, rolsuper "
                + "FROM pg_roles WHERE rolname = current_user");
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return String.format("%s (bypassrls=%s, superuser=%s)",
                        rs.getString("current_user"),
                        rs.getBoolean("rolbypassrls"),
                        rs.getBoolean("rolsuper"));
            }
        } catch (SQLException ignored) {
            // non-fatal — role diagnostic is nice-to-have
        }
        return "unknown";
    }

    /**
     * One-way HMAC-SHA256 fingerprint of the master KEK under a fixed label.
     * Proves V74 ran under the same KEK later reads will use, without leaking
     * the KEK. Truncated to 16 hex chars (8 bytes = 64 bits of collision
     * resistance — enough for "did the KEK drift between V74 and first read").
     */
    private static String fingerprintMasterKek(byte[] masterKekBytes) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(masterKekBytes, "HmacSHA256"));
            byte[] fingerprint = mac.doFinal(
                    "v74-audit-fingerprint".getBytes(StandardCharsets.UTF_8));
            byte[] truncated = new byte[8];
            System.arraycopy(fingerprint, 0, truncated, 0, 8);
            return HexFormat.of().formatHex(truncated);
        } catch (Exception hashErr) {
            // Fingerprint is diagnostic only; failure doesn't block migration.
            return "unknown";
        }
    }

    private static int countSkippedNullTenant(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Per-column counters tracked by each re-encrypt path. */
    private static final class Counts {
        int reencrypted;
        int skippedAlreadyV1;
        int skippedNullTenant;
        // v0.42.1: rows whose stored value failed v0 decryption and were
        // treated as plaintext + wrapped in v1 (preserving runtime behavior).
        // Matches the existing OAuth2 + HMIS plaintext-tolerance pattern
        // extended uniformly to TOTP + webhook in v0.42.1 after the v0.42.0
        // deploy surfaced 3 subscription seed rows with `placeholder_*`
        // plaintext values. Deliberately exposed in the audit event so an
        // operator can grep `*_plaintext_fallback > 0` and follow up.
        int plaintextFallback;
    }
}
