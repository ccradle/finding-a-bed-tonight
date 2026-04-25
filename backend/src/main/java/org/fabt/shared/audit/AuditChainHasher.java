package org.fabt.shared.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.fabt.shared.web.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Phase G slice G-1 — per-tenant audit hash chain writer.
 *
 * <p>Each {@code audit_events} INSERT becomes a link in a per-tenant SHA-256
 * hash chain. The link is computed inside the INSERT transaction:
 *
 * <ol>
 *   <li>{@link #computeHashes} reads the tenant's current head hash from
 *       {@code tenant_audit_chain_head.last_hash} with {@code SELECT ... FOR
 *       UPDATE} (serialises concurrent writers for the same tenant), then
 *       returns the {@link HashedRow} containing the row's {@code prev_hash}
 *       (the just-read head) and {@code row_hash = SHA-256(prev_hash ||
 *       canonical_json(row))}.</li>
 *   <li>Caller stamps the two hashes onto the {@link AuditEventEntity} and
 *       calls {@code repository.save(entity)}. The DB assigns the row id.</li>
 *   <li>Caller invokes {@link #advanceChainHead} with {@code entity.getId()}
 *       to UPDATE the chain head (atomic with the INSERT — same tx).</li>
 * </ol>
 *
 * <h2>Canonical JSON form (HASH INPUT IS PERMANENT)</h2>
 *
 * <p>{@link #canonicalJson} renders the row as a JSON object with
 * fixed-order fields:
 * {@code {"tenant_id":...,"timestamp":...,"actor_user_id":...,
 * "target_user_id":...,"action":...,"details":<raw jsonb|null>,
 * "ip_address":...}}.
 *
 * <p>Once the first chain row is written to a tenant, <b>this form is
 * permanent</b>. A future change to field order, escaping rules, null
 * representation, or any other visible-in-output detail breaks the
 * hash-stability contract — the verifier (Slice G-2) would fail to reproduce
 * historical hashes. Any change must ship with a migration that recomputes
 * hashes for every affected row AND re-signs the external anchor (Slice G-3).
 *
 * <p>{@code id} is intentionally excluded from the canonical form because
 * the DB assigns it on INSERT (after hashing). Row ordering in the chain is
 * enforced by {@code tenant_audit_chain_head.last_row_id}, not by the id in
 * the hash.
 *
 * <h2>SYSTEM_TENANT_ID orphan path</h2>
 *
 * <p>{@link TenantContext#SYSTEM_TENANT_ID} is a sentinel — no row in
 * {@code tenant} and therefore no row in {@code tenant_audit_chain_head}.
 * Audits that fall back to this tenant id are orphans already carrying a
 * WARN signal per Phase B D55. This hasher detects the sentinel and skips
 * hashing — the row's {@code prev_hash} and {@code row_hash} stay NULL.
 * Orphan audits remain evidential but are not linked into any tenant chain.
 *
 * <h2>Missing chain head (defensive)</h2>
 *
 * <p>If {@code tenant_audit_chain_head} has no row for the tenant
 * (theoretically impossible after V80's backfill + TenantLifecycleService.create
 * seeding, but possible under a test that inserts tenant rows directly
 * without going through the service), the hasher also skips hashing and
 * increments {@code fabt.audit.chain_missing_head.count} so operators notice.
 * Chain-verification (G-2) will flag the NULL-hash row anyway.
 */
@Service
public class AuditChainHasher {

    private static final Logger log = LoggerFactory.getLogger(AuditChainHasher.class);

    /** SHA-256 digest size — invariant pinned by the V85 CHECK constraint. */
    public static final int HASH_LENGTH_BYTES = 32;

    private final JdbcTemplate jdbc;
    private final MeterRegistry meterRegistry;

    public AuditChainHasher(JdbcTemplate jdbc,
                            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.jdbc = jdbc;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    /**
     * Read the tenant's current chain head (with row lock) and compute the
     * new row's {@code prev_hash} + {@code row_hash}. Call BEFORE
     * {@code repository.save(entity)} so the hashes can be stamped onto the
     * entity and persisted in the same INSERT.
     *
     * <p>Returns {@link HashedRow#SKIPPED} for orphan paths
     * (SYSTEM_TENANT_ID, or any tenant without a chain head row). The
     * caller should leave {@code prev_hash} and {@code row_hash} unset
     * (null) on the entity in those cases.
     */
    public HashedRow computeHashes(UUID tenantId, AuditEventEntity entity) {
        if (TenantContext.SYSTEM_TENANT_ID.equals(tenantId)) {
            // Orphan path. Phase B D55 already treats this as "publisher
            // forgot to bind TenantContext" — don't chain these rows.
            return HashedRow.SKIPPED;
        }

        byte[] prevHash;
        try {
            prevHash = jdbc.query(
                    "SELECT last_hash FROM tenant_audit_chain_head "
                    + "WHERE tenant_id = ? FOR UPDATE",
                    rs -> rs.next() ? rs.getBytes(1) : null,
                    tenantId);
        } catch (Exception e) {
            log.warn("Failed to read tenant_audit_chain_head for tenant={}: {}. "
                    + "Row will be inserted with NULL hash — will surface in G-2 verifier.",
                    tenantId, e.getMessage());
            incrementMissingHeadCounter();
            return HashedRow.SKIPPED;
        }

        if (prevHash == null) {
            // No chain head row — unexpected. V80 backfill + TenantLifecycleService.create
            // both seed one. Log + skip rather than block the audit INSERT.
            log.warn("tenant_audit_chain_head has no row for tenant={} — audit row "
                    + "will be inserted with NULL hash. Investigate the tenant's "
                    + "creation path; every tenant should have a seeded chain head.",
                    tenantId);
            incrementMissingHeadCounter();
            return HashedRow.SKIPPED;
        }

        if (prevHash.length != HASH_LENGTH_BYTES) {
            // CHECK constraint in V80 should have prevented this, but defensive.
            log.error("tenant_audit_chain_head.last_hash for tenant={} is {} bytes, "
                    + "expected {}. Skipping hash for this row.",
                    tenantId, prevHash.length, HASH_LENGTH_BYTES);
            incrementMissingHeadCounter();
            return HashedRow.SKIPPED;
        }

        byte[] canonical = canonicalJson(entity).getBytes(StandardCharsets.UTF_8);
        byte[] rowHash = sha256(concat(prevHash, canonical));

        return new HashedRow(prevHash, rowHash);
    }

    /**
     * Update {@code tenant_audit_chain_head} to point at the just-inserted
     * row. Call AFTER {@code repository.save(entity)} so {@code entity.getId()}
     * is available.
     *
     * <p>No-op when {@code hashed} is {@link HashedRow#SKIPPED} — orphan
     * paths do not advance any chain.
     *
     * <p>Atomicity: this UPDATE and the preceding audit_events INSERT run
     * in the same tx (REQUIRED or REQUIRES_NEW depending on caller).
     * Rollback rolls both back together; commit commits both — chain head
     * and audit row never disagree.
     */
    public void advanceChainHead(UUID tenantId, UUID auditRowId, HashedRow hashed) {
        if (hashed == null || hashed == HashedRow.SKIPPED) return;

        int updated = jdbc.update(
                "UPDATE tenant_audit_chain_head "
                + "SET last_hash = ?, last_row_id = ?, updated_at = NOW() "
                + "WHERE tenant_id = ?",
                hashed.rowHash(), auditRowId, tenantId);

        if (updated != 1) {
            // Chain head disappeared between computeHashes() SELECT FOR UPDATE
            // and this UPDATE — only possible if another tx committed a DELETE
            // on the chain head (i.e. tenant hardDelete). That path captures
            // last_hash first, so losing the race here is a correctness issue
            // for the audit trail but doesn't silently corrupt the chain.
            // Log + emit metric for operator attention.
            log.error("advanceChainHead updated {} rows for tenant={} auditRowId={} — "
                    + "chain head row may have been concurrently deleted. The audit "
                    + "row INSERT is committed but the chain head was not advanced.",
                    updated, tenantId, auditRowId);
            incrementAdvanceFailedCounter();
        }
    }

    /**
     * Serialise the entity to canonical JSON used as the hash input.
     *
     * <p><b>Stability contract.</b> This output form is permanent. Any
     * change after first ship requires recomputing hashes for every
     * affected row and re-signing the external anchor (G-3).
     *
     * <p>Public so the verifier (G-2) can call the same code path — a
     * single implementation of the canonical form prevents drift between
     * writer + verifier.
     */
    public String canonicalJson(AuditEventEntity entity) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"tenant_id\":").append(jsonStringOrNull(
                entity.getTenantId() == null ? null : entity.getTenantId().toString()));
        sb.append(",\"timestamp\":").append(jsonStringOrNull(
                entity.getTimestamp() == null ? null : entity.getTimestamp().toString()));
        sb.append(",\"actor_user_id\":").append(jsonStringOrNull(
                entity.getActorUserId() == null ? null : entity.getActorUserId().toString()));
        sb.append(",\"target_user_id\":").append(jsonStringOrNull(
                entity.getTargetUserId() == null ? null : entity.getTargetUserId().toString()));
        sb.append(",\"action\":").append(jsonStringOrNull(entity.getAction()));
        sb.append(",\"details\":");
        if (entity.getDetails() == null || entity.getDetails().value() == null) {
            sb.append("null");
        } else {
            // Route details through the canonicaliser (G-2 §8.6a): writer gets
            // Jackson-produced insertion-order JSON; verifier gets PG JSONB
            // ::text (sorted keys + `": "` separator). AuditCanonicalJson
            // re-serialises both into compact sorted form, so the hash input
            // is byte-identical regardless of path.
            sb.append(AuditCanonicalJson.canonicalize(entity.getDetails().value()));
        }
        sb.append(",\"ip_address\":").append(jsonStringOrNull(entity.getIpAddress()));
        sb.append('}');
        return sb.toString();
    }

    /** Returns {@code "value"} with JSON-escape rules applied, or {@code null}. */
    private static String jsonStringOrNull(String value) {
        if (value == null) return "null";
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    static byte[] sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a required JCA algorithm — unreachable on any
            // standard JVM. Wrap as unchecked so callers aren't forced
            // to declare it.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private void incrementMissingHeadCounter() {
        if (meterRegistry != null) {
            Counter.builder("fabt.audit.chain_missing_head.count")
                    .description("Audit rows written without a linked chain head (expected for pre-V85 rows + SYSTEM_TENANT_ID orphans; non-zero rate for real tenants is a data-integrity signal)")
                    .register(meterRegistry)
                    .increment();
        }
    }

    private void incrementAdvanceFailedCounter() {
        if (meterRegistry != null) {
            Counter.builder("fabt.audit.chain_advance_failed.count")
                    .description("UPDATE of tenant_audit_chain_head affected != 1 row — chain head concurrently deleted between compute + advance")
                    .register(meterRegistry)
                    .increment();
        }
    }

    /**
     * Immutable result of {@link #computeHashes}. {@link #SKIPPED} is the
     * sentinel returned for orphan paths where no chain update should occur.
     */
    public record HashedRow(byte[] prevHash, byte[] rowHash) {
        public static final HashedRow SKIPPED = new HashedRow(null, null);
    }
}
