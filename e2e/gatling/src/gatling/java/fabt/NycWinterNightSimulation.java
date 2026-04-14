package fabt;

import io.gatling.javaapi.core.ScenarioBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * NYC Winter Night Simulation — worst-case concurrent load.
 *
 * Simulates a cold snap night in NYC (10pm-midnight):
 * - 200 concurrent virtual users
 * - 60% bed searches, 20% availability updates, 10% reservations, 10% notifications
 * - Ramp: 0→200 over 5 min, hold 20 min, ramp down 5 min
 *
 * Run against NYC load test data (nyc-loadtest tenant, 500 shelters, 875K availability rows).
 * Start stack with --observability to capture Grafana metrics during the run.
 *
 * Usage:
 *   mvn gatling:test -Pperf -DbaseUrl=http://localhost:8081 \
 *       -Dgatling.simulationClass=fabt.NycWinterNightSimulation
 *
 * SLO: p99 < 1000ms, error rate < 1%
 */
public class NycWinterNightSimulation extends FabtSimulation {

    // --- NYC Loadtest Tenant Tokens ---
    // Acquired from nyc-loadtest tenant (separate from dev-coc)
    private static final String NYC_TENANT_SLUG = "nyc-loadtest";

    private static String acquireNycToken(String email) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            String body = String.format(
                    "{\"tenantSlug\":\"%s\",\"email\":\"%s\",\"password\":\"admin123\"}",
                    NYC_TENANT_SLUG, email);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            Pattern pattern = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(response.body());
            if (matcher.find()) {
                return matcher.group(1);
            }
            throw new RuntimeException("Login failed for " + email + ": " + response.statusCode());
        } catch (Exception e) {
            throw new RuntimeException("NYC token acquisition failed for " + email, e);
        }
    }

    // Fetch shelter IDs from the NYC tenant for realistic feeder data
    private static List<String> fetchShelterIds(String adminToken) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/shelters"))
                    .header("Authorization", "Bearer " + adminToken)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            List<String> ids = new ArrayList<>();
            Pattern pattern = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(response.body());
            while (matcher.find()) {
                ids.add(matcher.group(1));
            }
            return ids;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch shelter IDs", e);
        }
    }

    private static final String NYC_OUTREACH_TOKEN = acquireNycToken("outreach-0000@nyc-loadtest.fabt.org");
    private static final String NYC_COORD_TOKEN = acquireNycToken("coordinator-0000@nyc-loadtest.fabt.org");
    private static final String NYC_ADMIN_TOKEN = acquireNycToken("admin-0000@nyc-loadtest.fabt.org");
    private static final List<String> SHELTER_IDS = fetchShelterIds(NYC_ADMIN_TOKEN);

    private static final Random RANDOM = new Random();
    private static final String[] POP_TYPES = {
            "SINGLE_ADULT", "FAMILY_WITH_CHILDREN", "VETERAN", "WOMEN_ONLY", "YOUTH_18_24"
    };

    // --- Feeders ---

    Iterator<Map<String, Object>> searchFeeder = Stream.generate(
            (Supplier<Map<String, Object>>) () -> Map.of(
                    "query", String.format("{\"populationType\":\"%s\",\"limit\":10}", POP_TYPES[RANDOM.nextInt(POP_TYPES.length)])
            )
    ).iterator();

    Iterator<Map<String, Object>> availabilityFeeder = Stream.generate(
            (Supplier<Map<String, Object>>) () -> {
                String shelterId = SHELTER_IDS.get(RANDOM.nextInt(SHELTER_IDS.size()));
                int occupied = RANDOM.nextInt(50);
                return Map.of(
                        "shelterId", shelterId,
                        "body", String.format(
                                "{\"populationType\":\"SINGLE_ADULT\",\"bedsTotal\":50,\"bedsOccupied\":%d,\"bedsOnHold\":0,\"acceptingNewGuests\":true}",
                                occupied)
                );
            }
    ).iterator();

    // --- Scenarios ---

    ScenarioBuilder bedSearchScenario = scenario("Bed Search (60%)")
            .feed(searchFeeder)
            .exec(
                    http("POST /queries/beds")
                            .post("/api/v1/queries/beds")
                            .header("Authorization", "Bearer " + NYC_OUTREACH_TOKEN)
                            .body(StringBody("#{query}"))
                            .check(status().is(200))
            )
            .pause(Duration.ofMillis(500), Duration.ofMillis(2000));

    ScenarioBuilder availabilityUpdateScenario = scenario("Availability Update (20%)")
            .feed(availabilityFeeder)
            .exec(
                    http("PATCH /shelters/{id}/availability")
                            .patch("/api/v1/shelters/#{shelterId}/availability")
                            .header("Authorization", "Bearer " + NYC_COORD_TOKEN)
                            .body(StringBody("#{body}"))
                            .check(status().in(200, 403)) // 403 if coordinator not assigned to this shelter
            )
            .pause(Duration.ofMillis(1000), Duration.ofMillis(3000));

    ScenarioBuilder notificationScenario = scenario("Notification List (10%)")
            .exec(
                    http("GET /notifications")
                            .get("/api/v1/notifications?page=0&size=20")
                            .header("Authorization", "Bearer " + NYC_OUTREACH_TOKEN)
                            .check(status().is(200))
            )
            .pause(Duration.ofSeconds(2), Duration.ofSeconds(5));

    ScenarioBuilder shelterListScenario = scenario("Shelter List (10%)")
            .exec(
                    http("GET /shelters")
                            .get("/api/v1/shelters")
                            .header("Authorization", "Bearer " + NYC_ADMIN_TOKEN)
                            .check(status().is(200))
            )
            .pause(Duration.ofSeconds(1), Duration.ofSeconds(3));

    // --- Load Profile ---
    // Total: 200 VUs at peak
    // 60% search (120), 20% availability (40), 10% notifications (20), 10% shelter list (20)

    {
        setUp(
                bedSearchScenario.injectOpen(
                        rampUsers(120).during(Duration.ofMinutes(5)),
                        constantUsersPerSec(1).during(Duration.ofMinutes(20)),
                        nothingFor(Duration.ofMinutes(5))
                ),
                availabilityUpdateScenario.injectOpen(
                        rampUsers(40).during(Duration.ofMinutes(5)),
                        constantUsersPerSec(0.33).during(Duration.ofMinutes(20)),
                        nothingFor(Duration.ofMinutes(5))
                ),
                notificationScenario.injectOpen(
                        rampUsers(20).during(Duration.ofMinutes(5)),
                        constantUsersPerSec(0.17).during(Duration.ofMinutes(20)),
                        nothingFor(Duration.ofMinutes(5))
                ),
                shelterListScenario.injectOpen(
                        rampUsers(20).during(Duration.ofMinutes(5)),
                        constantUsersPerSec(0.17).during(Duration.ofMinutes(20)),
                        nothingFor(Duration.ofMinutes(5))
                )
        ).protocols(httpProtocol)
                .assertions(
                        global().responseTime().percentile(99.0).lt(1000),
                        global().failedRequests().percent().lt(1.0)
                );
    }
}
