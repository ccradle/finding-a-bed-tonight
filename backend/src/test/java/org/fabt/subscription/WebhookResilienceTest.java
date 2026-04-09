package org.fabt.subscription;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.fabt.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies resilience4j retry + circuit breaker configuration for webhook delivery.
 * These tests validate the wiring (beans exist, config correct) rather than
 * end-to-end HTTP delivery (which would require a mock HTTP server).
 */
class WebhookResilienceTest extends BaseIntegrationTest {

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RetryRegistry retryRegistry;

    @Test
    @DisplayName("webhook-delivery circuit breaker is configured")
    void circuitBreaker_configured() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("webhook-delivery");
        assertThat(cb).isNotNull();
        assertThat(cb.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(10);
        assertThat(cb.getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(50f);
        assertThat(cb.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState()).isNotNull();
    }

    @Test
    @DisplayName("webhook-delivery retry is configured with 3 max attempts")
    void retry_configured() {
        Retry retry = retryRegistry.retry("webhook-delivery");
        assertThat(retry).isNotNull();
        assertThat(retry.getRetryConfig().getMaxAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("webhook-delivery retry only retries on configured exceptions")
    void retry_onlyRetriableExceptions() {
        Retry retry = retryRegistry.retry("webhook-delivery");
        // IOException is retryable
        assertThat(retry.getRetryConfig().getExceptionPredicate().test(
                new java.io.IOException("Connection refused"))).isTrue();
        // HttpServerErrorException (5xx) is retryable
        assertThat(retry.getRetryConfig().getExceptionPredicate().test(
                new org.springframework.web.client.HttpServerErrorException(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR))).isTrue();
        // IllegalArgumentException is NOT retryable (not in retry-exceptions list)
        assertThat(retry.getRetryConfig().getExceptionPredicate().test(
                new IllegalArgumentException("bad input"))).isFalse();
        // RestClientResponseException 4xx is NOT retryable
        assertThat(retry.getRetryConfig().getExceptionPredicate().test(
                new org.springframework.web.client.HttpClientErrorException(
                        org.springframework.http.HttpStatus.BAD_REQUEST))).isFalse();
    }

    @Test
    @DisplayName("webhook-delivery circuit breaker has correct minimumNumberOfCalls (not default 100)")
    void circuitBreaker_minimumNumberOfCalls_configured() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("webhook-delivery");
        // Default is 100 — we set 5 in application.yml to trip faster for webhook failures
        assertThat(cb.getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(5);
    }

    @Test
    @DisplayName("Circuit breaker transitions to OPEN after minimumNumberOfCalls failures")
    void circuitBreaker_opensAfterMinimumFailures() {
        // Use the real webhook-delivery config (minimumNumberOfCalls=5, failureRateThreshold=50%)
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("webhook-delivery");
        int minCalls = cb.getCircuitBreakerConfig().getMinimumNumberOfCalls();

        // Use a fresh instance with the same config to avoid polluting the shared one
        CircuitBreaker testCb = CircuitBreaker.of("webhook-test-" + System.nanoTime(),
                cb.getCircuitBreakerConfig());

        // Send minCalls failures — all failures = 100% failure rate > 50% threshold
        for (int i = 0; i < minCalls; i++) {
            testCb.onError(100, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new RuntimeException("simulated failure"));
        }
        assertThat(testCb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("Circuit breaker starts CLOSED")
    void circuitBreaker_startsClosed() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("webhook-delivery");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
