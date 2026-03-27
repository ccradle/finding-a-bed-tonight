package org.fabt.subscription.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import org.fabt.observability.ObservabilityMetrics;
import org.fabt.shared.event.DomainEvent;
import org.fabt.shared.web.TenantContext;
import org.fabt.subscription.domain.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Delivers domain events to webhook subscribers concurrently on virtual threads.
 *
 * The @EventListener returns immediately after submitting deliveries to the executor.
 * Each subscription delivery runs on its own virtual thread — a slow subscriber
 * does not block delivery to others or the event publisher.
 */
@Service
public class WebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final ObservabilityMetrics metrics;
    private final ExecutorService deliveryExecutor;

    public WebhookDeliveryService(SubscriptionService subscriptionService,
                                  ObjectMapper objectMapper,
                                  RestClient.Builder restClientBuilder,
                                  ObservabilityMetrics metrics) {
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder.build();
        this.metrics = metrics;
        this.deliveryExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @EventListener
    public void onDomainEvent(DomainEvent event) {
        UUID tenantId = TenantContext.getTenantId();
        boolean dvAccess = TenantContext.getDvAccess();

        List<Subscription> subscriptions = subscriptionService.findActiveByEventType(event.type());

        for (Subscription subscription : subscriptions) {
            if (!matchesFilter(event, subscription)) {
                continue;
            }
            deliveryExecutor.submit(() ->
                TenantContext.runWithContext(tenantId, dvAccess, () ->
                    deliverSingle(event, subscription)
                )
            );
        }
    }

    private void deliverSingle(DomainEvent event, Subscription subscription) {
        try {
            Timer.Sample timerSample = Timer.start();
            try {
                deliver(event, subscription);
                metrics.webhookDeliveryCounter(event.type(), "success").increment();
            } finally {
                timerSample.stop(metrics.webhookDeliveryTimer(event.type()));
            }
        } catch (Exception e) {
            metrics.webhookDeliveryCounter(event.type(), "failure").increment();
            log.warn("Failed to deliver event {} to subscription {}: {}",
                    event.id(), subscription.getId(), e.getMessage());
            subscriptionService.markFailing(subscription.getId(), e.getMessage());
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

            if (filterMap.containsKey("population_types")) {
                Object filterTypes = filterMap.get("population_types");
                Object payloadType = event.payload() != null ? event.payload().get("population_type") : null;
                if (filterTypes instanceof List<?> types && payloadType != null) {
                    return types.contains(payloadType.toString());
                }
                return false;
            }

            return true;
        } catch (JacksonException e) {
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
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to serialize event to JSON", e);
        }

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
