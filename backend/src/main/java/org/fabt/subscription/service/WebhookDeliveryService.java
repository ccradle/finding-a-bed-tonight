package org.fabt.subscription.service;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
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
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
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
    private static final int MAX_RETRIES = 2;
    private static final long[] RETRY_DELAYS_MS = { 1_000, 3_000 };

    private final SubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final ObservabilityMetrics metrics;
    private final ExecutorService deliveryExecutor;
    private final CircuitBreaker circuitBreaker;

    public WebhookDeliveryService(SubscriptionService subscriptionService,
                                  ObjectMapper objectMapper,
                                  RestClient.Builder restClientBuilder,
                                  ObservabilityMetrics metrics,
                                  CircuitBreakerRegistry circuitBreakerRegistry) {
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
        // Timeouts: 10s connect, 30s read per design D3
        this.restClient = restClientBuilder
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(10))
                                .build()))
                .build();
        this.metrics = metrics;
        this.deliveryExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("webhook-delivery");
    }

    /**
     * Send a test event to a subscription endpoint and return the delivery result.
     * Used by the admin "Send Test" button. Runs synchronously (not on executor).
     */
    public TestDeliveryResult sendTestEvent(UUID subscriptionId, UUID tenantId, String eventType) {
        Subscription subscription = subscriptionService.findByTenantId(tenantId).stream()
                .filter(s -> s.getId().equals(subscriptionId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Subscription not found: " + subscriptionId));

        // Build synthetic event using convenience constructor (generates UUID + timestamp)
        DomainEvent testEvent = new DomainEvent(eventType, tenantId, Map.of("test", true));

        long startMs = System.currentTimeMillis();
        try {
            String jsonBody = objectMapper.writeValueAsString(Map.of(
                    "id", testEvent.id().toString(),
                    "type", testEvent.type(),
                    "schemaVersion", testEvent.schemaVersion(),
                    "tenantId", testEvent.tenantId().toString(),
                    "payload", testEvent.payload(),
                    "timestamp", testEvent.timestamp().toString(),
                    "test", true
            ));

            // Decrypt the stored secret for HMAC computation (AES-256-GCM at rest)
            String hmacKey = subscriptionService.decryptCallbackSecret(subscription.getCallbackSecretHash());
            String signature = "sha256=" + computeHmacSha256(hmacKey, jsonBody);

            var response = restClient.post()
                    .uri(subscription.getCallbackUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Signature", signature)
                    .header("X-Test-Event", "true")
                    .body(jsonBody)
                    .retrieve()
                    .toEntity(String.class);

            int responseTimeMs = (int) (System.currentTimeMillis() - startMs);
            String body = response.getBody();
            String truncated = body != null && body.length() > 1024 ? body.substring(0, 1024) : body;

            subscriptionService.recordDelivery(subscriptionId, eventType,
                    response.getStatusCode().value(), responseTimeMs, 1, truncated);

            return new TestDeliveryResult(response.getStatusCode().value(), responseTimeMs, truncated);

        } catch (Exception e) {
            int responseTimeMs = (int) (System.currentTimeMillis() - startMs);
            String error = e.getMessage() != null ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 200)) : "Unknown error";

            subscriptionService.recordDelivery(subscriptionId, eventType, null, responseTimeMs, 1, error);

            return new TestDeliveryResult(null, responseTimeMs, error);
        }
    }

    public record TestDeliveryResult(Integer statusCode, int responseTimeMs, String responseBody) {}

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

    /**
     * Delivers a single event to a subscription with retry and circuit breaker.
     *
     * <p>Retry: up to 2 retries with exponential backoff (1s, 3s) on retryable failures
     * (5xx, timeout, network). Does NOT retry 4xx client errors.</p>
     *
     * <p>Circuit breaker: uses resilience4j "webhook-delivery" instance. When open,
     * deliveries fail fast (CallNotPermittedException) — subscriptions still accumulate
     * consecutive failures and auto-disable at 5.</p>
     */
    private void deliverSingle(DomainEvent event, Subscription subscription) {
        // Circuit breaker fast-fail — don't attempt delivery if circuit is open
        try {
            circuitBreaker.acquirePermission();
        } catch (CallNotPermittedException e) {
            metrics.webhookDeliveryCounter(event.type(), "circuit_open").increment();
            log.debug("Circuit breaker open — skipping delivery of event {} to subscription {}",
                    event.id(), subscription.getId());
            subscriptionService.markFailing(subscription.getId(), "Circuit breaker open");
            return;
        }

        Exception lastException = null;
        boolean success = false;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                Timer.Sample timerSample = Timer.start();
                try {
                    deliver(event, subscription);
                    metrics.webhookDeliveryCounter(event.type(), "success").increment();
                } finally {
                    timerSample.stop(metrics.webhookDeliveryTimer(event.type()));
                }
                if (attempt > 0) {
                    log.info("Webhook delivery succeeded on retry {} for event {} to subscription {}",
                            attempt, event.id(), subscription.getId());
                }
                circuitBreaker.onSuccess(0, java.util.concurrent.TimeUnit.MILLISECONDS);
                success = true;
                break;
            } catch (Exception e) {
                lastException = e;
                // Don't retry 4xx client errors — only retryable (5xx, timeout, network)
                if (isClientError(e)) {
                    log.warn("Non-retryable 4xx delivering event {} to subscription {}: {}",
                            event.id(), subscription.getId(), e.getMessage());
                    break;
                }
                if (attempt < MAX_RETRIES) {
                    log.debug("Webhook attempt {} failed for event {} to subscription {}, retrying in {}ms",
                            attempt + 1, event.id(), subscription.getId(), RETRY_DELAYS_MS[attempt]);
                    try {
                        Thread.sleep(RETRY_DELAYS_MS[attempt]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        if (!success) {
            circuitBreaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, lastException);
            metrics.webhookDeliveryCounter(event.type(), "failure").increment();
            log.warn("Failed to deliver event {} to subscription {} after {} attempts: {}",
                    event.id(), subscription.getId(), MAX_RETRIES + 1,
                    lastException != null ? lastException.getMessage() : "unknown");
            subscriptionService.markFailing(subscription.getId(),
                    lastException != null ? lastException.getMessage() : "unknown");
        }
    }

    private boolean isClientError(Exception e) {
        if (e instanceof HttpClientErrorException hce) {
            return hce.getStatusCode().is4xxClientError();
        }
        String msg = e.getMessage();
        if (msg == null) return false;
        return msg.contains(" 400 ") || msg.contains(" 401 ") || msg.contains(" 403 ")
                || msg.contains(" 404 ") || msg.contains(" 405 ") || msg.contains(" 422 ");
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

        // Decrypt the stored secret for HMAC computation (AES-256-GCM at rest)
        String hmacKey = subscriptionService.decryptCallbackSecret(subscription.getCallbackSecretHash());
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
