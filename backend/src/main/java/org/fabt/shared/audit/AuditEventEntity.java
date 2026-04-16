package org.fabt.shared.audit;

import java.time.Instant;
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

    public AuditEventEntity() {}

    public AuditEventEntity(UUID tenantId, UUID actorUserId, UUID targetUserId, String action,
                            JsonString details, String ipAddress) {
        this.tenantId = tenantId;
        this.actorUserId = actorUserId;
        this.targetUserId = targetUserId;
        this.action = action;
        this.details = details;
        this.ipAddress = ipAddress;
        this.timestamp = Instant.now();
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
}
