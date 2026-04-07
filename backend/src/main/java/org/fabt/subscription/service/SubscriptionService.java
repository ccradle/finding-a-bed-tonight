package org.fabt.subscription.service;

import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.fabt.shared.config.JsonString;
import org.fabt.subscription.domain.Subscription;
import org.fabt.subscription.repository.SubscriptionRepository;
import org.fabt.subscription.repository.WebhookDeliveryLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryLogRepository deliveryLogRepository;
    private final ObjectMapper objectMapper;

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                               WebhookDeliveryLogRepository deliveryLogRepository,
                               ObjectMapper objectMapper) {
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryLogRepository = deliveryLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Subscription create(UUID tenantId, String eventType, Map<String, Object> filter,
                               String callbackUrl, String callbackSecret) {
        validateCallbackUrl(callbackUrl);

        // ID left null for INSERT (Lesson 64)
        Subscription subscription = new Subscription();
        subscription.setTenantId(tenantId);
        subscription.setEventType(eventType);
        subscription.setFilter(toJsonString(filter));
        subscription.setCallbackUrl(callbackUrl);
        // MVP: store secret as-is for HMAC computation (see WebhookDeliveryService).
        // Field name is callbackSecretHash but we store the SHA-256 hash for verification,
        // and need the original for HMAC. For MVP, store as-is.
        // TODO: encrypt the secret at rest instead of hashing (future migration will rename field)
        subscription.setCallbackSecretHash(hashSecret(callbackSecret));
        subscription.setStatus("ACTIVE");
        subscription.setExpiresAt(Instant.now().plus(365, ChronoUnit.DAYS));
        subscription.setCreatedAt(Instant.now());

        return subscriptionRepository.save(subscription);
    }

    @Transactional(readOnly = true)
    public List<Subscription> findByTenantId(UUID tenantId) {
        return subscriptionRepository.findByTenantId(tenantId);
    }

    @Transactional
    public void delete(UUID id) {
        Subscription subscription = subscriptionRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Subscription not found: " + id));
        subscription.setStatus("CANCELLED");
        subscriptionRepository.save(subscription);
    }

    @Transactional
    public void markFailing(UUID id, String error) {
        Subscription subscription = subscriptionRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Subscription not found: " + id));
        subscription.setStatus("FAILING");
        subscription.setLastError(error);
        subscriptionRepository.save(subscription);
    }

    @Transactional
    public void deactivate(UUID id) {
        Subscription subscription = subscriptionRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Subscription not found: " + id));
        subscription.setStatus("DEACTIVATED");
        subscriptionRepository.save(subscription);
    }

    /**
     * Admin-initiated status change. Only ACTIVE and PAUSED are valid admin-settable values.
     * Resetting to ACTIVE from DEACTIVATED or FAILING clears consecutive failure counter.
     */
    @Transactional
    public Subscription updateStatus(UUID id, String newStatus) {
        if (!"ACTIVE".equals(newStatus) && !"PAUSED".equals(newStatus)) {
            throw new IllegalArgumentException("Only ACTIVE and PAUSED are valid admin-settable status values");
        }

        Subscription subscription = subscriptionRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Subscription not found: " + id));

        if ("ACTIVE".equals(newStatus) &&
                ("DEACTIVATED".equals(subscription.getStatus()) || "FAILING".equals(subscription.getStatus()))) {
            subscription.setConsecutiveFailures(0);
            subscription.setLastError(null);
        }

        subscription.setStatus(newStatus);
        return subscriptionRepository.save(subscription);
    }

    @Transactional(readOnly = true)
    public List<Subscription> findActiveByEventType(String eventType) {
        return subscriptionRepository.findActiveByEventType(eventType);
    }

    /**
     * Record a delivery attempt in the delivery log.
     * Manages consecutive failure counter and auto-disable at 5 failures.
     */
    @Transactional
    public void recordDelivery(UUID subscriptionId, String eventType, Integer statusCode,
                                Integer responseTimeMs, int attemptNumber, String responseBody) {
        var logEntry = new org.fabt.subscription.domain.WebhookDeliveryLog(
                subscriptionId, eventType, statusCode, responseTimeMs, attemptNumber, responseBody);
        deliveryLogRepository.save(logEntry);

        Subscription subscription = subscriptionRepository.findById(subscriptionId).orElse(null);
        if (subscription == null) return;

        boolean success = statusCode != null && statusCode >= 200 && statusCode < 300;

        if (success) {
            if (subscription.getConsecutiveFailures() > 0) {
                subscription.setConsecutiveFailures(0);
                subscription.setLastError(null);
                if ("FAILING".equals(subscription.getStatus())) {
                    subscription.setStatus("ACTIVE");
                }
                subscriptionRepository.save(subscription);
            }
        } else {
            subscription.setConsecutiveFailures(subscription.getConsecutiveFailures() + 1);
            subscription.setLastError(responseBody != null ? responseBody.substring(0, Math.min(responseBody.length(), 200)) : "No response");
            if (subscription.getConsecutiveFailures() >= 5) {
                subscription.setStatus("DEACTIVATED");
                log.warn("Subscription {} auto-disabled after {} consecutive failures", subscriptionId, subscription.getConsecutiveFailures());
            } else if (!"FAILING".equals(subscription.getStatus())) {
                subscription.setStatus("FAILING");
            }
            subscriptionRepository.save(subscription);
        }
    }

    /**
     * Delete delivery logs older than 14 days. Runs daily.
     * NOTE: For multi-instance deployments, add ShedLock to prevent duplicate execution.
     */
    @Scheduled(fixedRate = 86_400_000) // daily
    @Transactional
    public void cleanupOldDeliveryLogs() {
        int deleted = deliveryLogRepository.deleteOlderThan14Days();
        if (deleted > 0) {
            log.info("Cleaned up {} delivery log entries older than 14 days", deleted);
        }
    }

    private void validateCallbackUrl(String callbackUrl) {
        try {
            URI.create(callbackUrl).toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid callback URL: " + callbackUrl);
        }
    }

    private String hashSecret(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private JsonString toJsonString(Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return JsonString.empty();
        }
        try {
            return JsonString.of(objectMapper.writeValueAsString(filter));
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Invalid filter: unable to serialize to JSON", e);
        }
    }
}
