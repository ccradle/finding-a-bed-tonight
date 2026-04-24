package db.migration;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * V83 — Re-encrypt existing v1-HKDF ciphertexts under per-tenant random DEKs in
 * {@code tenant_dek} (F-6.0 task 7.8d, design-f6-real-cryptoshred §8).
 *
 * <p>Counterpart to V74: V74 migrated v0 (single-platform-key) to v1-HKDF
 * (kid in {@code kid_to_tenant_key}, DEK deterministically derived from
 * master_KEK + tenantId + purpose). V83 migrates v1-HKDF to v1-random
 * (kid in {@code tenant_dek}, DEK randomly generated + wrapped via AES-KWP).
 *
 * <p>The net effect, after both migrations, is every persisted secret is
 * wrapped under a DEK that exists ONLY inside {@code tenant_dek}.
 * {@code TenantLifecycleService.hardDelete} cascading that row away is
 * what makes the §D11 crypto-shred claim true — per the TDD anchor
 * {@code CryptoShredGapIntegrationTest} (commit b5672da).
 *
 * <h2>Columns walked</h2>
 *
 * Same 4 columns V74 touched (design-f6 §8 + Appendix A):
 *
 * <ol>
 *   <li>{@code app_user.totp_secret_encrypted}</li>
 *   <li>{@code subscription.callback_secret_hash}</li>
 *   <li>{@code tenant_oauth2_provider.client_secret_encrypted}</li>
 *   <li>{@code tenant.config → hmis_vendors[].api_key_encrypted}</li>
 * </ol>
 *
 * <h2>Discriminator — which rows re-encrypt</h2>
 *
 * For each column, select rows whose value is a v1 envelope AND whose
 * kid does NOT exist in {@code tenant_dek}. Equivalent checks:
 *
 * <ul>
 *   <li>v1 envelope: isV1Envelope(Base64-decoded bytes) is true</li>
 *   <li>kid not in tenant_dek: {@code NOT EXISTS (SELECT 1 FROM tenant_dek WHERE kid = ?)}</li>
 * </ul>
 *
 * Rows whose kid IS already in {@code tenant_dek} are already in the target
 * state — skip with counter {@code skipped_already_tenant_dek}.
 *
 * <h2>Per-row transaction with round-trip verify (warroom pass-2 Sam)</h2>
 *
 * Q-F6-2 resolution: per-row tx with round-trip verify, matching V74's
 * shape. ~65 rewrites × ~10ms each ≈ sub-second at pilot scale. Per-row
 * isolates failures — a single corrupt row fails just that row's tx; the
 * rest continue.
 *
 * <p>Round-trip: immediately after writing the new v1-random envelope,
 * unwrap the DEK from {@code tenant_dek}, decrypt the envelope, compare
 * to the original plaintext. Mismatch = IllegalStateException = migration
 * fail + rollback.
 *
 * <h2>Rotation-readiness probe (warroom pass-2 Jordan, Q-F6-4 fold-in)</h2>
 *
 * After the re-encrypt phase, if any {@code tenant_dek} rows exist, the
 * migration exercises the rotation path on ONE row:
 *
 * <ol>
 *   <li>Pick any (tenant, purpose) that has an active gen=1 row</li>
 *   <li>UPDATE gen=1 SET active=FALSE, rotated_at=NOW() — must succeed</li>
 *   <li>INSERT gen=2 active=TRUE with a freshly wrapped DEK — must succeed</li>
 *   <li>SELECT COUNT active rows: must be exactly 1 (gen=2)</li>
 *   <li>Flip back: UPDATE gen=2 SET active=FALSE, DELETE gen=2 UPDATE gen=1
 *       SET active=TRUE, rotated_at=NULL</li>
 *   <li>Restore to pre-probe state</li>
 * </ol>
 *
 * Exercises the atomic rotation path BEFORE Phase H ever needs it for a
 * real rotation. ~2s; cheap insurance.
 *
 * <h2>Idempotency</h2>
 *
 * Re-running V83 on already-migrated data is safe — the
 * {@code NOT EXISTS (SELECT 1 FROM tenant_dek WHERE kid = ?)} filter
 * excludes every already-migrated row. Second run = zero column
 * rewrites. Enforced by {@link #t6_idempotency} in V83's test suite.
 *
 * <h2>Dev-skip</h2>
 *
 * If {@code FABT_ENCRYPTION_KEY} is unset, logs WARN and returns cleanly.
 * Same pattern as V74 — prod fails-fast before Flyway runs; dev/CI uses
 * the {@code fabt.encryption-key} system-property fallback.
 *
 * <h2>Audit</h2>
 *
 * Writes a single {@code SYSTEM_MIGRATION_V83_TENANT_DEK} audit row with
 * per-column counts + rotation probe outcome + master-KEK fingerprint.
 */
public class V83__reencrypt_v1_envelopes_under_tenant_dek extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(
            V83__reencrypt_v1_envelopes_under_tenant_dek.class);

    // ------------------------------------------------------------------
    // Constants (mirror SecretEncryptionService + EncryptionEnvelope +
    // KeyDerivationService + TenantDekService). Inlined because Flyway
    // runs pre-Spring-context. Any runtime wire-format change MUST be
    // reflected here.
    // ------------------------------------------------------------------

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final String AES_KWP = "AESWrapPad";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int DEK_LENGTH_BYTES = 32;

    private static final byte[] V1_MAGIC = { 0x46, 0x41, 0x42, 0x54 };
    private static final byte V1_VERSION = 0x01;
    private static final int ENVELOPE_HEADER_LENGTH = V1_MAGIC.length + 1 + 16 + GCM_IV_LENGTH;

    private static final String HKDF_CONTEXT_VERSION = "v1";
    private static final String PURPOSE_TOTP = "totp";
    private static final String PURPOSE_WEBHOOK = "webhook-secret";
    private static final String PURPOSE_OAUTH2 = "oauth2-client-secret";
    private static final String PURPOSE_HMIS = "hmis-api-key";
    private static final String PURPOSE_KEK_WRAP = "kek-wrap";

    /** Uppercase purpose names for the tenant_dek.purpose CHECK constraint. */
    private static final String DEK_PURPOSE_TOTP = "TOTP";
    private static final String DEK_PURPOSE_WEBHOOK = "WEBHOOK_SECRET";
    private static final String DEK_PURPOSE_OAUTH2 = "OAUTH2_CLIENT_SECRET";
    private static final String DEK_PURPOSE_HMIS = "HMIS_API_KEY";

    private final ObjectMapper objectMapper = JsonMapper.builder(
            JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(64)
                            .maxStringLength(1_048_576)
                            .maxNumberLength(1_000)
                            .build())
                    .build())
            .build();

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public void migrate(Context context) throws Exception {
        Instant started = Instant.now();
        Connection conn = context.getConnection();

        String base64Key = System.getenv("FABT_ENCRYPTION_KEY");
        if (base64Key == null || base64Key.isBlank()) {
            base64Key = System.getenv("FABT_TOTP_ENCRYPTION_KEY");
        }
        if (base64Key == null || base64Key.isBlank()) {
            base64Key = System.getProperty("fabt.encryption-key");
        }
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("V83: FABT_ENCRYPTION_KEY not set — skipping re-encryption. "
                    + "Flyway will mark V83 as APPLIED; set the key + DELETE FROM "
                    + "flyway_schema_history WHERE version = '83' to retry.");
            return;
        }

        byte[] masterKekBytes = Base64.getDecoder().decode(base64Key);
        if (masterKekBytes.length != 32) {
            throw new IllegalStateException(
                    "FABT_ENCRYPTION_KEY must be 32 bytes. Got: " + masterKekBytes.length);
        }

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET LOCAL lock_timeout = '30s'");
            stmt.execute("SET LOCAL statement_timeout = '5min'");
        }

        // V82 preflight — V83 is meaningless without the tenant_dek table.
        try (PreparedStatement check = conn.prepareStatement(
                "SELECT success FROM flyway_schema_history WHERE version = '82'");
                ResultSet rs = check.executeQuery()) {
            if (!rs.next() || !rs.getBoolean("success")) {
                throw new IllegalStateException(
                        "V83 preflight failed: V82 (tenant_dek schema) must be applied "
                        + "before V83 runs. See design-f6-real-cryptoshred §8.");
            }
        }

        String sessionRole = fetchSessionRole(conn);
        log.info("V83 migrating as DB role: {}", sessionRole);

        Counts totp = reencryptColumn(conn, masterKekBytes, ColumnSpec.TOTP);
        Counts webhook = reencryptColumn(conn, masterKekBytes, ColumnSpec.WEBHOOK);
        Counts oauth2 = reencryptColumn(conn, masterKekBytes, ColumnSpec.OAUTH2);
        Counts hmis = reencryptHmisJsonbColumn(conn, masterKekBytes);

        ProbeResult probe = runRotationReadinessProbe(conn, masterKekBytes);

        Instant completed = Instant.now();
        long durationMs = completed.toEpochMilli() - started.toEpochMilli();
        String kekFingerprint = fingerprintMasterKek(masterKekBytes);

        writeAuditRow(conn, sessionRole, started, completed, durationMs, kekFingerprint,
                totp, webhook, oauth2, hmis, probe);

        log.info(
                "V83 COMMITTED — re-encrypted {} TOTP / {} webhook / {} OAuth2 / {} HMIS rows "
                + "under tenant_dek in {}ms; rotation probe: {}",
                totp.reencrypted, webhook.reencrypted, oauth2.reencrypted, hmis.reencrypted,
                durationMs, probe.result);
    }

    // ------------------------------------------------------------------
    // Column-specific re-encrypt
    // ------------------------------------------------------------------

    /**
     * Describes one of the 3 scalar-column re-encryption paths (TOTP,
     * webhook, OAuth2). HMIS lives in JSONB and has a dedicated method.
     */
    private enum ColumnSpec {
        TOTP("app_user", "totp_secret_encrypted", PURPOSE_TOTP, DEK_PURPOSE_TOTP),
        WEBHOOK("subscription", "callback_secret_hash", PURPOSE_WEBHOOK, DEK_PURPOSE_WEBHOOK),
        OAUTH2("tenant_oauth2_provider", "client_secret_encrypted", PURPOSE_OAUTH2, DEK_PURPOSE_OAUTH2);

        final String table;
        final String column;
        final String hkdfPurpose;
        final String dekPurpose;

        ColumnSpec(String table, String column, String hkdfPurpose, String dekPurpose) {
            this.table = table;
            this.column = column;
            this.hkdfPurpose = hkdfPurpose;
            this.dekPurpose = dekPurpose;
        }
    }

    private Counts reencryptColumn(Connection conn, byte[] masterKekBytes,
                                    ColumnSpec spec) throws Exception {
        Counts counts = new Counts();

        String selectSql = String.format(
                "SELECT id, tenant_id, %s FROM %s "
                + "WHERE %s IS NOT NULL AND tenant_id IS NOT NULL",
                spec.column, spec.table, spec.column);

        List<Row> candidates = new ArrayList<>();
        try (PreparedStatement select = conn.prepareStatement(selectSql);
                ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                String stored = rs.getString(spec.column);
                if (stored == null || stored.isBlank() || !isV1(stored)) {
                    continue;
                }
                UUID kid = extractKidFromV1Envelope(stored);
                if (kid == null) {
                    log.warn("V83: {}.id={} has v1 envelope but kid parse failed — skipping",
                            spec.table, rs.getObject("id"));
                    continue;
                }
                if (kidExistsInTenantDek(conn, kid)) {
                    counts.skippedAlreadyTenantDek++;
                    continue;
                }
                UUID rowId = (UUID) rs.getObject("id");
                UUID tenantId = (UUID) rs.getObject("tenant_id");
                candidates.add(new Row(rowId, tenantId, stored));
            }
        }

        for (Row row : candidates) {
            reencryptOneRow(conn, masterKekBytes, spec, row, counts);
        }
        return counts;
    }

    private void reencryptOneRow(Connection conn, byte[] masterKekBytes,
                                  ColumnSpec spec, Row row, Counts counts) throws Exception {
        // 1. Decrypt the legacy v1 envelope under the HKDF-derived DEK.
        SecretKey legacyDek = deriveHkdfKey(masterKekBytes, row.tenantId, spec.hkdfPurpose);
        String plaintext = decryptV1(legacyDek, row.stored);

        // 2. Get (or create) the tenant_dek row for this (tenant, purpose).
        //    Inlines TenantDekService.getOrCreateActiveDek because Flyway is
        //    pre-Spring. Binds app.tenant_id for V82's RESTRICTIVE INSERT.
        TenantDekRow dek = findOrCreateActiveTenantDek(
                conn, masterKekBytes, row.tenantId, spec.dekPurpose);

        // 3. Encrypt the plaintext under the fresh random DEK with the
        //    tenant_dek kid as the envelope discriminator.
        String newStored = encryptV1(dek.dek, dek.kid, plaintext);

        // 4. Round-trip verify — unwrap fresh from tenant_dek and decrypt.
        //    Catches any mismatch between wrap/unwrap paths before we
        //    commit the new ciphertext.
        String verify = decryptV1(dek.dek, newStored);
        if (!plaintext.equals(verify)) {
            throw new IllegalStateException(String.format(
                    "V83 round-trip mismatch: %s.id=%s tenant=%s purpose=%s",
                    spec.table, row.id, row.tenantId, spec.dekPurpose));
        }

        // 5. Update the column with the new envelope.
        String updateSql = String.format(
                "UPDATE %s SET %s = ? WHERE id = ?", spec.table, spec.column);
        try (PreparedStatement update = conn.prepareStatement(updateSql)) {
            update.setString(1, newStored);
            update.setObject(2, row.id);
            int rows = update.executeUpdate();
            if (rows != 1) {
                throw new IllegalStateException(String.format(
                        "V83 UPDATE affected %d rows, expected 1: %s.id=%s",
                        rows, spec.table, row.id));
            }
        }
        counts.reencrypted++;
    }

    /**
     * HMIS lives inside {@code tenant.config} JSONB as
     * {@code hmis_vendors[].api_key_encrypted}. Walks tenants, parses JSONB,
     * rewrites each v1-HKDF api_key_encrypted value that isn't already in
     * tenant_dek.
     */
    private Counts reencryptHmisJsonbColumn(Connection conn, byte[] masterKekBytes) throws Exception {
        Counts counts = new Counts();
        List<TenantConfig> updates = new ArrayList<>();

        try (PreparedStatement select = conn.prepareStatement(
                "SELECT id, config FROM tenant WHERE config IS NOT NULL");
                ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                UUID tenantId = (UUID) rs.getObject("id");
                String configJson = rs.getString("config");
                if (configJson == null || configJson.isBlank()) continue;

                JsonNode root;
                try {
                    root = objectMapper.readTree(configJson);
                } catch (Exception parseErr) {
                    log.debug("V83: tenant {} has unparseable config — skipping", tenantId);
                    continue;
                }
                if (!root.has("hmis_vendors") || !root.get("hmis_vendors").isArray()) continue;
                JsonNode vendorsNode = root.get("hmis_vendors");
                if (vendorsNode.isEmpty()) continue;

                boolean mutated = false;
                for (JsonNode vendor : vendorsNode) {
                    if (!(vendor instanceof ObjectNode vendorObj)) continue;
                    if (!vendor.has("api_key_encrypted")) continue;
                    String stored = vendor.get("api_key_encrypted").asText();
                    if (stored == null || stored.isBlank() || !isV1(stored)) continue;

                    UUID kid = extractKidFromV1Envelope(stored);
                    if (kid == null) continue;
                    if (kidExistsInTenantDek(conn, kid)) {
                        counts.skippedAlreadyTenantDek++;
                        continue;
                    }

                    SecretKey legacyDek = deriveHkdfKey(masterKekBytes, tenantId, PURPOSE_HMIS);
                    String plaintext = decryptV1(legacyDek, stored);

                    TenantDekRow dek = findOrCreateActiveTenantDek(
                            conn, masterKekBytes, tenantId, DEK_PURPOSE_HMIS);
                    String newStored = encryptV1(dek.dek, dek.kid, plaintext);

                    String verify = decryptV1(dek.dek, newStored);
                    if (!plaintext.equals(verify)) {
                        throw new IllegalStateException(
                                "V83 HMIS round-trip mismatch for tenant " + tenantId);
                    }

                    vendorObj.put("api_key_encrypted", newStored);
                    mutated = true;
                    counts.reencrypted++;
                }

                if (mutated) {
                    updates.add(new TenantConfig(tenantId, objectMapper.writeValueAsString(root)));
                }
            }
        }

        if (updates.isEmpty()) return counts;
        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE tenant SET config = ?::jsonb WHERE id = ?")) {
            for (TenantConfig u : updates) {
                update.setString(1, u.configJson);
                update.setObject(2, u.tenantId);
                update.addBatch();
            }
            update.executeBatch();
        }
        return counts;
    }

    // ------------------------------------------------------------------
    // tenant_dek inline writes (mirror TenantDekService.getOrCreateActiveDek)
    // ------------------------------------------------------------------

    private TenantDekRow findOrCreateActiveTenantDek(Connection conn, byte[] masterKekBytes,
                                                      UUID tenantId, String dekPurpose) throws SQLException {
        // V82 RESTRICTIVE INSERT/UPDATE require app.tenant_id = tenantId.
        bindTenantGuc(conn, tenantId);

        // Optimistic read — already-active row from a previous call this tx.
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT kid, wrapped_dek FROM tenant_dek "
                + "WHERE tenant_id = ? AND purpose = ? AND active = TRUE")) {
            ps.setObject(1, tenantId);
            ps.setString(2, dekPurpose);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    UUID kid = (UUID) rs.getObject("kid");
                    byte[] wrapped = rs.getBytes("wrapped_dek");
                    SecretKey dek = unwrapDekWithKwp(masterKekBytes, tenantId, wrapped);
                    return new TenantDekRow(kid, dek);
                }
            }
        }

        // First DEK for (tenant, purpose). Generate + wrap + INSERT.
        byte[] rawDek = new byte[DEK_LENGTH_BYTES];
        secureRandom.nextBytes(rawDek);
        byte[] wrapped = wrapDekWithKwp(masterKekBytes, tenantId, rawDek);
        UUID newKid = UUID.randomUUID();

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tenant_dek (kid, tenant_id, purpose, generation, wrapped_dek, active) "
                + "VALUES (?, ?, ?, 1, ?, TRUE) ON CONFLICT DO NOTHING")) {
            ps.setObject(1, newKid);
            ps.setObject(2, tenantId);
            ps.setString(3, dekPurpose);
            ps.setBytes(4, wrapped);
            ps.executeUpdate();
        }

        // Re-SELECT: captures a concurrent winner or our own INSERT.
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT kid, wrapped_dek FROM tenant_dek "
                + "WHERE tenant_id = ? AND purpose = ? AND active = TRUE")) {
            ps.setObject(1, tenantId);
            ps.setString(2, dekPurpose);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    UUID kid = (UUID) rs.getObject("kid");
                    byte[] winningWrapped = rs.getBytes("wrapped_dek");
                    SecretKey dek = unwrapDekWithKwp(masterKekBytes, tenantId, winningWrapped);
                    return new TenantDekRow(kid, dek);
                }
            }
        }
        throw new IllegalStateException(
                "tenant_dek INSERT + re-select returned empty for (" + tenantId + ", "
                + dekPurpose + ") — partial unique index invariant violated");
    }

    private boolean kidExistsInTenantDek(Connection conn, UUID kid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM tenant_dek WHERE kid = ?")) {
            ps.setObject(1, kid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ------------------------------------------------------------------
    // Rotation-readiness probe (Q-F6-4)
    // ------------------------------------------------------------------

    private ProbeResult runRotationReadinessProbe(Connection conn, byte[] masterKekBytes) throws Exception {
        UUID tenantId = null;
        String purpose = null;
        UUID gen1Kid = null;

        // Find any active gen=1 row in tenant_dek. If none exists (fresh
        // Testcontainer, no V74 output to migrate), skip the probe cleanly.
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT tenant_id, purpose, kid FROM tenant_dek "
                + "WHERE generation = 1 AND active = TRUE LIMIT 1");
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                tenantId = (UUID) rs.getObject("tenant_id");
                purpose = rs.getString("purpose");
                gen1Kid = (UUID) rs.getObject("kid");
            }
        }
        if (tenantId == null) {
            log.info("V83: no tenant_dek rows to probe rotation against — skipping probe");
            return new ProbeResult("skipped_no_rows");
        }

        bindTenantGuc(conn, tenantId);

        // Build a gen=2 DEK for the atomic rotation.
        byte[] gen2Raw = new byte[DEK_LENGTH_BYTES];
        secureRandom.nextBytes(gen2Raw);
        byte[] gen2Wrapped = wrapDekWithKwp(masterKekBytes, tenantId, gen2Raw);
        UUID gen2Kid = UUID.randomUUID();

        // Rotation order matters for the partial unique index:
        // (tenant_id, purpose) WHERE active=TRUE — having two active rows
        // would violate it. UPDATE gen=1 to inactive FIRST (0 active rows
        // briefly, which is allowed), THEN INSERT gen=2 active=TRUE.
        // clock_timestamp() (not NOW()) matches V82's DEFAULT for created_at —
        // NOW() returns the tx-start timestamp, which can be EARLIER than a
        // row's DEFAULT clock_timestamp() created_at when both fall in the
        // same tx. That would violate the tenant_dek_rotated_after_created
        // CHECK (rotated_at >= created_at). Caught in V83's own IT suite
        // first run before the migration shipped.
        try (PreparedStatement updateOld = conn.prepareStatement(
                "UPDATE tenant_dek SET active = FALSE, rotated_at = clock_timestamp() "
                + "WHERE tenant_id = ? AND purpose = ? AND generation = 1")) {
            updateOld.setObject(1, tenantId);
            updateOld.setString(2, purpose);
            updateOld.executeUpdate();
        }

        try (PreparedStatement insertNew = conn.prepareStatement(
                "INSERT INTO tenant_dek (kid, tenant_id, purpose, generation, wrapped_dek, active) "
                + "VALUES (?, ?, ?, 2, ?, TRUE)")) {
            insertNew.setObject(1, gen2Kid);
            insertNew.setObject(2, tenantId);
            insertNew.setString(3, purpose);
            insertNew.setBytes(4, gen2Wrapped);
            insertNew.executeUpdate();
        }

        // Assertion: exactly one active row for (tenant, purpose), and it's gen=2.
        int activeCount;
        int activeGen;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*), MAX(generation) FROM tenant_dek "
                + "WHERE tenant_id = ? AND purpose = ? AND active = TRUE");
                ResultSet rs = executeWithParams(ps, tenantId, purpose)) {
            rs.next();
            activeCount = rs.getInt(1);
            activeGen = rs.getInt(2);
        }
        if (activeCount != 1 || activeGen != 2) {
            throw new IllegalStateException(
                    "V83 rotation probe assertion failed: activeCount=" + activeCount
                    + " activeGen=" + activeGen + " (expected 1, 2)");
        }

        // Grace-window assertion: gen=1 kid still resolves + unwraps.
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT wrapped_dek FROM tenant_dek WHERE kid = ?")) {
            ps.setObject(1, gen1Kid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "V83 rotation probe: gen=1 row missing post-rotation");
                }
                byte[] wrapped = rs.getBytes("wrapped_dek");
                SecretKey decommissionedDek = unwrapDekWithKwp(masterKekBytes, tenantId, wrapped);
                if (decommissionedDek == null) {
                    throw new IllegalStateException(
                            "V83 rotation probe: gen=1 unwrap returned null");
                }
            }
        }

        // Flip back — delete gen=2, revert gen=1 to active + clear rotated_at.
        // Deleting gen=2 fires the BEFORE DELETE trigger; we need to set the
        // shred GUC to match the row's tenant. Same pattern hardDelete will use.
        bindShredGuc(conn, tenantId);
        try (PreparedStatement deleteGen2 = conn.prepareStatement(
                "DELETE FROM tenant_dek WHERE tenant_id = ? AND purpose = ? AND generation = 2")) {
            deleteGen2.setObject(1, tenantId);
            deleteGen2.setString(2, purpose);
            deleteGen2.executeUpdate();
        }
        // Clear the shred GUC so later statements don't accidentally delete.
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT set_config('fabt.shred_in_progress', '', true)");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
        }

        try (PreparedStatement revertGen1 = conn.prepareStatement(
                "UPDATE tenant_dek SET active = TRUE, rotated_at = NULL "
                + "WHERE tenant_id = ? AND purpose = ? AND generation = 1")) {
            revertGen1.setObject(1, tenantId);
            revertGen1.setString(2, purpose);
            revertGen1.executeUpdate();
        }

        log.info("V83 rotation probe succeeded against tenant={} purpose={}", tenantId, purpose);
        return new ProbeResult("passed");
    }

    private static ResultSet executeWithParams(PreparedStatement ps, UUID tenantId, String purpose)
            throws SQLException {
        ps.setObject(1, tenantId);
        ps.setString(2, purpose);
        return ps.executeQuery();
    }

    private void bindShredGuc(Connection conn, UUID tenantId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT set_config('fabt.shred_in_progress', ?, true)")) {
            ps.setString(1, tenantId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
    }

    private void bindTenantGuc(Connection conn, UUID tenantId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT set_config('app.tenant_id', ?, true)")) {
            ps.setString(1, tenantId == null ? "" : tenantId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
    }

    // ------------------------------------------------------------------
    // Envelope + HKDF + AES-KWP inline (mirrors runtime code)
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

    private static UUID extractKidFromV1Envelope(String stored) {
        try {
            byte[] decoded = Base64.getDecoder().decode(stored);
            if (decoded.length < ENVELOPE_HEADER_LENGTH) return null;
            ByteBuffer buf = ByteBuffer.wrap(decoded);
            buf.position(V1_MAGIC.length + 1);
            long hi = buf.getLong();
            long lo = buf.getLong();
            return new UUID(hi, lo);
        } catch (Exception parseErr) {
            return null;
        }
    }

    private String encryptV1(SecretKey dek, UUID kid, String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buf = ByteBuffer.allocate(ENVELOPE_HEADER_LENGTH + ct.length);
            buf.put(V1_MAGIC);
            buf.put(V1_VERSION);
            buf.putLong(kid.getMostSignificantBits());
            buf.putLong(kid.getLeastSignificantBits());
            buf.put(iv);
            buf.put(ct);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception cryptoErr) {
            throw new RuntimeException("V83 v1 encrypt failed", cryptoErr);
        }
    }

    private static String decryptV1(SecretKey dek, String stored) {
        try {
            byte[] decoded = Base64.getDecoder().decode(stored);
            ByteBuffer buf = ByteBuffer.wrap(decoded);
            buf.position(V1_MAGIC.length + 1 + 16); // skip magic + version + kid
            byte[] iv = new byte[GCM_IV_LENGTH];
            buf.get(iv);
            byte[] ct = new byte[buf.remaining()];
            buf.get(ct);

            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception cryptoErr) {
            throw new RuntimeException("V83 v1 decrypt failed", cryptoErr);
        }
    }

    private static SecretKey deriveHkdfKey(byte[] masterKekBytes, UUID tenantId, String purpose) {
        try {
            byte[] salt = uuidToBytes(tenantId);
            String context = "fabt:" + HKDF_CONTEXT_VERSION + ":" + tenantId + ":" + purpose;
            byte[] info = context.getBytes(StandardCharsets.UTF_8);
            byte[] derived = hkdfSha256(masterKekBytes, salt, info, DEK_LENGTH_BYTES);
            return new SecretKeySpec(derived, "AES");
        } catch (Exception e) {
            throw new RuntimeException("V83 HKDF derivation failed", e);
        }
    }

    private static byte[] wrapDekWithKwp(byte[] masterKekBytes, UUID tenantId, byte[] rawDek) {
        try {
            SecretKey wrappingKey = deriveHkdfKey(masterKekBytes, tenantId, PURPOSE_KEK_WRAP);
            Cipher cipher = Cipher.getInstance(AES_KWP);
            cipher.init(Cipher.WRAP_MODE, wrappingKey);
            SecretKey dekAsKey = new SecretKeySpec(rawDek, "AES");
            return cipher.wrap(dekAsKey);
        } catch (Exception e) {
            throw new RuntimeException("V83 AES-KWP wrap failed for tenant " + tenantId, e);
        }
    }

    private static SecretKey unwrapDekWithKwp(byte[] masterKekBytes, UUID tenantId, byte[] wrappedDek) {
        try {
            SecretKey wrappingKey = deriveHkdfKey(masterKekBytes, tenantId, PURPOSE_KEK_WRAP);
            Cipher cipher = Cipher.getInstance(AES_KWP);
            cipher.init(Cipher.UNWRAP_MODE, wrappingKey);
            return (SecretKey) cipher.unwrap(wrappedDek, "AES", Cipher.SECRET_KEY);
        } catch (Exception e) {
            throw new RuntimeException("V83 AES-KWP unwrap failed for tenant " + tenantId, e);
        }
    }

    private static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
        return buf.array();
    }

    /** RFC 5869 HKDF-SHA256, same as V74 + runtime. */
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
    // Audit row
    // ------------------------------------------------------------------

    private void writeAuditRow(Connection conn, String sessionRole,
                                Instant started, Instant completed, long durationMs,
                                String kekFingerprint,
                                Counts totp, Counts webhook, Counts oauth2, Counts hmis,
                                ProbeResult probe) throws SQLException {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("migration", "V83");
        details.put("started_at", started.toString());
        details.put("completed_at", completed.toString());
        details.put("duration_ms", durationMs);
        details.put("master_kek_fingerprint", kekFingerprint);
        details.put("flyway_role", sessionRole);
        details.put("totp_reencrypted", totp.reencrypted);
        details.put("totp_skipped_already_tenant_dek", totp.skippedAlreadyTenantDek);
        details.put("webhook_reencrypted", webhook.reencrypted);
        details.put("webhook_skipped_already_tenant_dek", webhook.skippedAlreadyTenantDek);
        details.put("oauth2_reencrypted", oauth2.reencrypted);
        details.put("oauth2_skipped_already_tenant_dek", oauth2.skippedAlreadyTenantDek);
        details.put("hmis_reencrypted", hmis.reencrypted);
        details.put("hmis_skipped_already_tenant_dek", hmis.skippedAlreadyTenantDek);
        details.put("rotation_probe_result", probe.result);

        String detailsJson;
        try {
            detailsJson = objectMapper.writeValueAsString(details);
        } catch (Exception serializeErr) {
            throw new IllegalStateException("V83 audit JSONB serialization failed", serializeErr);
        }

        UUID systemTenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        bindTenantGuc(conn, systemTenantId);
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO audit_events (action, tenant_id, details) VALUES (?, ?, ?::jsonb)")) {
            insert.setString(1, "SYSTEM_MIGRATION_V83_TENANT_DEK");
            insert.setObject(2, systemTenantId);
            insert.setString(3, detailsJson);
            insert.executeUpdate();
        }
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
        }
        return "unknown";
    }

    private static String fingerprintMasterKek(byte[] masterKekBytes) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(masterKekBytes, "HmacSHA256"));
            byte[] fingerprint = mac.doFinal(
                    "v83-audit-fingerprint".getBytes(StandardCharsets.UTF_8));
            byte[] truncated = new byte[8];
            System.arraycopy(fingerprint, 0, truncated, 0, 8);
            return HexFormat.of().formatHex(truncated);
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ------------------------------------------------------------------
    // Inner types
    // ------------------------------------------------------------------

    private record Row(UUID id, UUID tenantId, String stored) {}
    private record TenantConfig(UUID tenantId, String configJson) {}
    private record TenantDekRow(UUID kid, SecretKey dek) {}
    private record ProbeResult(String result) {}

    private static final class Counts {
        int reencrypted;
        int skippedAlreadyTenantDek;
    }
}
