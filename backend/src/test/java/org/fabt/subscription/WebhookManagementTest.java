package org.fabt.subscription;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.subscription.service.SubscriptionService;
import org.fabt.subscription.service.WebhookResponseRedactor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for webhook management: pause/resume, state guards,
 * delivery log, auto-disable, redaction.
 */
@DisplayName("Webhook Management")
class WebhookManagementTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID subscriptionId;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        authHelper.setupOutreachWorkerUser();

        // Create a subscription for testing
        HttpHeaders headers = authHelper.adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                "/api/v1/subscriptions", HttpMethod.POST,
                new HttpEntity<>("""
                        {"eventType":"availability.updated","filter":{},"callbackUrl":"https://example.com/webhook","callbackSecret":"test-secret-abc"}
                        """, headers),
                new ParameterizedTypeReference<>() {});
        subscriptionId = UUID.fromString((String) resp.getBody().get("id"));
    }

    // T-24: Pause subscription
    @Test
    @DisplayName("PATCH status to PAUSED succeeds on ACTIVE subscription")
    void pauseActiveSubscription() {
        HttpHeaders headers = authHelper.adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                "/api/v1/subscriptions/" + subscriptionId + "/status", HttpMethod.PATCH,
                new HttpEntity<>("{\"status\":\"PAUSED\"}", headers),
                new ParameterizedTypeReference<>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("status")).isEqualTo("PAUSED");
    }

    // T-24a: Pause non-existent → 404
    @Test
    @DisplayName("PATCH status on non-existent subscription returns 404")
    void pauseNonExistent_returns404() {
        HttpHeaders headers = authHelper.adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/subscriptions/" + UUID.randomUUID() + "/status", HttpMethod.PATCH,
                new HttpEntity<>("{\"status\":\"PAUSED\"}", headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // T-24c: Invalid status value → 400
    @Test
    @DisplayName("PATCH with invalid status value returns 400")
    void invalidStatusValue_returns400() {
        HttpHeaders headers = authHelper.adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/subscriptions/" + subscriptionId + "/status", HttpMethod.PATCH,
                new HttpEntity<>("{\"status\":\"FAILING\"}", headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // T-24d: Resume from PAUSED → ACTIVE
    @Test
    @DisplayName("PATCH PAUSED → ACTIVE resumes subscription")
    void resumePausedSubscription() {
        HttpHeaders headers = authHelper.adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Pause first
        restTemplate.exchange("/api/v1/subscriptions/" + subscriptionId + "/status", HttpMethod.PATCH,
                new HttpEntity<>("{\"status\":\"PAUSED\"}", headers), String.class);
        // Resume
        ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                "/api/v1/subscriptions/" + subscriptionId + "/status", HttpMethod.PATCH,
                new HttpEntity<>("{\"status\":\"ACTIVE\"}", headers),
                new ParameterizedTypeReference<>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("status")).isEqualTo("ACTIVE");
    }

    // T-24e: CANCELLED subscription → 409
    @Test
    @DisplayName("PATCH on CANCELLED subscription returns 409")
    void patchCancelledSubscription_returns409() {
        HttpHeaders headers = authHelper.adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Cancel (soft delete)
        restTemplate.exchange("/api/v1/subscriptions/" + subscriptionId, HttpMethod.DELETE,
                new HttpEntity<>(headers), String.class);
        // Try to pause
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/subscriptions/" + subscriptionId + "/status", HttpMethod.PATCH,
                new HttpEntity<>("{\"status\":\"PAUSED\"}", headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // T-24f: PAUSED on DEACTIVATED → 409
    @Test
    @DisplayName("PATCH PAUSED on DEACTIVATED subscription returns 409 (re-enable first)")
    void pauseDeactivatedSubscription_returns409() {
        // Deactivate via service (simulating 5 failures)
        subscriptionService.deactivate(subscriptionId);

        HttpHeaders headers = authHelper.adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/subscriptions/" + subscriptionId + "/status", HttpMethod.PATCH,
                new HttpEntity<>("{\"status\":\"PAUSED\"}", headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // T-26: 5 consecutive failures → DEACTIVATED
    @Test
    @DisplayName("5 consecutive failures auto-disables subscription to DEACTIVATED")
    void autoDisableAfter5Failures() {
        for (int i = 0; i < 5; i++) {
            subscriptionService.recordDelivery(subscriptionId, "availability.updated", 500, 100, i + 1, "Server Error");
        }
        // Check status in DB
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM subscription WHERE id = ?", String.class, subscriptionId);
        assertThat(status).isEqualTo("DEACTIVATED");
    }

    // T-26a: Re-enable from DEACTIVATED resets counter
    @Test
    @DisplayName("Re-enable from DEACTIVATED resets consecutive_failures to 0")
    void reEnableResetsFailureCounter() {
        // Auto-disable
        for (int i = 0; i < 5; i++) {
            subscriptionService.recordDelivery(subscriptionId, "availability.updated", 500, 100, i + 1, "Error");
        }
        // Re-enable
        HttpHeaders headers = authHelper.adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.exchange("/api/v1/subscriptions/" + subscriptionId + "/status", HttpMethod.PATCH,
                new HttpEntity<>("{\"status\":\"ACTIVE\"}", headers), String.class);

        Integer failures = jdbcTemplate.queryForObject(
                "SELECT consecutive_failures FROM subscription WHERE id = ?", Integer.class, subscriptionId);
        assertThat(failures).isEqualTo(0);
    }

    // T-26b: Successful delivery resets counter
    @Test
    @DisplayName("Successful delivery after 3 failures resets counter to 0")
    void successResetsFailureCounter() {
        // 3 failures
        for (int i = 0; i < 3; i++) {
            subscriptionService.recordDelivery(subscriptionId, "availability.updated", 500, 100, i + 1, "Error");
        }
        // 1 success
        subscriptionService.recordDelivery(subscriptionId, "availability.updated", 200, 50, 4, "OK");

        Integer failures = jdbcTemplate.queryForObject(
                "SELECT consecutive_failures FROM subscription WHERE id = ?", Integer.class, subscriptionId);
        assertThat(failures).isEqualTo(0);
    }

    // T-28: Delivery log persisted
    @Test
    @DisplayName("recordDelivery persists entry in webhook_delivery_log")
    void deliveryLogPersisted() {
        subscriptionService.recordDelivery(subscriptionId, "availability.updated", 200, 42, 1, "OK");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM webhook_delivery_log WHERE subscription_id = ?",
                Integer.class, subscriptionId);
        assertThat(count).isEqualTo(1);
    }

    // T-28a: Response body truncated to 1KB
    @Test
    @DisplayName("Delivery log response body truncated to 1KB")
    void deliveryLogTruncated() {
        String longBody = "x".repeat(2048);
        subscriptionService.recordDelivery(subscriptionId, "availability.updated", 200, 50, 1, longBody);

        String stored = jdbcTemplate.queryForObject(
                "SELECT response_body FROM webhook_delivery_log WHERE subscription_id = ? ORDER BY attempted_at DESC LIMIT 1",
                String.class, subscriptionId);
        assertThat(stored.length()).isLessThanOrEqualTo(1024);
    }

    // T-28b: Bearer token redacted
    @Test
    @DisplayName("Bearer token in response body is redacted")
    void bearerTokenRedacted() {
        String result = WebhookResponseRedactor.redact("Error: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.payload.sig");
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("eyJhbG");
    }

    // T-28c: Email redacted
    @Test
    @DisplayName("Email in response body is redacted")
    void emailRedacted() {
        String result = WebhookResponseRedactor.redact("Contact admin@shelter.org for support");
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("admin@shelter.org");
    }

    // T-13: GET deliveries returns last 20
    @Test
    @DisplayName("GET deliveries returns recent delivery log entries")
    void getDeliveries() {
        subscriptionService.recordDelivery(subscriptionId, "availability.updated", 200, 50, 1, "OK");
        subscriptionService.recordDelivery(subscriptionId, "availability.updated", 500, 100, 2, "Error");

        HttpHeaders headers = authHelper.adminHeaders();
        ResponseEntity<List<Map<String, Object>>> resp = restTemplate.exchange(
                "/api/v1/subscriptions/" + subscriptionId + "/deliveries", HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(2);
    }
}
