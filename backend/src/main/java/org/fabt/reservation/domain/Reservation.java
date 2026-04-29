package org.fabt.reservation.domain;

import java.time.Instant;
import java.time.LocalDate;
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
    private String idempotencyKey;

    /**
     * Third-party navigator hold attribution (transitional-reentry-support
     * task 3.5, V93 columns). Plaintext at the entity layer — the
     * {@link org.fabt.reservation.repository.ReservationRepository} row
     * mapper decrypts {@code held_for_client_name_encrypted} into this
     * field on read; the insert path encrypts on write. Plaintext is
     * never persisted to disk.
     *
     * <p>Note: this differs from the {@code app_user.totpSecretEncrypted}
     * precedent (which holds the encrypted form at the entity layer and
     * decrypts on use). For hold attribution, every read of the
     * coordinator dashboard renders this name, so paying the decrypt cost
     * at the row-mapper layer is operationally cleaner than remembering
     * to decrypt at every callsite.
     *
     * <p>Nullable. May contain names + contact info of supervision officers
     * per design D4 (warroom Casey input). Spring Batch purges the
     * underlying ciphertext column 24h after reservation resolution.
     */
    private String heldForClientName;

    /**
     * Date of birth of the third-party client (transitional-reentry-support
     * task 3.5, V93 column). Plaintext at the entity layer; serialized as
     * ISO-8601 ({@code LocalDate.toString()}) before encryption to support
     * clean round-trip. Nullable.
     */
    private LocalDate heldForClientDob;

    /**
     * Free-text coordination notes from navigator to shelter coordinator
     * (transitional-reentry-support task 3.5, V93 column). Plaintext at the
     * entity layer; max 500 chars per UI / 1000 chars per server validation
     * (open question #1 resolution). NOT a permanent record — purged 24h
     * post-resolution per design D4. Nullable.
     */
    private String holdNotes;

    /** Transient flag — true when returned from an idempotent key match (not persisted). */
    private transient boolean idempotentMatch;

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

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public boolean isIdempotentMatch() { return idempotentMatch; }
    public void setIdempotentMatch(boolean idempotentMatch) { this.idempotentMatch = idempotentMatch; }

    public String getHeldForClientName() { return heldForClientName; }
    public void setHeldForClientName(String heldForClientName) { this.heldForClientName = heldForClientName; }

    public LocalDate getHeldForClientDob() { return heldForClientDob; }
    public void setHeldForClientDob(LocalDate heldForClientDob) { this.heldForClientDob = heldForClientDob; }

    public String getHoldNotes() { return holdNotes; }
    public void setHoldNotes(String holdNotes) { this.holdNotes = holdNotes; }
}
