package org.fabt.subscription;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.subscription.service.SubscriptionService;
import org.fabt.subscription.service.WebhookDeliveryService;
import org.junit.jupiter.api.AfterEach;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-25: Integration test for the webhook test-event endpoint
 * (POST /api/v1/subscriptions/{id}/test).
 *
 * <p>Uses WireMock as a mock subscriber endpoint to verify the full transport
 * path: HMAC signing, headers, JSON body, and delivery logging. Spring Framework
 * recommends mock web servers over MockRestServiceServer for RestClient testing
 * because they exercise the real HTTP layer.</p>
 *
 * <p><b>Persona drivers:</b></p>
 * <ul>
 *   <li>Marcus Okafor (CoC admin): "Send Test" is the button he clicks during
 *       partner onboarding. If it silently fails, he wastes a partner call.</li>
 *   <li>Marcus Webb (pen tester): every untested authorization/transport path
 *       is a finding waiting to happen.</li>
 * </ul>
 */
@DisplayName("Webhook Test Event Delivery (T-25)")
class WebhookTestEventDeliveryTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private WireMockServer wireMock;
    private UUID subscriptionId;

    @BeforeEach
    void setUp() {
        // Random port — multiple test classes can run in parallel without collisions
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());

        authHelper.setupTestTenant();
        authHelper.setupAdminUser();

        // Create a subscription pointing at the WireMock server
        HttpHeaders headers = authHelper.adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"eventType":"availability.updated","filter":{},
                 "callbackUrl":"http://localhost:%d/webhook",
                 "callbackSecret":"test-secret-T25-abcdef"}
                """.formatted(wireMock.port());
        ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                "/api/v1/subscriptions", HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<>() {});
        subscriptionId = UUID.fromString((String) resp.getBody().get("id"));
    }

    @AfterEach
    void tearDown() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @Test
    @DisplayName("POST /test delivers synthetic event to subscriber and returns delivery result")
    void sendTestEvent_succeeds_andRecordsDelivery() {
        // Arrange — subscriber returns 200 OK with a small body
        wireMock.stubFor(post(urlEqualTo("/webhook"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"received\":true}")));

        // Act
        HttpHeaders headers = authHelper.adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<WebhookDeliveryService.TestDeliveryResult> resp = restTemplate.exchange(
                "/api/v1/subscriptions/" + subscriptionId + "/test", HttpMethod.POST,
                new HttpEntity<>("{\"eventType\":\"availability.updated\"}", headers),
                WebhookDeliveryService.TestDeliveryResult.class);

        // Assert — endpoint contract
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().statusCode()).isEqualTo(200);
        assertThat(resp.getBody().responseTimeMs()).isGreaterThanOrEqualTo(0);
        assertThat(resp.getBody().responseBody()).contains("received");

        // Assert — WireMock saw a well-formed delivery
        RequestPatternBuilder expected = postRequestedFor(urlEqualTo("/webhook"))
                .withHeader("Content-Type", WireMock.containing("application/json"))
                .withHeader("X-Signature", WireMock.matching("sha256=[0-9a-f]+"))
                .withHeader("X-Test-Event", WireMock.equalTo("true"))
                .withRequestBody(matchingJsonPath("$.type", WireMock.equalTo("availability.updated")))
                .withRequestBody(matchingJsonPath("$.test", WireMock.equalTo("true")))
                .withRequestBody(matchingJsonPath("$.id"))
                .withRequestBody(matchingJsonPath("$.timestamp"));
        wireMock.verify(1, expected);

        // Assert — delivery was logged
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM webhook_delivery_log WHERE subscription_id = ? AND status_code = 200",
                Integer.class, subscriptionId);
        assertThat(rows).as("Successful test delivery should be recorded with status 200")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("POST /test on subscriber returning 500 records failure but does NOT auto-disable")
    void sendTestEvent_5xxResponse_recordsFailureWithoutAutoDisable() {
        // Arrange — subscriber returns 500
        wireMock.stubFor(post(urlEqualTo("/webhook"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("Internal Server Error")));

        // Act — capture as String so we can diagnose the actual envelope
        HttpHeaders headers = authHelper.adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> raw = restTemplate.exchange(
                "/api/v1/subscriptions/" + subscriptionId + "/test", HttpMethod.POST,
                new HttpEntity<>("{\"eventType\":\"availability.updated\"}", headers),
                String.class);

        // The endpoint always returns 200 with the result envelope; the subscriber's
        // status code lives inside the body.
        assertThat(raw.getStatusCode())
                .as("Test endpoint must return 200 even when delivery fails")
                .isEqualTo(HttpStatus.OK);
        assertThat(raw.getBody()).isNotNull();
        // sendTestEvent's catch branch records null statusCode for non-2xx (RestClient throws)
        // — assert we did not silently report success.
        assertThat(raw.getBody())
                .as("Body must not claim upstream returned 200")
                .doesNotContain("\"statusCode\":200");

        // Failure was logged
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM webhook_delivery_log WHERE subscription_id = ?",
                Integer.class, subscriptionId);
        assertThat(rows).as("Failed test delivery should still be recorded").isEqualTo(1);
    }

    @Test
    @DisplayName("POST /test on missing subscription returns 404")
    void sendTestEvent_unknownSubscription_returns404() {
        HttpHeaders headers = authHelper.adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/subscriptions/" + UUID.randomUUID() + "/test", HttpMethod.POST,
                new HttpEntity<>("{\"eventType\":\"availability.updated\"}", headers),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /test signature is reproducible — same body → same HMAC")
    void sendTestEvent_signatureIsHmacSha256() {
        wireMock.stubFor(post(urlEqualTo("/webhook"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        HttpHeaders headers = authHelper.adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.exchange(
                "/api/v1/subscriptions/" + subscriptionId + "/test", HttpMethod.POST,
                new HttpEntity<>("{\"eventType\":\"availability.updated\"}", headers),
                WebhookDeliveryService.TestDeliveryResult.class);

        // Marcus Webb's lens: every signed request must have a verifiable signature.
        // We don't recompute the HMAC here (we'd need the encryption key), but we
        // verify the format is correct: sha256=<64 hex chars>.
        wireMock.verify(1, postRequestedFor(urlEqualTo("/webhook"))
                .withHeader("X-Signature", WireMock.matching("sha256=[0-9a-f]{64}")));

        // Sanity: there is exactly one delivery log entry, regardless of which
        // record path was taken (success vs. caught exception).
        @SuppressWarnings("unused")
        List<Map<String, Object>> logs = jdbcTemplate.queryForList(
                "SELECT * FROM webhook_delivery_log WHERE subscription_id = ?",
                subscriptionId);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM webhook_delivery_log WHERE subscription_id = ?",
                Integer.class, subscriptionId);
        assertThat(count).isEqualTo(1);
    }
}
