package org.fabt.tenant.service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.ResultSetMetaData;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.json.JsonFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Streams a tenant's user-facing data to a schema-versioned JSON envelope on local
 * disk (Q5 resolution: local disk for v0.51.0; S3 migration deferred to Phase H).
 * Invoked from {@link TenantLifecycleService#offboard} as part of the GDPR Art. 20
 * data-portability obligation.
 *
 * <p><b>Export shape:</b>
 * <pre>
 * {
 *   "schemaVersion": "1.0.0",
 *   "tenantId": "...",
 *   "exportedAt": "2026-...",
 *   "tenant": [ { ... single row ... } ],
 *   "shelters": [ ... ],
 *   "users": [ ... ],
 *   "reservations": [ ... ],
 *   "referral_tokens": [ ... ],
 *   "notifications": [ ... ],
 *   "subscriptions": [ ... ],
 *   "api_keys": [ ... ]           // hashes REDACTED (see below)
 * }
 * </pre>
 *
 * <p><b>What's NOT exported (intentionally):</b>
 * <ul>
 *   <li>{@code tenant_key_material}, {@code kid_to_tenant_key}, {@code jwt_revocations}
 *       — platform crypto metadata, not user data.</li>
 *   <li>{@code audit_events} — platform-held forensic record (GDPR Art. 20 covers
 *       data the user provided; audit rows are platform-originated).</li>
 *   <li>{@code api_key.key_hash} + {@code api_key.old_key_hash} — secret hashes,
 *       deliberately excluded from the SELECT.</li>
 *   <li>{@code webhook_delivery_log}, {@code hmis_outbox}, {@code hmis_audit_log}
 *       — operational tables, not user data.</li>
 * </ul>
 *
 * <p><b>Crash-safety:</b> writes to {@code <file>.partial} first, then atomically
 * renames to the final filename on success. Mid-write failures leave a
 * {@code .partial} file that an operator can clean up, but the final filename is
 * always a complete, parseable JSON document — the presence of which is what
 * {@link TenantLifecycleService#archive} checks as the prerequisite for transition
 * to ARCHIVED.
 *
 * <p><b>Transaction semantics:</b> the caller
 * ({@link TenantLifecycleService#offboard}) is {@code @Transactional}. The JDBC
 * queries here join that tx (REQUIRED propagation by default), which gives a
 * point-in-time-consistent view of the tenant's data — no concurrent write can
 * change export contents mid-stream. For prod-scale tenants (3 active, ~100 rows
 * each) the tx is sub-second. When tenant data scales to GB, this service will
 * need a split-tx refactor; see {@code project_phase_f_implementation_plan.md}
 * F-5 risk register.
 */
@Service
public class TenantOffboardExportService {

    private static final Logger log = LoggerFactory.getLogger(TenantOffboardExportService.class);

    static final String SCHEMA_VERSION = "1.0.0";

    // Exposed package-private so the schema test can reference the single source of truth.
    static final String[] EXPORT_TABLES = {"tenant", "shelters", "users", "reservations",
        "referral_tokens", "notifications", "subscriptions", "api_keys"};

    private static final DateTimeFormatter FILENAME_TS =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC);

    private final JdbcTemplate jdbc;
    private final JsonFactory jsonFactory;
    private final Path exportBasePath;

    public TenantOffboardExportService(
            JdbcTemplate jdbc,
            @Value("${fabt.tenant.offboard.export-path:/var/fabt/exports}") String exportBasePath) {
        this.jdbc = jdbc;
        this.exportBasePath = Path.of(exportBasePath);
        this.jsonFactory = new JsonFactory();
    }

    /**
     * Exports the tenant's user-facing data to
     * {@code <exportBasePath>/<tenantId>/<YYYYMMDD-HHMMSS>.json} and returns the
     * absolute path. Callers store the returned URI on
     * {@code tenant.offboard_export_receipt_uri} so {@code archive()} can verify it
     * exists.
     *
     * @throws UncheckedIOException if the filesystem write fails; the outer
     *     {@code @Transactional} on the caller rolls back the state transition so
     *     no half-offboarded tenant persists.
     */
    @Transactional(readOnly = true)
    public String exportTenant(UUID tenantId) {
        String timestamp = FILENAME_TS.format(Instant.now());
        Path tenantDir = exportBasePath.resolve(tenantId.toString());
        Path finalPath = tenantDir.resolve(timestamp + ".json");
        Path partialPath = tenantDir.resolve(timestamp + ".json.partial");

        try {
            Files.createDirectories(tenantDir);
            // Restrict tenant directory to owner-only (rwx------). PII / DV data
            // sits inside; no reason a co-tenant OS user on the VM should read it.
            tryRestrictPermissions(tenantDir, "rwx------");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create export directory " + tenantDir, e);
        }

        try (OutputStream out = Files.newOutputStream(partialPath,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             JsonGenerator gen = jsonFactory.createGenerator(out, JsonEncoding.UTF8)) {

            // Restrict the partial file to owner-only (rw-------) before we write
            // any payload. The subsequent atomic rename preserves these perms.
            tryRestrictPermissions(partialPath, "rw-------");

            gen.writeStartObject();
            gen.writeStringProperty("schemaVersion", SCHEMA_VERSION);
            gen.writeStringProperty("tenantId", tenantId.toString());
            gen.writeStringProperty("exportedAt", Instant.now().toString());

            writeTable(gen, "tenant",
                "SELECT * FROM tenant WHERE id = ?", tenantId);
            writeTable(gen, "shelters",
                "SELECT * FROM shelter WHERE tenant_id = ?", tenantId);
            writeTable(gen, "users",
                "SELECT * FROM app_user WHERE tenant_id = ?", tenantId);
            writeTable(gen, "reservations",
                "SELECT * FROM reservation WHERE tenant_id = ?", tenantId);
            writeTable(gen, "referral_tokens",
                "SELECT * FROM referral_token WHERE tenant_id = ?", tenantId);
            writeTable(gen, "notifications",
                "SELECT * FROM notification WHERE tenant_id = ?", tenantId);
            writeTable(gen, "subscriptions",
                "SELECT * FROM subscription WHERE tenant_id = ?", tenantId);
            // api_keys: hashes DELIBERATELY excluded — key_hash + old_key_hash are
            // secret material even though the plaintext key is not recoverable
            // from them. The export shows that a key existed (id + suffix +
            // label + role + active) so the tenant can reconstruct their
            // inventory, but not the authentication secret itself.
            writeTable(gen,
                "api_keys",
                "SELECT id, tenant_id, shelter_id, key_suffix, label, role, active, "
                + "created_at, last_used_at FROM api_key WHERE tenant_id = ?",
                tenantId);

            gen.writeEndObject();
            gen.flush();
        } catch (IOException e) {
            // Clean up the .partial file on any failure so the next attempt
            // starts fresh. The atomic rename below is the single success signal.
            try { Files.deleteIfExists(partialPath); } catch (IOException ignored) { }
            throw new UncheckedIOException(
                "Failed to write tenant export for " + tenantId, e);
        }

        try {
            Files.move(partialPath, finalPath,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try { Files.deleteIfExists(partialPath); } catch (IOException ignored) { }
            throw new UncheckedIOException(
                "Failed to atomic-rename export file " + partialPath + " -> " + finalPath, e);
        }

        log.info("Exported tenant {} data to {}", tenantId, finalPath);
        return finalPath.toString();
    }

    /**
     * Best-effort POSIX permission restriction. Silently no-ops on filesystems
     * that don't support POSIX perms (Windows dev/test hosts, exotic mounts).
     * The restriction is a defense-in-depth measure for the Oracle VM (Linux)
     * where the file could otherwise be readable by any local user via the
     * default umask.
     */
    private static void tryRestrictPermissions(Path path, String posixPerms) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(posixPerms));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX FS or permission-denied (rare — we just created the file);
            // do not let this block the export. Worst case is the file keeps
            // umask-derived perms (0644) which is what we had before F-5.
        }
    }

    /**
     * Streams one table into {@code "fieldName": [ {row}, {row}, ... ]}. Uses
     * {@link JdbcTemplate#query(String, org.springframework.jdbc.core.RowCallbackHandler, Object[])}
     * with a RowCallbackHandler that writes each row directly to the JsonGenerator
     * — no full ResultSet materialization, safe for arbitrary row counts.
     */
    private void writeTable(JsonGenerator gen, String fieldName, String sql, Object... params) {
        // Jackson 3's generator methods throw JacksonException (runtime) — not
        // checked IOException — so no try/catch wrapper is needed here; any
        // stream-level I/O failure surfaces as a RuntimeException which the
        // caller's outer handling (UncheckedIOException / tx rollback) catches.
        gen.writeArrayPropertyStart(fieldName);
        jdbc.query(sql, rs -> {
            gen.writeStartObject();
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String colName = meta.getColumnLabel(i);
                Object value = rs.getObject(i);
                writeField(gen, colName, value);
            }
            gen.writeEndObject();
        }, params);
        gen.writeEndArray();
    }

    /**
     * Handles the DB types Spring Data JDBC / pgjdbc may return for a SELECT *.
     * {@link JsonGenerator#writeObjectField} (Jackson 3) does not always produce a
     * stable representation for {@code java.sql.Timestamp} /
     * {@code PGobject}; make the fallback explicit as {@code toString()} so the
     * envelope is reproducible regardless of driver upgrade.
     */
    private static void writeField(JsonGenerator gen, String name, Object value) {
        if (value == null) {
            gen.writeNullProperty(name);
            return;
        }
        if (value instanceof String s) {
            gen.writeStringProperty(name, s);
        } else if (value instanceof UUID u) {
            gen.writeStringProperty(name, u.toString());
        } else if (value instanceof Number n) {
            gen.writeNumberProperty(name, n.doubleValue());
        } else if (value instanceof Boolean b) {
            gen.writeBooleanProperty(name, b);
        } else if (value instanceof java.sql.Timestamp ts) {
            gen.writeStringProperty(name, ts.toInstant().toString());
        } else if (value instanceof java.sql.Date d) {
            gen.writeStringProperty(name, d.toLocalDate().toString());
        } else {
            // Fallback — toString() for PGobject (jsonb, enums, etc.) and any
            // other exotic driver types. Always produces a parseable string.
            gen.writeStringProperty(name, value.toString());
        }
    }
}
