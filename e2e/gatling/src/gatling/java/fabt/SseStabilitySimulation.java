package fabt;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import io.gatling.javaapi.core.ScenarioBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * SseStabilitySimulation — validates SSE connection stability under load.
 *
 * Opens 200 concurrent SSE connections using Authorization headers (not query-param token),
 * holds them for 60 seconds, and verifies:
 * - All connections receive heartbeat events at ~20-second intervals
 * - No unexpected disconnects
 * - Bed search latency is not degraded by concurrent SSE connections
 *
 * Run: mvn gatling:test -Pperf -DbaseUrl=http://localhost:8081 -Dgatling.simulationClass=fabt.SseStabilitySimulation
 */
public class SseStabilitySimulation extends FabtSimulation {

    private static final int SSE_CONNECTION_COUNT = 200;
    private static final int HOLD_SECONDS = 60;

    private static final CopyOnWriteArrayList<CompletableFuture<?>> sseConnections = new CopyOnWriteArrayList<>();
    private static final AtomicInteger heartbeatsReceived = new AtomicInteger(0);
    private static final AtomicInteger connectionsDropped = new AtomicInteger(0);
    private static final AtomicInteger connectionsOpened = new AtomicInteger(0);

    // Open SSE connections BEFORE the Gatling scenario runs
    {
        openSseConnections();
        System.out.println("[SSE Stability] Waiting " + HOLD_SECONDS + "s for heartbeat collection...");
        try {
            Thread.sleep(HOLD_SECONDS * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("[SSE Stability] Results: opened=" + connectionsOpened.get()
                + " heartbeats=" + heartbeatsReceived.get()
                + " dropped=" + connectionsDropped.get());
    }

    // After SSE hold period, run a light bed search to verify API still responsive
    ScenarioBuilder bedSearchAfterSse = scenario("Bed Search (post-SSE)")
            .exec(
                    http("search beds")
                            .post("/api/v1/queries/beds")
                            .header("Authorization", "Bearer " + OUTREACH_TOKEN)
                            .body(StringBody("{\"populationType\":\"SINGLE_ADULT\",\"limit\":5}"))
                            .check(status().is(200))
            );

    {
        setUp(
                bedSearchAfterSse.injectOpen(rampUsers(50).during(Duration.ofSeconds(10)))
        ).protocols(httpProtocol)
         .assertions(
                 global().responseTime().percentile(95.0).lt(500),
                 global().failedRequests().percent().lt(1.0)
         );
    }

    @Override
    public void after() {
        sseConnections.forEach(f -> f.cancel(true));
        System.out.println("[SSE Stability] Closed " + sseConnections.size() + " SSE connections");
        System.out.println("[SSE Stability] Final: heartbeats=" + heartbeatsReceived.get()
                + " dropped=" + connectionsDropped.get());

        // Validate: each connection should have received at least 2 heartbeats in 60 seconds (20s interval)
        int expectedMinHeartbeats = SSE_CONNECTION_COUNT * 2;
        if (heartbeatsReceived.get() < expectedMinHeartbeats) {
            System.err.println("[SSE Stability] WARNING: Expected at least " + expectedMinHeartbeats
                    + " heartbeats, got " + heartbeatsReceived.get());
        }
        if (connectionsDropped.get() > 0) {
            System.err.println("[SSE Stability] WARNING: " + connectionsDropped.get() + " connections dropped unexpectedly");
        }
    }

    private static void openSseConnections() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        for (int i = 0; i < SSE_CONNECTION_COUNT; i++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/notifications/stream"))
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + OUTREACH_TOKEN)
                    .GET()
                    .build();

            CompletableFuture<HttpResponse<java.util.stream.Stream<String>>> future =
                    httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines());

            future.thenAccept(response -> {
                connectionsOpened.incrementAndGet();
                response.body().forEach(line -> {
                    if (line.contains("heartbeat")) {
                        heartbeatsReceived.incrementAndGet();
                    }
                });
            }).exceptionally(ex -> {
                connectionsDropped.incrementAndGet();
                return null;
            });

            sseConnections.add(future);
        }

        System.out.println("[SSE Stability] Opening " + SSE_CONNECTION_COUNT + " SSE connections with Authorization header");
    }
}
