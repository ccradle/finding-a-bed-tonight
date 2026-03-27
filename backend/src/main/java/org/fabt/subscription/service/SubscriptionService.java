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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final ObjectMapper objectMapper;

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                               ObjectMapper objectMapper) {
        this.subscriptionRepository = subscriptionRepository;
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

    @Transactional(readOnly = true)
    public List<Subscription> findActiveByEventType(String eventType) {
        return subscriptionRepository.findActiveByEventType(eventType);
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
