package org.fabt.subscription.domain;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("webhook_delivery_log")
public class WebhookDeliveryLog {

    @Id
    private UUID id;
    private UUID subscriptionId;
    private String eventType;
    private Integer statusCode;
    private Integer responseTimeMs;
    private Instant attemptedAt;
    private int attemptNumber;
    private String responseBody;

    public WebhookDeliveryLog() {
    }

    public WebhookDeliveryLog(UUID subscriptionId, String eventType, Integer statusCode,
                               Integer responseTimeMs, int attemptNumber, String responseBody) {
        this.subscriptionId = subscriptionId;
        this.eventType = eventType;
        this.statusCode = statusCode;
        this.responseTimeMs = responseTimeMs;
        this.attemptedAt = Instant.now();
        this.attemptNumber = attemptNumber;
        // Truncate response body to 1KB per design D4
        this.responseBody = responseBody != null && responseBody.length() > 1024
                ? responseBody.substring(0, 1024)
                : responseBody;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(UUID subscriptionId) { this.subscriptionId = subscriptionId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public Integer getStatusCode() { return statusCode; }
    public void setStatusCode(Integer statusCode) { this.statusCode = statusCode; }
    public Integer getResponseTimeMs() { return responseTimeMs; }
    public void setResponseTimeMs(Integer responseTimeMs) { this.responseTimeMs = responseTimeMs; }
    public Instant getAttemptedAt() { return attemptedAt; }
    public void setAttemptedAt(Instant attemptedAt) { this.attemptedAt = attemptedAt; }
    public int getAttemptNumber() { return attemptNumber; }
    public void setAttemptNumber(int attemptNumber) { this.attemptNumber = attemptNumber; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
}
