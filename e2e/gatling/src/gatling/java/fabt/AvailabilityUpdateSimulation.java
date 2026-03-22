package fabt;

import io.gatling.javaapi.core.ScenarioBuilder;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * AvailabilityUpdateSimulation — exercises PATCH /api/v1/shelters/{id}/availability.
 *
 * Scenario A: 10 coordinators, different shelters, 2 min (normal operation)
 * Scenario B: 5 coordinators, same shelter, 1 min (ON CONFLICT stress test)
 *
 * SLO: p95 < 200ms, < 1% errors
 */
public class AvailabilityUpdateSimulation extends FabtSimulation {

    private static final String[] SHELTER_IDS = {
            "d0000000-0000-0000-0000-000000000001",
            "d0000000-0000-0000-0000-000000000002",
            "d0000000-0000-0000-0000-000000000003",
            "d0000000-0000-0000-0000-000000000004",
            "d0000000-0000-0000-0000-000000000005",
            "d0000000-0000-0000-0000-000000000006",
            "d0000000-0000-0000-0000-000000000007",
            "d0000000-0000-0000-0000-000000000008",
            "d0000000-0000-0000-0000-000000000010",
            "d0000000-0000-0000-0000-000000000001"
    };

    private static final Random RANDOM = new Random();

    Iterator<Map<String, Object>> shelterFeeder = Stream.generate(
            (Supplier<Map<String, Object>>) () -> {
                int idx = RANDOM.nextInt(SHELTER_IDS.length);
                int occupied = RANDOM.nextInt(50);
                return Map.of(
                        "shelterId", SHELTER_IDS[idx],
                        "body", String.format(
                                "{\"populationType\":\"SINGLE_ADULT\",\"bedsTotal\":50,\"bedsOccupied\":%d,\"bedsOnHold\":0,\"acceptingNewGuests\":true}",
                                occupied)
                );
            }
    ).iterator();

    // Scenario A — multi-shelter concurrent updates
    ScenarioBuilder scenarioA = scenario("Multi-Shelter Updates")
            .feed(shelterFeeder)
            .during(Duration.ofMinutes(2)).on(
                    exec(
                            http("PATCH availability (multi-shelter)")
                                    .patch("/api/v1/shelters/#{shelterId}/availability")
                                    .header("Authorization", "Bearer " + COCADMIN_TOKEN)
                                    .body(StringBody("#{body}"))
                                    .check(status().is(200))
                    ).pause(Duration.ofSeconds(5))
            );

    // Scenario B — same-shelter concurrent updates
    ScenarioBuilder scenarioB = scenario("Same-Shelter Updates")
            .during(Duration.ofMinutes(1)).on(
                    exec(
                            http("PATCH availability (same-shelter)")
                                    .patch("/api/v1/shelters/d0000000-0000-0000-0000-000000000001/availability")
                                    .header("Authorization", "Bearer " + COCADMIN_TOKEN)
                                    .body(StringBody("{\"populationType\":\"SINGLE_ADULT\",\"bedsTotal\":50,\"bedsOccupied\":" + RANDOM.nextInt(50) + ",\"bedsOnHold\":0,\"acceptingNewGuests\":true}"))
                                    .check(status().is(200))
                    ).pause(Duration.ofSeconds(2))
            );

    {
        setUp(
                scenarioA.injectOpen(atOnceUsers(10)),
                scenarioB.injectOpen(
                        nothingFor(Duration.ofMinutes(2).plusSeconds(10)),
                        atOnceUsers(5)
                )
        ).protocols(httpProtocol)
                .assertions(
                        global().responseTime().percentile(95.0).lt(200),
                        global().failedRequests().percent().lt(1.0)
                );
    }
}
