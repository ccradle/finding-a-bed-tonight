package org.fabt.subscription;

import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.shared.web.TenantContext;
import org.fabt.subscription.api.SubscriptionResponse;
import org.fabt.tenant.domain.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        authHelper.setupOutreachWorkerUser();
    }

    @Test
    void test_createSubscription_returnsCreated() {
        HttpHeaders headers = authHelper.adminHeaders();

        String body = """
                {
                    "eventType": "availability.updated",
                    "filter": {"population_types": ["FAMILY_WITH_CHILDREN"]},
                    "callbackUrl": "https://example.com/webhook",
                    "callbackSecret": "my-webhook-secret-123"
                }
                """;

        ResponseEntity<SubscriptionResponse> response = restTemplate.exchange(
                "/api/v1/subscriptions",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                SubscriptionResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        SubscriptionResponse sub = response.getBody();
        assertThat(sub).isNotNull();
        assertThat(sub.id()).isNotNull();
        assertThat(sub.eventType()).isEqualTo("availability.updated");
        assertThat(sub.status()).isEqualTo("ACTIVE");
        assertThat(sub.callbackUrl()).isEqualTo("https://example.com/webhook");
    }

    @Test
    void test_listSubscriptions_returnsTenantScoped() {
        HttpHeaders headers = authHelper.adminHeaders();

        // Create a subscription
        String body = """
                {
                    "eventType": "availability.updated",
                    "filter": {},
                    "callbackUrl": "https://example.com/list-test",
                    "callbackSecret": "secret"
                }
                """;
        restTemplate.exchange("/api/v1/subscriptions", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        // List subscriptions
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/subscriptions",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("list-test");
    }

    @Test
    void test_deleteSubscription_setsCancelled() {
        HttpHeaders headers = authHelper.adminHeaders();

        // Create a subscription
        String body = """
                {
                    "eventType": "surge.activated",
                    "filter": {},
                    "callbackUrl": "https://example.com/delete-test",
                    "callbackSecret": "secret"
                }
                """;
        ResponseEntity<SubscriptionResponse> createResponse = restTemplate.exchange(
                "/api/v1/subscriptions", HttpMethod.POST,
                new HttpEntity<>(body, headers), SubscriptionResponse.class);

        UUID subId = createResponse.getBody().id();

        // Delete it
        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/api/v1/subscriptions/" + subId,
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Void.class
        );

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void test_outreachWorkerCanCreateSubscription() {
        // Any authenticated user can create subscriptions (per SecurityConfig)
        HttpHeaders headers = authHelper.outreachWorkerHeaders();

        String body = """
                {
                    "eventType": "availability.updated",
                    "filter": {"population_types": ["VETERAN"]},
                    "callbackUrl": "https://example.com/outreach-webhook",
                    "callbackSecret": "outreach-secret"
                }
                """;

        ResponseEntity<SubscriptionResponse> response = restTemplate.exchange(
                "/api/v1/subscriptions",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                SubscriptionResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void test_errorResponseStructure_onInvalidRequest() {
        HttpHeaders headers = authHelper.adminHeaders();

        // Missing required fields
        String body = """
                {"eventType": "", "callbackUrl": "", "callbackSecret": ""}
                """;

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/subscriptions",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map responseBody = response.getBody();
        assertThat(responseBody).isNotNull();
        assertThat(responseBody).containsKey("error");
        assertThat(responseBody.get("error")).isEqualTo("validation_failed");
        assertThat(responseBody).containsKey("context");
    }

    // ================================================================
    // cross-tenant-isolation-audit (Issue #117) — Phase 2 task 2.4.4.
    // Regression test pinning the findByIdAndTenantId / findByIdOrThrow
    // refactor on SubscriptionService.delete.
    //
    // THREAT MODEL (Marcus Webb, VULN-HIGH — availability / silent DoS):
    // pre-fix, a CoC admin in Tenant A could DELETE /api/v1/subscriptions/
    // {tenantB-subscription-id}, setting Tenant B's subscription status to
    // CANCELLED. Tenant B would silently stop receiving their own webhook
    // events — denial-of-service of their automation pipeline with no
    // indication to Tenant B's operators that the cancellation was
    // attacker-initiated.
    // ================================================================

    @Test
    void tc_delete_crossTenant_returns404_leavesTenantBSubscriptionActive() {
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);

        // Set up Tenant B with its own admin + subscription.
        Tenant tenantB = authHelper.setupSecondaryTenant("xtenant-sub-delete-" + suffix);
        User adminB = authHelper.createUserInTenant(tenantB.getId(),
                "admin-b-sub-" + suffix + "@test.fabt.org", "Tenant B Admin",
                new String[]{"PLATFORM_ADMIN", "COC_ADMIN"}, false);
        HttpHeaders adminBHeaders = authHelper.headersForUser(adminB);

        String createBody = """
                {
                    "eventType": "availability.updated",
                    "filter": {},
                    "callbackUrl": "https://example.com/tenant-b/webhook",
                    "callbackSecret": "tenant-b-legitimate-secret"
                }
                """;
        ResponseEntity<SubscriptionResponse> createResp = restTemplate.exchange(
                "/api/v1/subscriptions", HttpMethod.POST,
                new HttpEntity<>(createBody, adminBHeaders), SubscriptionResponse.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID tenantBSubscriptionId = createResp.getBody().id();

        // Act: Tenant A's admin attempts to cancel Tenant B's subscription.
        HttpHeaders tenantAHeaders = authHelper.adminHeaders();
        ResponseEntity<String> attackResp = restTemplate.exchange(
                "/api/v1/subscriptions/" + tenantBSubscriptionId,
                HttpMethod.DELETE,
                new HttpEntity<>(tenantAHeaders),
                String.class);

        // Assert: 404 (not 403 — D3 symmetric; not 204 — pre-fix would
        // have silently succeeded and returned 204).
        assertThat(attackResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Defense-in-depth: Tenant B's subscription status is still ACTIVE.
        TenantContext.runWithContext(tenantB.getId(), false, () -> {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM subscription WHERE id = ?::uuid",
                    String.class, tenantBSubscriptionId);
            assertThat(status)
                    .as("Tenant B's subscription must still be ACTIVE — cross-tenant DELETE was rejected")
                    .isEqualTo("ACTIVE");
        });
    }

    // ================================================================
    // Task 2.4.5 — URL-path-sink class per design D11 (warroom 2026-04-15
    // fix-the-set-now call). Pre-fix: SubscriptionService.create accepted
    // UUID tenantId as a parameter — not a live vulnerability because the
    // controller sources from TenantContext, but an attractive-nuisance
    // signature the Phase 3 ArchUnit Family B rule will flag. Post-fix:
    // service signature drops the tenantId param and pulls from
    // TenantContext internally.
    //
    // This regression test is NOT a cross-tenant attack test (the delete
    // test above covers that class). It is a signature-correctness pin:
    // it exercises the create endpoint end-to-end and verifies the new
    // subscription lands in the caller's tenant, matching the D11 contract.
    // ================================================================

    // ================================================================
    // cross-tenant-isolation-audit Phase 2.14 — SSRF guard on webhook
    // callback URL (SafeOutboundUrlValidator, design D12).
    //
    // THREAT MODEL (Marcus Webb, LIVE VULN-HIGH): pre-fix, a CoC admin
    // could configure http://169.254.169.254/latest/meta-data/ or
    // http://127.0.0.1:9091/actuator/prometheus as the webhook
    // callbackUrl. Every matching event would dial the cloud-metadata
    // service or the backend's own actuator port, exfiltrating IAM
    // credentials or internal metrics. 2026 CVE-2026-27127 (Craft CMS)
    // showed URL-parse-only validation is defeated by DNS rebinding —
    // hence the three-layer (parse + DNS + dial-time) design.
    // ================================================================

    @Test
    void tc_createSubscription_cloudMetadataUrl_rejected() {
        HttpHeaders headers = authHelper.adminHeaders();
        String maliciousBody = """
                {
                    "eventType": "availability.updated",
                    "filter": {},
                    "callbackUrl": "http://169.254.169.254/latest/meta-data/iam/security-credentials/",
                    "callbackSecret": "attacker-secret"
                }
                """;
        ResponseEntity<Map> resp = restTemplate.exchange(
                "/api/v1/subscriptions", HttpMethod.POST,
                new HttpEntity<>(maliciousBody, headers), Map.class);

        assertThat(resp.getStatusCode())
                .as("Cloud-metadata SSRF URL must be rejected at creation time")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody())
                .as("Error envelope must surface bad_request — pin the GlobalExceptionHandler contract")
                .containsEntry("error", "bad_request");
    }

    @Test
    void tc_createSubscription_loopbackUrl_rejected() {
        HttpHeaders headers = authHelper.adminHeaders();
        String maliciousBody = """
                {
                    "eventType": "surge.activated",
                    "filter": {},
                    "callbackUrl": "http://127.0.0.1:9091/actuator/prometheus",
                    "callbackSecret": "attacker-secret"
                }
                """;
        ResponseEntity<Map> resp = restTemplate.exchange(
                "/api/v1/subscriptions", HttpMethod.POST,
                new HttpEntity<>(maliciousBody, headers), Map.class);

        assertThat(resp.getStatusCode())
                .as("Loopback URL must be rejected — prevents backend self-exfiltration via actuator")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsEntry("error", "bad_request");
    }

    @Test
    void tc_createSubscription_rfc1918Url_rejected() {
        HttpHeaders headers = authHelper.adminHeaders();
        String maliciousBody = """
                {
                    "eventType": "availability.updated",
                    "filter": {},
                    "callbackUrl": "http://192.168.1.1/internal",
                    "callbackSecret": "attacker-secret"
                }
                """;
        ResponseEntity<Map> resp = restTemplate.exchange(
                "/api/v1/subscriptions", HttpMethod.POST,
                new HttpEntity<>(maliciousBody, headers), Map.class);

        assertThat(resp.getStatusCode())
                .as("RFC1918 private-network URL must be rejected")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsEntry("error", "bad_request");
    }

    @Test
    void tc_createSubscription_nonHttpScheme_rejected() {
        HttpHeaders headers = authHelper.adminHeaders();
        String maliciousBody = """
                {
                    "eventType": "availability.updated",
                    "filter": {},
                    "callbackUrl": "file:///etc/passwd",
                    "callbackSecret": "attacker-secret"
                }
                """;
        ResponseEntity<Map> resp = restTemplate.exchange(
                "/api/v1/subscriptions", HttpMethod.POST,
                new HttpEntity<>(maliciousBody, headers), Map.class);

        assertThat(resp.getStatusCode())
                .as("Non-http/https scheme must be rejected")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsEntry("error", "bad_request");
    }

    @Test
    void tc_create_afterD11Refactor_subscriptionLandsInCallerTenant() {
        HttpHeaders tenantAHeaders = authHelper.adminHeaders();
        UUID tenantAId = authHelper.getTestTenantId();

        String createBody = """
                {
                    "eventType": "surge.activated",
                    "filter": {},
                    "callbackUrl": "https://example.com/tenant-a/d11-check",
                    "callbackSecret": "d11-check-secret"
                }
                """;
        ResponseEntity<SubscriptionResponse> createResp = restTemplate.exchange(
                "/api/v1/subscriptions", HttpMethod.POST,
                new HttpEntity<>(createBody, tenantAHeaders), SubscriptionResponse.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID subscriptionId = createResp.getBody().id();

        // Assert: the new subscription's tenant_id column equals the
        // caller's JWT tenant — service correctly pulled from
        // TenantContext rather than any request-supplied value.
        TenantContext.runWithContext(tenantAId, false, () -> {
            UUID storedTenantId = jdbcTemplate.queryForObject(
                    "SELECT tenant_id FROM subscription WHERE id = ?::uuid",
                    UUID.class, subscriptionId);
            assertThat(storedTenantId)
                    .as("Subscription tenant_id must equal caller's JWT tenant (D11: sourced from TenantContext)")
                    .isEqualTo(tenantAId);
        });
    }
}
