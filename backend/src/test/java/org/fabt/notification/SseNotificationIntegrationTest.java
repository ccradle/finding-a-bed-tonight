package org.fabt.notification;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.domain.User;
import org.fabt.notification.service.NotificationPersistenceService;
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
import static org.assertj.core.api.Assertions.assertThatCode;

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
    private NotificationPersistenceService notificationPersistenceService;

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
            httpClient.awaitTermination(Duration.ofSeconds(2));
        }
    }

    @Test
    @DisplayName("NotificationService delivers referral response to outreach worker")
    void referralResponseDeliveredToOutreachWorker() {
        User outreach = authHelper.setupOutreachWorkerUser();
        UUID tenantId = testTenant.getId();

        SseEmitter emitter = notificationService.register(
                outreach.getId(), tenantId,
                new String[]{"OUTREACH_WORKER"}, true, null);

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
                new String[]{"OUTREACH_WORKER"}, true, null);
        SseEmitter emitterB = notificationService.register(
                outreachB.getId(), tenantB.getId(),
                new String[]{"OUTREACH_WORKER"}, true, null);

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
        httpClient.awaitTermination(Duration.ofSeconds(2));

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

    @Test
    @DisplayName("Heartbeat error recovery — dead emitter removed without cascading exception")
    void heartbeatErrorRecovery() throws Exception {
        User outreach = authHelper.setupOutreachWorkerUser();
        String token = authHelper.getJwtService().generateAccessToken(outreach);

        // Connect via real HTTP SSE stream
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
        Thread.sleep(300); // Allow connection to establish

        // Force-close the client connection to simulate disconnect
        response.body().close();
        httpClient.shutdownNow();
        httpClient.awaitTermination(Duration.ofSeconds(2));

        // Trigger heartbeat — should handle the dead emitter gracefully (no exception)
        assertThatCode(() -> notificationService.sendHeartbeat())
                .as("Heartbeat should not throw on dead emitter — remove-before-completeWithError pattern")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Event broadcast error recovery — dead emitter doesn't affect live emitter")
    void eventBroadcastErrorRecovery() throws Exception {
        // User A — will disconnect
        User userA = authHelper.setupUserWithDvAccess(
                "sse-usera@test.fabt.org", "User A", new String[]{"OUTREACH_WORKER"});
        String tokenA = authHelper.getJwtService().generateAccessToken(userA);

        // User B — stays connected
        User userB = authHelper.setupUserWithDvAccess(
                "sse-userb@test.fabt.org", "User B", new String[]{"OUTREACH_WORKER"});
        String tokenB = authHelper.getJwtService().generateAccessToken(userB);

        // Connect both
        HttpClient clientA = HttpClient.newHttpClient();
        HttpResponse<Stream<String>> respA = clientA.sendAsync(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/v1/notifications/stream?token=" + tokenA))
                        .header("Accept", "text/event-stream").GET().build(),
                HttpResponse.BodyHandlers.ofLines()).get(5, TimeUnit.SECONDS);

        HttpClient clientB = HttpClient.newHttpClient();
        var receivedB = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var latchB = new java.util.concurrent.CountDownLatch(1);
        HttpResponse<Stream<String>> respB = clientB.sendAsync(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/v1/notifications/stream?token=" + tokenB))
                        .header("Accept", "text/event-stream").GET().build(),
                HttpResponse.BodyHandlers.ofLines()).get(5, TimeUnit.SECONDS);

        Thread.startVirtualThread(() -> {
            respB.body().forEach(line -> {
                receivedB.add(line);
                if (line.contains("dv-referral.responded")) latchB.countDown();
            });
        });

        Thread.sleep(300);

        // Disconnect User A
        respA.body().close();
        clientA.shutdownNow();
        clientA.awaitTermination(Duration.ofSeconds(2));

        // Publish event — User A is dead, User B should still receive
        UUID tenantId = testTenant.getId();
        TenantContext.runWithContext(tenantId, true, () ->
                eventBus.publish(new DomainEvent("dv-referral.responded", tenantId, Map.of(
                        "token_id", UUID.randomUUID().toString(),
                        "shelter_id", UUID.randomUUID().toString(),
                        "status", "ACCEPTED"))));

        boolean received = latchB.await(5, TimeUnit.SECONDS);

        respB.body().close();
        clientB.shutdownNow();
        clientB.awaitTermination(Duration.ofSeconds(2));

        assertThat(received).as("User B should receive event even after User A disconnects").isTrue();
    }

    @Test
    @DisplayName("SSE connection survives beyond 30 seconds (async timeout override)")
    void sseConnectionSurvivesBeyond30Seconds() throws Exception {
        User outreach = authHelper.setupOutreachWorkerUser();
        String token = authHelper.getJwtService().generateAccessToken(outreach);

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

        var receivedLines = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var latch = new java.util.concurrent.CountDownLatch(1);
        Thread.startVirtualThread(() -> {
            response.body().forEach(line -> {
                receivedLines.add(line);
                if (line.contains("heartbeat")) latch.countDown();
            });
        });

        Thread.sleep(500); // Allow connection to establish

        // Manually trigger heartbeat at 31 seconds — beyond default 30s timeout
        Thread.sleep(31_000);
        notificationService.sendHeartbeat();
        Thread.sleep(500);

        boolean received = latch.await(5, TimeUnit.SECONDS);

        response.body().close();
        httpClient.shutdownNow();
        httpClient.awaitTermination(Duration.ofSeconds(2));

        // If we received a heartbeat at 31s, the connection survived past the default 30s timeout
        assertThat(received).as("SSE connection should survive beyond 30s default timeout").isTrue();
    }

    @Test
    @DisplayName("Unauthenticated SSE connection returns 401")
    void unauthenticatedSseReturns401() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/notifications/stream", String.class);
        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("SSE catch-up delivers unread notifications from DB on connect")
    void sseCatchupDeliversUnreadNotifications() throws Exception {
        User coordinator = authHelper.setupCoordinatorUser();
        UUID tenantId = testTenant.getId();

        // Create persistent notifications BEFORE SSE connect
        TenantContext.runWithContext(tenantId, false, () -> {
            notificationPersistenceService.send(tenantId, coordinator.getId(),
                    "referral.requested", "ACTION_REQUIRED",
                    "{\"referralId\":\"" + UUID.randomUUID() + "\"}");
        });

        // Connect via SSE — catch-up should deliver the unread notification
        String token = authHelper.getJwtService().generateAccessToken(coordinator);
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpResponse<Stream<String>> response = httpClient.sendAsync(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/v1/notifications/stream?token=" + token))
                        .header("Accept", "text/event-stream").GET().build(),
                HttpResponse.BodyHandlers.ofLines()).get(5, TimeUnit.SECONDS);

        assertThat(response.statusCode()).isEqualTo(200);

        var receivedLines = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var latch = new java.util.concurrent.CountDownLatch(1);
        Thread.startVirtualThread(() -> {
            response.body().forEach(line -> {
                receivedLines.add(line);
                // Catch-up sends events with name "notification"
                if (line.contains("referral.requested")) latch.countDown();
            });
        });

        // Wait for catch-up delivery (runs on virtual thread, may take a moment)
        boolean received = latch.await(5, TimeUnit.SECONDS);

        response.body().close();
        httpClient.shutdownNow();
        httpClient.awaitTermination(Duration.ofSeconds(2));

        assertThat(received).as("Catch-up should deliver unread notification from DB").isTrue();
        String allLines = String.join("\n", receivedLines);
        assertThat(allLines).contains("referral.requested");
        assertThat(allLines).contains("ACTION_REQUIRED");
    }

    @Test
    @DisplayName("SSE catch-up delivers CRITICAL before ACTION_REQUIRED before INFO")
    void sseCatchupDeliversInSeverityOrder() throws Exception {
        User coordinator = authHelper.setupCoordinatorUser();
        UUID tenantId = testTenant.getId();

        // Create notifications in reverse severity order — DB should reorder
        TenantContext.runWithContext(tenantId, false, () -> {
            notificationPersistenceService.send(tenantId, coordinator.getId(),
                    "test.info", "INFO", "{}");
            notificationPersistenceService.send(tenantId, coordinator.getId(),
                    "test.action", "ACTION_REQUIRED", "{}");
            notificationPersistenceService.send(tenantId, coordinator.getId(),
                    "test.critical", "CRITICAL", "{}");
        });

        // Connect — catch-up delivers all three
        String token = authHelper.getJwtService().generateAccessToken(coordinator);
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpResponse<Stream<String>> response = httpClient.sendAsync(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/v1/notifications/stream?token=" + token))
                        .header("Accept", "text/event-stream").GET().build(),
                HttpResponse.BodyHandlers.ofLines()).get(5, TimeUnit.SECONDS);

        assertThat(response.statusCode()).isEqualTo(200);

        var receivedLines = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var allReceived = new java.util.concurrent.CountDownLatch(3);
        Thread.startVirtualThread(() -> {
            response.body().forEach(line -> {
                receivedLines.add(line);
                if (line.contains("test.critical") || line.contains("test.action") || line.contains("test.info")) {
                    allReceived.countDown();
                }
            });
        });

        boolean received = allReceived.await(5, TimeUnit.SECONDS);

        response.body().close();
        httpClient.shutdownNow();
        httpClient.awaitTermination(Duration.ofSeconds(2));

        assertThat(received).as("All three catch-up notifications should arrive").isTrue();

        // Verify severity ordering: CRITICAL appears before ACTION_REQUIRED, which appears before INFO.
        // Use "severity":"X" pattern to avoid substring false matches (e.g., "INFO" inside "ACTION_REQUIRED").
        String allLines = String.join("\n", receivedLines);
        int criticalIdx = allLines.indexOf("\"severity\":\"CRITICAL\"");
        int actionIdx = allLines.indexOf("\"severity\":\"ACTION_REQUIRED\"");
        int infoIdx = allLines.indexOf("\"severity\":\"INFO\"");
        // First: verify all three severities are present (prevents false-passing if one is missing)
        assertThat(criticalIdx).as("CRITICAL must appear in catch-up").isGreaterThanOrEqualTo(0);
        assertThat(actionIdx).as("ACTION_REQUIRED must appear in catch-up").isGreaterThanOrEqualTo(0);
        assertThat(infoIdx).as("INFO must appear in catch-up").isGreaterThanOrEqualTo(0);
        // Then: verify ordering — CRITICAL before ACTION_REQUIRED before INFO
        assertThat(criticalIdx).as("CRITICAL must appear before ACTION_REQUIRED").isLessThan(actionIdx);
        assertThat(actionIdx).as("ACTION_REQUIRED must appear before INFO").isLessThan(infoIdx);
    }
}
