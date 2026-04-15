package org.fabt.subscription;

import java.util.Map;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.shared.security.SafeOutboundUrlValidator;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-25a: Integration test for webhook delivery read timeout.
 *
 * <p>Design D3 specifies a 30s read timeout. Investigation 2026-04-09 found that
 * only the connect timeout was actually wired into the JDK HttpClient — there was
 * no read timeout, so a slow endpoint would block a virtual thread indefinitely.
 * Marcus Webb's lens: documented timeouts that don't exist are a security gap.</p>
 *
 * <p>This test uses a tiny configured timeout (1s) and a WireMock fixed delay
 * (3s) so the test runs quickly. It verifies the timeout fires, the failure is
 * recorded, and the delivery result envelope reports an error rather than a
 * silent success.</p>
 */
@DisplayName("Webhook Delivery Timeout (T-25a)")
@TestPropertySource(properties = {
        "fabt.webhook.connect-timeout-seconds=10",
        "fabt.webhook.read-timeout-seconds=1"
})
class WebhookTimeoutTest extends BaseIntegrationTest {

    @Autowired private TestAuthHelper authHelper;
    @Autowired private JdbcTemplate jdbcTemplate;

    // D12: stub the SSRF guard so this test can target WireMock at
    // http://localhost:<random>. See WebhookTestEventDeliveryTest for the
    // full rationale — the production validator stays armed elsewhere.
    @MockitoBean
    private SafeOutboundUrlValidator urlValidator;

    private WireMockServer wireMock;
    private UUID subscriptionId;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());

        authHelper.setupTestTenant();
        authHelper.setupAdminUser();

        HttpHeaders headers = authHelper.adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"eventType":"availability.updated","filter":{},
                 "callbackUrl":"http://localhost:%d/webhook",
                 "callbackSecret":"test-secret-T25a-abcdef"}
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
    @DisplayName("Slow endpoint exceeding read timeout fails the test delivery")
    void slowEndpoint_exceedsReadTimeout_recordedAsFailure() {
        // Subscriber sleeps 3s before responding; configured read timeout is 1s
        wireMock.stubFor(post(urlEqualTo("/webhook"))
                .willReturn(aResponse()
                        .withFixedDelay(3000)
                        .withStatus(200)
                        .withBody("would have been ok")));

        long start = System.currentTimeMillis();

        HttpHeaders headers = authHelper.adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> raw = restTemplate.exchange(
                "/api/v1/subscriptions/" + subscriptionId + "/test", HttpMethod.POST,
                new HttpEntity<>("{\"eventType\":\"availability.updated\"}", headers),
                String.class);

        long elapsedMs = System.currentTimeMillis() - start;

        // The endpoint must return 200 with the result envelope (not propagate the
        // delivery failure as a server error — the test endpoint reports the result
        // in the body, not via HTTP status)
        assertThat(raw.getStatusCode())
                .as("Test endpoint must return 200 even when delivery fails — failure lives in the body")
                .isEqualTo(HttpStatus.OK);
        assertThat(raw.getBody()).isNotNull();

        // The body must indicate failure (statusCode is null when the RestClient
        // threw on timeout) — never the upstream's would-be 200.
        assertThat(raw.getBody())
                .as("Body must not report upstream's would-be 200")
                .doesNotContain("\"statusCode\":200");

        // Most important: we did not wait for the upstream to finish.
        // 3s upstream delay > 1s read timeout — total elapsed should be well under 3s.
        // Allow generous slack for CI overhead but still prove the timeout fired.
        assertThat(elapsedMs)
                .as("Read timeout must fire well before the upstream's 3s delay completes")
                .isLessThan(2500);

        // The failure was logged
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM webhook_delivery_log WHERE subscription_id = ?",
                Integer.class, subscriptionId);
        assertThat(rows).as("Timed-out delivery must still be recorded").isEqualTo(1);
    }

    @Test
    @DisplayName("Fast endpoint within timeout still succeeds")
    void fastEndpoint_underTimeout_succeeds() {
        // Subscriber responds well within the 1s read timeout
        wireMock.stubFor(post(urlEqualTo("/webhook"))
                .willReturn(aResponse()
                        .withFixedDelay(100)
                        .withStatus(200)
                        .withBody("ok")));

        HttpHeaders headers = authHelper.adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<WebhookDeliveryService.TestDeliveryResult> resp = restTemplate.exchange(
                "/api/v1/subscriptions/" + subscriptionId + "/test", HttpMethod.POST,
                new HttpEntity<>("{\"eventType\":\"availability.updated\"}", headers),
                WebhookDeliveryService.TestDeliveryResult.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().statusCode()).isEqualTo(200);

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM webhook_delivery_log WHERE subscription_id = ? AND status_code = 200",
                Integer.class, subscriptionId);
        assertThat(rows).isEqualTo(1);
    }
}
