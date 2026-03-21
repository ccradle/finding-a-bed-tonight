package org.fabt.reservation.domain;

import java.time.Instant;
import java.util.UUID;

public class Reservation {

    private UUID id;
    private UUID shelterId;
    private UUID tenantId;
    private String populationType;
    private UUID userId;
    private ReservationStatus status;
    private Instant expiresAt;
    private Instant confirmedAt;
    private Instant cancelledAt;
    private Instant createdAt;
    private String notes;

    public Reservation() {
    }

    public Reservation(UUID shelterId, UUID tenantId, String populationType,
                       UUID userId, Instant expiresAt, String notes) {
        this.shelterId = shelterId;
        this.tenantId = tenantId;
        this.populationType = populationType;
        this.userId = userId;
        this.status = ReservationStatus.HELD;
        this.expiresAt = expiresAt;
        this.notes = notes;
    }

    public boolean isHeld() {
        return status == ReservationStatus.HELD;
    }

    public long remainingSeconds() {
        if (expiresAt == null) return 0;
        long remaining = expiresAt.getEpochSecond() - Instant.now().getEpochSecond();
        return Math.max(0, remaining);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getShelterId() { return shelterId; }
    public void setShelterId(UUID shelterId) { this.shelterId = shelterId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public String getPopulationType() { return populationType; }
    public void setPopulationType(String populationType) { this.populationType = populationType; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public ReservationStatus getStatus() { return status; }
    public void setStatus(ReservationStatus status) { this.status = status; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }

    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
