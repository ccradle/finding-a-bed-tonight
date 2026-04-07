package org.fabt.subscription.domain;

import java.time.Instant;
import java.util.UUID;

import org.fabt.shared.config.JsonString;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("subscription")
public class Subscription {

    @Id
    private UUID id;
    private UUID tenantId;
    private String eventType;
    private JsonString filter;
    private String callbackUrl;
    private String callbackSecretHash;
    private String status;
    private Instant expiresAt;
    private String lastError;
    private int consecutiveFailures;
    private Instant createdAt;

    public Subscription() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public JsonString getFilter() {
        return filter;
    }

    public void setFilter(JsonString filter) {
        this.filter = filter;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public void setCallbackUrl(String callbackUrl) {
        this.callbackUrl = callbackUrl;
    }

    public String getCallbackSecretHash() {
        return callbackSecretHash;
    }

    public void setCallbackSecretHash(String callbackSecretHash) {
        this.callbackSecretHash = callbackSecretHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }
}
