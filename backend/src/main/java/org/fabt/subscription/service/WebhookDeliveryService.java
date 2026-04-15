package org.fabt.subscription.service;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.fabt.observability.ObservabilityMetrics;
import org.fabt.shared.event.DomainEvent;
import org.fabt.shared.security.SafeOutboundUrlValidator;
import org.fabt.shared.web.TenantContext;
import org.fabt.subscription.domain.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    private final SafeOutboundUrlValidator urlValidator;
    private final Counter ssrfBlockedCounter;

    public WebhookDeliveryService(SubscriptionService subscriptionService,
                                  ObjectMapper objectMapper,
                                  RestClient.Builder restClientBuilder,
                                  ObservabilityMetrics metrics,
                                  CircuitBreakerRegistry circuitBreakerRegistry,
                                  RetryRegistry retryRegistry,
                                  SafeOutboundUrlValidator urlValidator,
                                  MeterRegistry meterRegistry,
                                  @Value("${fabt.webhook.connect-timeout-seconds:10}") int connectTimeoutSeconds,
                                  @Value("${fabt.webhook.read-timeout-seconds:30}") int readTimeoutSeconds) {
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
        this.urlValidator = urlValidator;
        this.ssrfBlockedCounter = Counter.builder("fabt.webhook.delivery.failures")
                .tag("reason", "ssrf_blocked")
                .description("Webhook deliveries blocked at dial time by SafeOutboundUrlValidator "
                        + "(possible DNS rebinding or misconfigured callback URL).")
                .register(meterRegistry);
        // Timeouts per design D3: connect (default 10s) protects against unreachable
        // endpoints, read (default 30s) protects against hanging endpoints. The read
        // timeout MUST be set on the request factory — JDK HttpClient has no per-client
        // read timeout, so without setReadTimeout() a slow endpoint blocks the virtual
        // thread indefinitely (Marcus Webb finding, 2026-04-09).
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                        .build());
        requestFactory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        this.restClient = restClientBuilder
                .requestFactory(requestFactory)
                .build();
        this.metrics = metrics;
        this.deliveryExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("webhook-delivery");
        this.retry = retryRegistry.retry("webhook-delivery");
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

            // D12 (cross-tenant-isolation-audit Phase 2.14): dial-time SSRF
            // re-validation, designed to mitigate DNS rebinding — an
            // attacker's webhook URL resolves public at creation-time then
            // rebinds to 127.0.0.1 / 169.254.169.254 between registration
            // and delivery. Creation-time validation alone misses this.
            // We re-resolve immediately before send; IllegalArgumentException
            // on block.
            try {
                urlValidator.validateForDial(subscription.getCallbackUrl());
            } catch (IllegalArgumentException ssrf) {
                ssrfBlockedCounter.increment();
                throw ssrf;
            }

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

            subscriptionService.recordDeliveryInternal(subscriptionId, eventType,
                    response.getStatusCode().value(), responseTimeMs, 1, truncated);

            return new TestDeliveryResult(response.getStatusCode().value(), responseTimeMs, truncated);

        } catch (Exception e) {
            int responseTimeMs = (int) (System.currentTimeMillis() - startMs);
            String error = e.getMessage() != null ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 200)) : "Unknown error";

            subscriptionService.recordDeliveryInternal(subscriptionId, eventType, null, responseTimeMs, 1, error);

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
     * Delivers a single event to a subscription with resilience4j retry + circuit breaker.
     *
     * <p>Retry: resilience4j "webhook-delivery" instance (3 attempts, 1s + 3s exponential backoff).
     * Only retries IOException, SocketTimeoutException, ResourceAccessException,
     * HttpServerErrorException. Does NOT retry 4xx client errors.</p>
     *
     * <p>Circuit breaker: resilience4j "webhook-delivery" instance (sliding window 10,
     * 50% failure rate, 30s open). When open, deliveries fail fast — subscriptions
     * still accumulate consecutive failures and auto-disable at 5.</p>
     */
    private void deliverSingle(DomainEvent event, Subscription subscription) {
        Runnable deliveryWithRetry = Retry.decorateRunnable(retry, () -> {
            deliver(event, subscription);
        });

        Runnable deliveryWithCircuitBreakerAndRetry = CircuitBreaker.decorateRunnable(
                circuitBreaker, deliveryWithRetry);

        Timer.Sample timerSample = Timer.start();
        try {
            deliveryWithCircuitBreakerAndRetry.run();
            metrics.webhookDeliveryCounter(event.type(), "success").increment();
        } catch (CallNotPermittedException e) {
            metrics.webhookDeliveryCounter(event.type(), "circuit_open").increment();
            log.debug("Circuit breaker open — skipping event {} to subscription {}",
                    event.id(), subscription.getId());
            subscriptionService.markFailingInternal(subscription.getId(), "Circuit breaker open");
        } catch (RestClientResponseException e) {
            // 4xx errors propagate through retry (not in retry-exceptions list)
            metrics.webhookDeliveryCounter(event.type(), "failure").increment();
            log.warn("Failed to deliver event {} to subscription {}: {} {}",
                    event.id(), subscription.getId(), e.getStatusCode(), e.getMessage());
            subscriptionService.markFailingInternal(subscription.getId(), e.getMessage());
        } catch (Exception e) {
            // All retries exhausted for retryable errors
            metrics.webhookDeliveryCounter(event.type(), "failure").increment();
            log.warn("Failed to deliver event {} to subscription {} after retries: {}",
                    event.id(), subscription.getId(), e.getMessage());
            subscriptionService.markFailingInternal(subscription.getId(), e.getMessage());
        } finally {
            timerSample.stop(metrics.webhookDeliveryTimer(event.type()));
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

        // Decrypt the stored secret for HMAC computation (AES-256-GCM at rest)
        String hmacKey = subscriptionService.decryptCallbackSecret(subscription.getCallbackSecretHash());
        String signature = "sha256=" + computeHmacSha256(hmacKey, jsonBody);

        log.debug("Delivering event {} to {} for subscription {}",
                event.type(), subscription.getCallbackUrl(), subscription.getId());

        // D12: dial-time SSRF re-validation before production event delivery.
        // See sendTestEvent() comment for rationale; designed to mitigate
        // the DNS rebinding bypass class.
        try {
            urlValidator.validateForDial(subscription.getCallbackUrl());
        } catch (IllegalArgumentException ssrf) {
            ssrfBlockedCounter.increment();
            throw ssrf;
        }

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
                subscriptionService.deactivateInternal(subscription.getId());
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
