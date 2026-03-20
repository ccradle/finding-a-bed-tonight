package org.fabt.subscription;

import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.subscription.api.SubscriptionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

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
}
