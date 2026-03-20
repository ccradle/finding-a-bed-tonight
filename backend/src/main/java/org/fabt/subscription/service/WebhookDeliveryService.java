package org.fabt.subscription.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fabt.shared.event.DomainEvent;
import org.fabt.subscription.domain.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class WebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public WebhookDeliveryService(SubscriptionService subscriptionService,
                                  ObjectMapper objectMapper,
                                  RestClient.Builder restClientBuilder) {
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder.build();
    }

    @EventListener
    public void onDomainEvent(DomainEvent event) {
        deliverEvent(event);
    }

    public void deliverEvent(DomainEvent event) {
        List<Subscription> subscriptions = subscriptionService.findActiveByEventType(event.type());

        for (Subscription subscription : subscriptions) {
            try {
                if (!matchesFilter(event, subscription)) {
                    continue;
                }
                deliver(event, subscription);
            } catch (Exception e) {
                log.warn("Failed to deliver event {} to subscription {}: {}",
                        event.id(), subscription.getId(), e.getMessage());
                // TODO: retry with exponential backoff schedule: 1m, 5m, 30m, 2h
                subscriptionService.markFailing(subscription.getId(), e.getMessage());
            }
        }
    }

    private boolean matchesFilter(DomainEvent event, Subscription subscription) {
        String filterJson = subscription.getFilter() != null ? subscription.getFilter().value() : "{}";
        if ("{}".equals(filterJson) || filterJson == null || filterJson.isBlank()) {
            return true;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> filterMap = objectMapper.readValue(filterJson, Map.class);

            // Basic filter matching: if filter has "population_types", check if event payload
            // contains a matching type
            if (filterMap.containsKey("population_types")) {
                Object filterTypes = filterMap.get("population_types");
                Object payloadType = event.payload() != null ? event.payload().get("population_type") : null;
                if (filterTypes instanceof List<?> types && payloadType != null) {
                    return types.contains(payloadType.toString());
                }
                return false;
            }

            // No recognized filter keys — pass through
            return true;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse subscription filter for subscription {}: {}",
                    subscription.getId(), e.getMessage());
            return true;
        }
    }

    private void deliver(DomainEvent event, Subscription subscription) {
        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(Map.of(
                    "id", event.id().toString(),
                    "type", event.type(),
                    "schemaVersion", event.schemaVersion(),
                    "tenantId", event.tenantId().toString(),
                    "payload", event.payload(),
                    "timestamp", event.timestamp().toString()
            ));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event to JSON", e);
        }

        // MVP: callbackSecretHash field actually stores the SHA-256 hash of the secret.
        // For HMAC we need the original secret. Since we hashed it, we cannot recover it.
        // For MVP, we use the stored hash as the HMAC key. A future migration will store
        // the secret encrypted instead of hashed, allowing proper HMAC computation.
        // TODO: switch to encrypted secret storage so HMAC uses the original secret
        String hmacKey = subscription.getCallbackSecretHash();
        String signature = "sha256=" + computeHmacSha256(hmacKey, jsonBody);

        log.debug("Delivering event {} to {} for subscription {}",
                event.type(), subscription.getCallbackUrl(), subscription.getId());

        try {
            restClient.post()
                    .uri(subscription.getCallbackUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Signature", signature)
                    .body(jsonBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::is2xxSuccessful, (req, res) -> {
                        // Success — do nothing
                    })
                    .toBodilessEntity();

            log.debug("Successfully delivered event {} to subscription {}",
                    event.id(), subscription.getId());
        } catch (Exception e) {
            // Check if the error message indicates a 410 Gone response
            if (e.getMessage() != null && e.getMessage().contains("410")) {
                log.info("Callback returned 410 Gone for subscription {}, deactivating permanently",
                        subscription.getId());
                subscriptionService.deactivate(subscription.getId());
            } else {
                throw e;
            }
        }
    }

    private String computeHmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hmacBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC-SHA256 signature", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
