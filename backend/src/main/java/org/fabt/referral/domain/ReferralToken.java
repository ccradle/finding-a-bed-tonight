package org.fabt.referral.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * DV opaque referral token — privacy-preserving shelter referral.
 * Contains no structured client PII (designed to support VAWA 34 U.S.C. 12291(b)(2)).
 * Terminal tokens are hard-deleted within 24 hours.
 */
public class ReferralToken {

    private UUID id;
    private UUID shelterId;
    private UUID tenantId;
    private UUID referringUserId;
    private int householdSize;
    private String populationType;
    private String urgency;
    private String specialNeeds;
    private String callbackNumber;
    private String status;
    private Instant createdAt;
    private Instant respondedAt;
    private UUID respondedBy;
    private Instant expiresAt;
    private String rejectionReason;

    public ReferralToken() {}

    public ReferralToken(UUID shelterId, UUID tenantId, UUID referringUserId,
                         int householdSize, String populationType, String urgency,
                         String specialNeeds, String callbackNumber, Instant expiresAt) {
        this.shelterId = shelterId;
        this.tenantId = tenantId;
        this.referringUserId = referringUserId;
        this.householdSize = householdSize;
        this.populationType = populationType;
        this.urgency = urgency;
        this.specialNeeds = specialNeeds;
        this.callbackNumber = callbackNumber;
        this.status = "PENDING";
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    public boolean isPending() { return "PENDING".equals(status); }
    public boolean isExpired() { return expiresAt != null && Instant.now().isAfter(expiresAt); }

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getShelterId() { return shelterId; }
    public void setShelterId(UUID shelterId) { this.shelterId = shelterId; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getReferringUserId() { return referringUserId; }
    public void setReferringUserId(UUID referringUserId) { this.referringUserId = referringUserId; }
    public int getHouseholdSize() { return householdSize; }
    public void setHouseholdSize(int householdSize) { this.householdSize = householdSize; }
    public String getPopulationType() { return populationType; }
    public void setPopulationType(String populationType) { this.populationType = populationType; }
    public String getUrgency() { return urgency; }
    public void setUrgency(String urgency) { this.urgency = urgency; }
    public String getSpecialNeeds() { return specialNeeds; }
    public void setSpecialNeeds(String specialNeeds) { this.specialNeeds = specialNeeds; }
    public String getCallbackNumber() { return callbackNumber; }
    public void setCallbackNumber(String callbackNumber) { this.callbackNumber = callbackNumber; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getRespondedAt() { return respondedAt; }
    public void setRespondedAt(Instant respondedAt) { this.respondedAt = respondedAt; }
    public UUID getRespondedBy() { return respondedBy; }
    public void setRespondedBy(UUID respondedBy) { this.respondedBy = respondedBy; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
}
