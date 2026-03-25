package org.fabt.hmis.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit log entry for HMIS data transmissions.
 * Required by HMIS security standards.
 */
public class HmisAuditEntry {

    private UUID id;
    private UUID tenantId;
    private String vendorType;
    private Instant pushTimestamp;
    private int recordCount;
    private String status;
    private String errorMessage;
    private String payloadHash;

    public HmisAuditEntry() {}

    public HmisAuditEntry(UUID tenantId, String vendorType, int recordCount, String status,
                           String errorMessage, String payloadHash) {
        this.tenantId = tenantId;
        this.vendorType = vendorType;
        this.pushTimestamp = Instant.now();
        this.recordCount = recordCount;
        this.status = status;
        this.errorMessage = errorMessage;
        this.payloadHash = payloadHash;
    }

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public String getVendorType() { return vendorType; }
    public void setVendorType(String vendorType) { this.vendorType = vendorType; }
    public Instant getPushTimestamp() { return pushTimestamp; }
    public void setPushTimestamp(Instant pushTimestamp) { this.pushTimestamp = pushTimestamp; }
    public int getRecordCount() { return recordCount; }
    public void setRecordCount(int recordCount) { this.recordCount = recordCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
}
