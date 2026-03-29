package fabt;

import io.gatling.javaapi.core.ScenarioBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * SseSearchConcurrentSimulation — verifies bed search latency is not degraded
 * by concurrent SSE connections.
 *
 * Opens N SSE connections before the Gatling load phase, then runs the standard
 * bed search workload. Asserts bed search p99 stays under SLO threshold.
 *
 * Uses java.net.http.HttpClient for SSE connections (Gatling doesn't natively
 * support SSE streams). SSE connections are opened in setUp and closed in after.
 */
public class SseSearchConcurrentSimulation extends FabtSimulation {

    private static final int SSE_CONNECTION_COUNT = 20;
    private static final CopyOnWriteArrayList<CompletableFuture<?>> sseConnections = new CopyOnWriteArrayList<>();

    private static final String[] QUERIES = {
            "{\"populationType\": \"SINGLE_ADULT\", \"limit\": 10}",
            "{\"populationType\": \"FAMILY_WITH_CHILDREN\", \"constraints\": {\"petsAllowed\": true}, \"limit\": 5}",
            "{\"populationType\": \"SINGLE_ADULT\", \"constraints\": {\"wheelchairAccessible\": true}, \"limit\": 10}",
            "{\"populationType\": \"VETERAN\", \"limit\": 10}"
    };

    private static final Random RANDOM = new Random();

    Iterator<Map<String, Object>> queryFeeder = Stream.generate(
            (Supplier<Map<String, Object>>) () -> Map.of("query", QUERIES[RANDOM.nextInt(QUERIES.length)])
    ).iterator();

    ScenarioBuilder searchScenario = scenario("Bed Search with SSE Connections")
            .feed(queryFeeder)
            .exec(
                    http("POST /api/v1/queries/beds")
                            .post("/api/v1/queries/beds")
                            .header("Authorization", "Bearer " + OUTREACH_TOKEN)
                            .body(StringBody("#{query}"))
                            .check(status().is(200))
            );

    {
        // Open SSE connections before load test starts
        openSseConnections();

        setUp(
                searchScenario.injectOpen(
                        rampUsers(50).during(Duration.ofSeconds(30)),
                        constantUsersPerSec(25).during(Duration.ofMinutes(2)),
                        nothingFor(Duration.ofSeconds(15))
                )
        ).protocols(httpProtocol)
                .assertions(
                        global().responseTime().percentile(50.0).lt(100),
                        global().responseTime().percentile(95.0).lt(500),
                        global().responseTime().percentile(99.0).lt(1000),
                        global().failedRequests().percent().lt(1.0)
                );
    }

    @Override
    public void after() {
        // Cancel all SSE connections
        sseConnections.forEach(f -> f.cancel(true));
        System.out.println("[SSE Load] Closed " + sseConnections.size() + " SSE connections");
    }

    private static void openSseConnections() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        for (int i = 0; i < SSE_CONNECTION_COUNT; i++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/notifications/stream?token=" + OUTREACH_TOKEN))
                    .header("Accept", "text/event-stream")
                    .GET()
                    .build();

            CompletableFuture<HttpResponse<java.util.stream.Stream<String>>> future =
                    httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines());
            sseConnections.add(future);
        }

        System.out.println("[SSE Load] Opened " + SSE_CONNECTION_COUNT + " SSE connections");
    }
}
