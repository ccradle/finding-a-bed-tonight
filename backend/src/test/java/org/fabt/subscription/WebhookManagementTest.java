package org.fabt.subscription;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.auth.repository.UserRepository;
import org.fabt.auth.service.JwtService;
import org.fabt.auth.service.PasswordService;
import org.fabt.subscription.service.SubscriptionService;
import org.fabt.subscription.service.WebhookResponseRedactor;
import org.fabt.tenant.service.TenantService;
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
    @Autowired private TenantService tenantService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordService passwordService;
    @Autowired private JwtService jwtService;

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
        subscriptionService.deactivateInternal(subscriptionId);

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
            subscriptionService.recordDeliveryInternal(subscriptionId, "availability.updated", 500, 100, i + 1, "Server Error");
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
            subscriptionService.recordDeliveryInternal(subscriptionId, "availability.updated", 500, 100, i + 1, "Error");
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
            subscriptionService.recordDeliveryInternal(subscriptionId, "availability.updated", 500, 100, i + 1, "Error");
        }
        // 1 success
        subscriptionService.recordDeliveryInternal(subscriptionId, "availability.updated", 200, 50, 4, "OK");

        Integer failures = jdbcTemplate.queryForObject(
                "SELECT consecutive_failures FROM subscription WHERE id = ?", Integer.class, subscriptionId);
        assertThat(failures).isEqualTo(0);
    }

    // T-28: Delivery log persisted
    @Test
    @DisplayName("recordDelivery persists entry in webhook_delivery_log")
    void deliveryLogPersisted() {
        subscriptionService.recordDeliveryInternal(subscriptionId, "availability.updated", 200, 42, 1, "OK");

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
        subscriptionService.recordDeliveryInternal(subscriptionId, "availability.updated", 200, 50, 1, longBody);

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
        subscriptionService.recordDeliveryInternal(subscriptionId, "availability.updated", 200, 50, 1, "OK");
        subscriptionService.recordDeliveryInternal(subscriptionId, "availability.updated", 500, 100, 2, "Error");

        HttpHeaders headers = authHelper.adminHeaders();
        ResponseEntity<List<Map<String, Object>>> resp = restTemplate.exchange(
                "/api/v1/subscriptions/" + subscriptionId + "/deliveries", HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(2);
    }

    // --- Riley: Intermediate FAILING state test ---

    @Test
    @DisplayName("1-4 failures set status to FAILING, 5th sets DEACTIVATED")
    void failureStateTransitions() {
        // 1 failure → FAILING
        subscriptionService.recordDeliveryInternal(subscriptionId, "availability.updated", 500, 100, 1, "Error");
        String status1 = jdbcTemplate.queryForObject("SELECT status FROM subscription WHERE id = ?", String.class, subscriptionId);
        assertThat(status1).as("After 1 failure, status should be FAILING").isEqualTo("FAILING");

        // 4 failures total → still FAILING, consecutive_failures=4
        for (int i = 2; i <= 4; i++) {
            subscriptionService.recordDeliveryInternal(subscriptionId, "availability.updated", 500, 100, i, "Error");
        }
        String status4 = jdbcTemplate.queryForObject("SELECT status FROM subscription WHERE id = ?", String.class, subscriptionId);
        Integer count4 = jdbcTemplate.queryForObject("SELECT consecutive_failures FROM subscription WHERE id = ?", Integer.class, subscriptionId);
        assertThat(status4).as("After 4 failures, status should still be FAILING").isEqualTo("FAILING");
        assertThat(count4).as("After 4 failures, counter should be 4").isEqualTo(4);

        // 5th failure → DEACTIVATED
        subscriptionService.recordDeliveryInternal(subscriptionId, "availability.updated", 500, 100, 5, "Error");
        String status5 = jdbcTemplate.queryForObject("SELECT status FROM subscription WHERE id = ?", String.class, subscriptionId);
        assertThat(status5).as("After 5 failures, status should be DEACTIVATED").isEqualTo("DEACTIVATED");
    }

    // --- Marcus: Cross-tenant isolation tests ---

    @Test
    @DisplayName("Tenant B cannot PATCH status on Tenant A's subscription (returns 404)")
    void crossTenantPatchStatus_returns404() {
        // Create Tenant B
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var tenantB = tenantService.findBySlug("wh-b-" + suffix)
                .orElseGet(() -> tenantService.create("Webhook Tenant B", "wh-b-" + suffix));
        var userB = new org.fabt.auth.domain.User();
        userB.setTenantId(tenantB.getId());
        userB.setEmail("admin-b-" + suffix + "@test.fabt.org");
        userB.setPasswordHash(passwordService.hash("TestPass123!"));
        userB.setDisplayName("Tenant B Admin");
        userB.setRoles(new String[]{"PLATFORM_ADMIN"});
        userB.setStatus("ACTIVE");
        userB.setCreatedAt(java.time.Instant.now());
        userB.setUpdatedAt(java.time.Instant.now());
        userRepository.save(userB);
        String tokenB = jwtService.generateAccessToken(userB);

        // Tenant B tries to patch Tenant A's subscription
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenB);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/subscriptions/" + subscriptionId + "/status", HttpMethod.PATCH,
                new HttpEntity<>("{\"status\":\"PAUSED\"}", headers), String.class);

        assertThat(resp.getStatusCode())
                .as("Cross-tenant PATCH should return 404 (not 403)")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Tenant B cannot GET delivery log for Tenant A's subscription (returns 404)")
    void crossTenantGetDeliveries_returns404() {
        // Create delivery for Tenant A's subscription
        subscriptionService.recordDeliveryInternal(subscriptionId, "availability.updated", 200, 50, 1, "OK");

        // Create Tenant B
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var tenantB = tenantService.findBySlug("wh-c-" + suffix)
                .orElseGet(() -> tenantService.create("Webhook Tenant C", "wh-c-" + suffix));
        var userB = new org.fabt.auth.domain.User();
        userB.setTenantId(tenantB.getId());
        userB.setEmail("admin-c-" + suffix + "@test.fabt.org");
        userB.setPasswordHash(passwordService.hash("TestPass123!"));
        userB.setDisplayName("Tenant C Admin");
        userB.setRoles(new String[]{"PLATFORM_ADMIN"});
        userB.setStatus("ACTIVE");
        userB.setCreatedAt(java.time.Instant.now());
        userB.setUpdatedAt(java.time.Instant.now());
        userRepository.save(userB);
        String tokenB = jwtService.generateAccessToken(userB);

        // Tenant B tries to read Tenant A's delivery log
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenB);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/subscriptions/" + subscriptionId + "/deliveries", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(resp.getStatusCode())
                .as("Cross-tenant GET deliveries should return 404 (not 403)")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- Marcus: Additional redaction pattern tests ---

    @Test
    @DisplayName("SSN in response body is redacted")
    void ssnRedacted() {
        String result = WebhookResponseRedactor.redact("SSN: 123-45-6789 on file");
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("123-45-6789");
    }

    @Test
    @DisplayName("Credit card number in response body is redacted")
    void creditCardRedacted() {
        String result = WebhookResponseRedactor.redact("Card: 4111111111111111 charged");
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("4111111111111111");
    }

    @Test
    @DisplayName("Generic API key value in response body is redacted")
    void apiKeyValueRedacted() {
        String result = WebhookResponseRedactor.redact("api_key=sk_live_abcdef1234567890abcdef1234567890");
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("sk_live_abcdef");
    }
}
