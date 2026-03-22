package org.fabt.observability;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for custom Micrometer metrics exposed via /actuator/prometheus.
 *
 * CRITICAL: @AutoConfigureObservability is required — without it, Spring Boot test
 * auto-config disables PrometheusMeterRegistry and the endpoint returns 404.
 * (Portfolio Lesson 40)
 *
 * The /actuator/prometheus endpoint is intentionally behind authentication
 * (anyRequest().authenticated()) — FABT handles DV shelter data and must not
 * expose business metrics publicly. All requests use auth headers.
 */
@AutoConfigureObservability
class MetricsIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        authHelper.setupOutreachWorkerUser();
    }

    private ResponseEntity<String> getPrometheus() {
        HttpHeaders headers = authHelper.adminHeaders();
        return restTemplate.exchange("/actuator/prometheus", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    @Test
    void prometheusEndpoint_requiresAuthentication() {
        // Unauthenticated request should be rejected
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(),
                "Prometheus endpoint must require authentication — DV shelter data security");
    }

    @Test
    void prometheusEndpoint_exposesJvmMetrics() {
        // Portfolio Lesson 23: Use jvm_memory_used_bytes for smoke tests (always present at startup).
        // Custom counters only appear after first increment (lazy registration).
        ResponseEntity<String> response = getPrometheus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("jvm_memory_used_bytes"),
                "Prometheus endpoint should expose JVM memory metrics at startup");
    }

    @Test
    void prometheusEndpoint_exposesGauges() {
        // Gauges are registered eagerly in ObservabilityMetrics constructor
        ResponseEntity<String> response = getPrometheus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody();
        assertNotNull(body);
        assertTrue(body.contains("fabt_surge_active"), "Should expose surge active gauge");
        assertTrue(body.contains("fabt_shelter_stale_count"), "Should expose stale shelter gauge");
        assertTrue(body.contains("fabt_dv_canary_pass"), "Should expose DV canary gauge");
    }

    @Test
    void prometheusEndpoint_exposesCustomCounterAfterBedSearch() {
        // Trigger a bed search to register the counter (Portfolio Lesson 23: lazy registration)
        HttpHeaders headers = authHelper.outreachWorkerHeaders();
        String searchBody = """
                {"populationType": "individuals"}
                """;
        HttpEntity<String> request = new HttpEntity<>(searchBody, headers);
        restTemplate.exchange("/api/v1/queries/beds", HttpMethod.POST, request, String.class);

        // Now check Prometheus endpoint for the counter
        ResponseEntity<String> response = getPrometheus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody();
        assertNotNull(body);
        assertTrue(body.contains("fabt_bed_search_count"),
                "Prometheus should expose bed search counter after triggering a search");
    }

    @Test
    void prometheusEndpoint_exposesResilience4jMetrics() {
        ResponseEntity<String> response = getPrometheus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody();
        assertNotNull(body);
        // Resilience4J circuit breaker metrics should be present via resilience4j-micrometer bridge
        assertTrue(body.contains("resilience4j_circuitbreaker"),
                "Prometheus should expose Resilience4J circuit breaker metrics");
    }
}
