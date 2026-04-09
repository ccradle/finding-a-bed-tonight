package org.fabt.notification.domain;

import java.time.Instant;
import java.util.UUID;

import org.fabt.shared.config.JsonString;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Persistent notification — survives logout/restart.
 * Payload is JSONB with zero client PII (designed to support VAWA/FVPSA).
 */
@Table("notification")
public class Notification {

    @Id
    private UUID id;
    private UUID tenantId;
    private UUID recipientId;
    private String type;
    private String severity;
    private JsonString payload;
    private Instant readAt;
    private Instant actedAt;
    private Instant createdAt;
    private Instant expiresAt;

    public Notification() {
    }

    public Notification(UUID tenantId, UUID recipientId, String type, String severity, String payload) {
        this.tenantId = tenantId;
        this.recipientId = recipientId;
        this.type = type;
        this.severity = severity;
        this.payload = JsonString.of(payload);
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getRecipientId() { return recipientId; }
    public void setRecipientId(UUID recipientId) { this.recipientId = recipientId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public JsonString getPayload() { return payload; }
    public String getPayloadValue() { return payload != null ? payload.value() : null; }
    public void setPayload(JsonString payload) { this.payload = payload; }
    public void setPayloadString(String payload) { this.payload = JsonString.of(payload); }
    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant readAt) { this.readAt = readAt; }
    public Instant getActedAt() { return actedAt; }
    public void setActedAt(Instant actedAt) { this.actedAt = actedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Notification that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        // Stable hash for transient entities (id == null) — uses class identity.
        // Once persisted, hash is based on the immutable PK.
        return id != null ? id.hashCode() : System.identityHashCode(this);
    }
}
