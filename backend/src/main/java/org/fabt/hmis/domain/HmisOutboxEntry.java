package org.fabt.hmis.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox entry for async HMIS push. Survives application restarts.
 * Status lifecycle: PENDING → SENT (success) or FAILED → DEAD_LETTER (after 3 retries).
 */
public class HmisOutboxEntry {

    private UUID id;
    private UUID tenantId;
    private UUID shelterId;
    private String vendorType;
    private String status;
    private String payload;
    private Instant createdAt;
    private Instant sentAt;
    private String errorMessage;
    private int retryCount;

    public HmisOutboxEntry() {}

    public HmisOutboxEntry(UUID tenantId, UUID shelterId, String vendorType, String payload) {
        this.tenantId = tenantId;
        this.shelterId = shelterId;
        this.vendorType = vendorType;
        this.status = "PENDING";
        this.payload = payload;
        this.createdAt = Instant.now();
        this.retryCount = 0;
    }

    public boolean isPending() { return "PENDING".equals(status); }
    public boolean isDeadLetter() { return "DEAD_LETTER".equals(status); }

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getShelterId() { return shelterId; }
    public void setShelterId(UUID shelterId) { this.shelterId = shelterId; }
    public String getVendorType() { return vendorType; }
    public void setVendorType(String vendorType) { this.vendorType = vendorType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
}
