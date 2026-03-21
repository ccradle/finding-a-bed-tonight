package org.fabt.surge.domain;

import java.time.Instant;
import java.util.UUID;

public class SurgeEvent {

    private UUID id;
    private UUID tenantId;
    private SurgeEventStatus status;
    private String reason;
    private String boundingBox; // JSONB stored as string
    private UUID activatedBy;
    private Instant activatedAt;
    private Instant deactivatedAt;
    private UUID deactivatedBy;
    private Instant scheduledEnd;
    private Instant createdAt;

    public SurgeEvent() {}

    public SurgeEvent(UUID tenantId, String reason, String boundingBox,
                      UUID activatedBy, Instant scheduledEnd) {
        this.tenantId = tenantId;
        this.status = SurgeEventStatus.ACTIVE;
        this.reason = reason;
        this.boundingBox = boundingBox;
        this.activatedBy = activatedBy;
        this.scheduledEnd = scheduledEnd;
    }

    public boolean isActive() { return status == SurgeEventStatus.ACTIVE; }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public SurgeEventStatus getStatus() { return status; }
    public void setStatus(SurgeEventStatus status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getBoundingBox() { return boundingBox; }
    public void setBoundingBox(String boundingBox) { this.boundingBox = boundingBox; }
    public UUID getActivatedBy() { return activatedBy; }
    public void setActivatedBy(UUID activatedBy) { this.activatedBy = activatedBy; }
    public Instant getActivatedAt() { return activatedAt; }
    public void setActivatedAt(Instant activatedAt) { this.activatedAt = activatedAt; }
    public Instant getDeactivatedAt() { return deactivatedAt; }
    public void setDeactivatedAt(Instant deactivatedAt) { this.deactivatedAt = deactivatedAt; }
    public UUID getDeactivatedBy() { return deactivatedBy; }
    public void setDeactivatedBy(UUID deactivatedBy) { this.deactivatedBy = deactivatedBy; }
    public Instant getScheduledEnd() { return scheduledEnd; }
    public void setScheduledEnd(Instant scheduledEnd) { this.scheduledEnd = scheduledEnd; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
