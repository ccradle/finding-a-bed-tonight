package org.fabt.shared.audit;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.fabt.shared.config.JsonString;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("audit_events")
public class AuditEventEntity {

    @Id
    private UUID id;
    private Instant timestamp;
    private UUID tenantId;
    private UUID actorUserId;
    private UUID targetUserId;
    private String action;
    private JsonString details;
    private String ipAddress;
    // Phase G-1 hash chain — Slice G-1. NULL for pre-V85 rows and for
    // orphan audits under SYSTEM_TENANT_ID; set by AuditChainHasher for
    // all real-tenant rows written post-V85. See V85 migration comments.
    private byte[] prevHash;
    private byte[] rowHash;

    public AuditEventEntity() {}

    public AuditEventEntity(UUID tenantId, UUID actorUserId, UUID targetUserId, String action,
                            JsonString details, String ipAddress) {
        this.tenantId = tenantId;
        this.actorUserId = actorUserId;
        this.targetUserId = targetUserId;
        this.action = action;
        this.details = details;
        this.ipAddress = ipAddress;
        // Truncate to microseconds so the timestamp round-trips through
        // PostgreSQL TIMESTAMPTZ (microsecond precision) without losing
        // sub-microsecond bits. Load-bearing for Phase G-1 chain hashing:
        // the hash input at write time must match the value the G-2
        // verifier reads back from the DB, or SHA-256 output diverges and
        // verification fails. Without truncation, Instant.now() on the
        // JVM has nanosecond precision; a save+read cycle drops the last
        // 3 digits and produces a different canonical_json.
        this.timestamp = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getActorUserId() { return actorUserId; }
    public void setActorUserId(UUID actorUserId) { this.actorUserId = actorUserId; }
    public UUID getTargetUserId() { return targetUserId; }
    public void setTargetUserId(UUID targetUserId) { this.targetUserId = targetUserId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public JsonString getDetails() { return details; }
    public void setDetails(JsonString details) { this.details = details; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public byte[] getPrevHash() { return prevHash; }
    public void setPrevHash(byte[] prevHash) { this.prevHash = prevHash; }
    public byte[] getRowHash() { return rowHash; }
    public void setRowHash(byte[] rowHash) { this.rowHash = rowHash; }
}
