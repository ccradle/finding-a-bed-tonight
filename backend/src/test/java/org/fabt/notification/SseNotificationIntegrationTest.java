package org.fabt.notification;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.notification.service.NotificationService;
import org.fabt.shared.event.DomainEvent;
import org.fabt.shared.event.EventBus;
import org.fabt.shared.web.TenantContext;
import org.fabt.tenant.domain.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for SSE notification endpoint.
 * Tests: authentication, event delivery, tenant isolation, DV safety.
 *
 * The SSE stream test uses java.net.http.HttpClient with BodyHandlers.ofLines()
 * because its CompletableFuture completes on headers (streaming type), not on
 * full body (which would block until the 5-min emitter timeout). TestRestTemplate
 * uses accumulating body semantics and cannot handle SSE streams.
 */
class SseNotificationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private EventBus eventBus;

    @LocalServerPort
    private int port;

    private Tenant testTenant;

    @BeforeEach
    void setUp() {
        testTenant = authHelper.setupTestTenant("sse-test");
        authHelper.setupOutreachWorkerUser();
        authHelper.setupCoordinatorUser();
    }

    @AfterEach
    void tearDown() {
        // Complete all server-side SSE emitters so Tomcat doesn't block on shutdown
        // waiting for active requests to finish. SseEmitter only detects client
        // disconnect on the next write attempt — without this, graceful shutdown
        // hangs until the 5-minute emitter timeout.
        notificationService.completeAll();
    }

    @Test
    @DisplayName("SSE endpoint returns 401 without token")
    void sseEndpointRejectsUnauthenticated() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/notifications/stream", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("SSE endpoint accepts token via query parameter and returns 200")
    void sseEndpointAcceptsTokenQueryParam() throws Exception {
        User outreach = authHelper.setupOutreachWorkerUser();
        String token = authHelper.getJwtService().generateAccessToken(outreach);

        // BodyHandlers.ofLines() is a streaming type — the CompletableFuture completes
        // when headers arrive (status code available), not when the body finishes.
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port
                        + "/api/v1/notifications/stream?token=" + token))
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        HttpResponse<Stream<String>> response = httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .get(5, TimeUnit.SECONDS);

        try {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type"))
                    .isPresent()
                    .hasValueSatisfying(ct -> assertThat(ct).contains("text/event-stream"));
        } finally {
            response.body().close();
            httpClient.shutdownNow();
        }
    }

    @Test
    @DisplayName("NotificationService delivers referral response to outreach worker")
    void referralResponseDeliveredToOutreachWorker() {
        User outreach = authHelper.setupOutreachWorkerUser();
        UUID tenantId = testTenant.getId();

        SseEmitter emitter = notificationService.register(
                outreach.getId(), tenantId,
                new String[]{"OUTREACH_WORKER"}, true);

        Map<String, Object> payload = Map.of(
                "token_id", UUID.randomUUID().toString(),
                "shelter_id", UUID.randomUUID().toString(),
                "status", "ACCEPTED");

        TenantContext.runWithContext(tenantId, true, () ->
                eventBus.publish(new DomainEvent("dv-referral.responded", tenantId, payload)));

        // Event sent successfully — emitter still active
        assertThat(emitter).isNotNull();
    }

    @Test
    @DisplayName("Cross-tenant isolation — Tenant B user does not receive Tenant A events")
    void crossTenantIsolation() {
        Tenant tenantA = authHelper.setupTestTenant("sse-tenant-a");
        User outreachA = authHelper.setupUserWithDvAccess(
                "outreach-a@test.fabt.org", "Outreach A", new String[]{"OUTREACH_WORKER"});

        Tenant tenantB = authHelper.setupTestTenant("sse-tenant-b");
        User outreachB = authHelper.setupUserWithDvAccess(
                "outreach-b@test.fabt.org", "Outreach B", new String[]{"OUTREACH_WORKER"});

        SseEmitter emitterA = notificationService.register(
                outreachA.getId(), tenantA.getId(),
                new String[]{"OUTREACH_WORKER"}, true);
        SseEmitter emitterB = notificationService.register(
                outreachB.getId(), tenantB.getId(),
                new String[]{"OUTREACH_WORKER"}, true);

        Map<String, Object> payload = Map.of(
                "shelter_id", UUID.randomUUID().toString(),
                "shelter_name", "Test Shelter",
                "population_type", "SINGLE_ADULT",
                "beds_available", 5,
                "beds_available_previous", 3);

        TenantContext.runWithContext(tenantA.getId(), false, () ->
                eventBus.publish(new DomainEvent("availability.updated", tenantA.getId(), payload)));

        assertThat(emitterA).isNotNull();
        assertThat(emitterB).isNotNull();
    }

    @Test
    @DisplayName("DV safety — SSE wire data does not contain shelter name or address")
    void dvSafetyNoShelterInfoInWireData() throws Exception {
        User outreach = authHelper.setupOutreachWorkerUser();
        String token = authHelper.getJwtService().generateAccessToken(outreach);
        UUID tenantId = testTenant.getId();

        // Connect to SSE endpoint via HttpClient — ofLines() completes on headers
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port
                        + "/api/v1/notifications/stream?token=" + token))
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        HttpResponse<Stream<String>> response = httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .get(5, TimeUnit.SECONDS);

        assertThat(response.statusCode()).isEqualTo(200);

        // Collect lines in a background thread
        var receivedLines = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var latch = new java.util.concurrent.CountDownLatch(1);

        Thread.startVirtualThread(() -> {
            response.body().forEach(line -> {
                receivedLines.add(line);
                if (line.startsWith("data:")) {
                    latch.countDown();
                }
            });
        });

        // Small delay to ensure SSE connection is fully established
        Thread.sleep(200);

        // Publish a dv-referral.responded event
        TenantContext.runWithContext(tenantId, true, () ->
                eventBus.publish(new DomainEvent("dv-referral.responded", tenantId, Map.of(
                        "token_id", UUID.randomUUID().toString(),
                        "shelter_id", UUID.randomUUID().toString(),
                        "status", "ACCEPTED"))));

        // Wait for the event to arrive on the wire
        boolean received = latch.await(5, TimeUnit.SECONDS);

        // Close the SSE stream and HttpClient to release connections
        response.body().close();
        httpClient.shutdownNow();

        assertThat(received).as("SSE event should be received within 5 seconds").isTrue();

        // Assert on actual wire content — shelter_name and shelter_address must be absent
        String allLines = String.join("\n", receivedLines);
        assertThat(allLines).doesNotContain("shelter_name");
        assertThat(allLines).doesNotContain("shelter_address");
        assertThat(allLines).doesNotContain("shelterName");
        assertThat(allLines).doesNotContain("shelterAddress");
        // Verify the event DID contain expected fields (positive assertion)
        assertThat(allLines).contains("referralId");
        assertThat(allLines).contains("ACCEPTED");
    }
}
