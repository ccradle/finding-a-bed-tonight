package org.fabt.subscription.service;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.fabt.shared.config.JsonString;
import org.fabt.shared.web.TenantContext;
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
    private final org.fabt.shared.security.SecretEncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                               WebhookDeliveryLogRepository deliveryLogRepository,
                               org.fabt.shared.security.SecretEncryptionService encryptionService,
                               ObjectMapper objectMapper) {
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryLogRepository = deliveryLogRepository;
        this.encryptionService = encryptionService;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a new webhook subscription for the caller's tenant.
     *
     * <p>Design D11 (URL-path-sink class): {@code tenantId} is sourced from
     * {@link TenantContext#getTenantId()} internally. The service SHALL NOT
     * accept {@code tenantId} as a parameter — symmetric with
     * {@code TenantOAuth2ProviderService.create},
     * {@code ApiKeyService.create}, and {@code ShelterService.create}.
     */
    @Transactional
    public Subscription create(String eventType, Map<String, Object> filter,
                               String callbackUrl, String callbackSecret) {
        UUID tenantId = TenantContext.getTenantId();
        validateCallbackUrl(callbackUrl);

        // ID left null for INSERT (Lesson 64)
        Subscription subscription = new Subscription();
        subscription.setTenantId(tenantId);
        subscription.setEventType(eventType);
        subscription.setFilter(toJsonString(filter));
        subscription.setCallbackUrl(callbackUrl);
        // Encrypt the callback secret with AES-256-GCM for storage at rest.
        // Decrypted on delivery to compute HMAC-SHA256 signatures.
        // Field name is still callbackSecretHash (pre-existing column) — stores encrypted value.
        subscription.setCallbackSecretHash(encryptionService.encrypt(callbackSecret));
        subscription.setStatus("ACTIVE");
        subscription.setExpiresAt(Instant.now().plus(365, ChronoUnit.DAYS));
        subscription.setCreatedAt(Instant.now());

        return subscriptionRepository.save(subscription);
    }

    @Transactional(readOnly = true)
    public List<Subscription> findByTenantId(UUID tenantId) {
        return subscriptionRepository.findByTenantId(tenantId);
    }

    /**
     * Cancels a webhook subscription.
     *
     * <p>Tenant-scoped: the subscription MUST belong to the caller's tenant
     * (resolved from {@link TenantContext}). A cross-tenant id returns 404
     * via {@link NoSuchElementException} — not 403 — to avoid existence
     * disclosure (design D3). See {@link #findByIdOrThrow(UUID)} and the
     * {@code cross-tenant-isolation-audit} change (task 2.4.3).
     *
     * <p>Pre-fix, a CoC admin in Tenant A could DELETE a subscription
     * belonging to Tenant B — silent denial-of-service of Tenant B's
     * webhook-driven integrations.
     */
    @Transactional
    public void delete(UUID id) {
        Subscription subscription = findByIdOrThrow(id);
        subscription.setStatus("CANCELLED");
        subscriptionRepository.save(subscription);
    }

    /**
     * Tenant-scoped single-subscription lookup used by state-mutating paths
     * that originate at the HTTP boundary ({@link #delete}). Pulls the
     * caller's {@code tenantId} from {@link TenantContext} and delegates to
     * {@link SubscriptionRepository#findByIdAndTenantId(UUID, UUID)}. Throws
     * {@link NoSuchElementException} on empty — mapped to 404 by
     * {@code GlobalExceptionHandler}.
     *
     * <p>Not used by the internal webhook-delivery paths ({@link #markFailing},
     * {@link #deactivate}, {@link #recordDelivery}) — those are system-caller-
     * only and operate on subscription ids that were already tenant-scoped
     * upstream (by {@code findActiveByEventType} or similar). Phase 2.6
     * renames those methods to {@code *Internal} and restricts callers to
     * {@link org.fabt.subscription.service.WebhookDeliveryService} via
     * ArchUnit.
     */
    private Subscription findByIdOrThrow(UUID id) {
        return subscriptionRepository.findByIdAndTenantId(id, TenantContext.getTenantId())
                .orElseThrow(() -> new NoSuchElementException("Subscription not found: " + id));
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
    public Subscription updateStatus(UUID id, UUID tenantId, String newStatus) {
        if (!"ACTIVE".equals(newStatus) && !"PAUSED".equals(newStatus)) {
            throw new IllegalArgumentException("Only ACTIVE and PAUSED are valid admin-settable status values");
        }

        Subscription subscription = subscriptionRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Subscription not found: " + id));

        // Tenant isolation — return 404 to avoid confirming existence in another tenant
        if (!subscription.getTenantId().equals(tenantId)) {
            throw new NoSuchElementException("Subscription not found: " + id);
        }

        // CANCELLED is terminal — cannot be reactivated
        if ("CANCELLED".equals(subscription.getStatus())) {
            throw new IllegalStateException("Cannot modify a cancelled subscription");
        }

        // Only allow PAUSED from ACTIVE (not from DEACTIVATED/FAILING — re-enable to ACTIVE first)
        if ("PAUSED".equals(newStatus) && !"ACTIVE".equals(subscription.getStatus())) {
            throw new IllegalStateException("Can only pause an ACTIVE subscription. Current status: " + subscription.getStatus());
        }

        // Resetting to ACTIVE from DEACTIVATED or FAILING clears failure state
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
     * Return recent delivery log entries for a subscription, with tenant isolation.
     * Returns 404 (via NoSuchElementException) if subscription doesn't belong to tenant.
     */
    @Transactional(readOnly = true)
    public List<org.fabt.subscription.domain.WebhookDeliveryLog> findRecentDeliveries(UUID subscriptionId, UUID tenantId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new NoSuchElementException("Subscription not found: " + subscriptionId));
        if (!subscription.getTenantId().equals(tenantId)) {
            throw new NoSuchElementException("Subscription not found: " + subscriptionId);
        }
        return deliveryLogRepository.findRecentBySubscriptionId(subscriptionId);
    }

    /**
     * Record a delivery attempt in the delivery log.
     * Response body is redacted (secrets/PII masked) before persistence.
     * Manages consecutive failure counter and auto-disable at 5 failures.
     * NOTE: consecutiveFailures increment is not atomic (read-modify-write).
     * For lite profile (single instance) this is acceptable. For multi-instance,
     * use SQL atomic increment: UPDATE subscription SET consecutive_failures = consecutive_failures + 1
     */
    @Transactional
    public void recordDelivery(UUID subscriptionId, String eventType, Integer statusCode,
                                Integer responseTimeMs, int attemptNumber, String responseBody) {
        // Redact secrets/PII before persistence
        String redactedBody = WebhookResponseRedactor.redact(responseBody);

        var logEntry = new org.fabt.subscription.domain.WebhookDeliveryLog(
                subscriptionId, eventType, statusCode, responseTimeMs, attemptNumber, redactedBody);
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

    /**
     * Decrypt a stored webhook callback secret for HMAC computation.
     * Public so WebhookDeliveryService can call it.
     */
    public String decryptCallbackSecret(String encryptedSecret) {
        return encryptionService.decrypt(encryptedSecret);
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
