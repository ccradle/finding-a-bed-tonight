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

    // Shelter ID → population type mapping (must match seed data constraints)
    private static final String[][] SHELTERS = {
            {"d0000000-0000-0000-0000-000000000004", "SINGLE_ADULT"},     // Oak City
            {"d0000000-0000-0000-0000-000000000005", "SINGLE_ADULT"},     // South Wilmington
            {"d0000000-0000-0000-0000-000000000006", "SINGLE_ADULT"},     // Downtown Warming
            {"d0000000-0000-0000-0000-000000000010", "SINGLE_ADULT"},     // Helping Hand
            {"d0000000-0000-0000-0000-000000000001", "FAMILY_WITH_CHILDREN"}, // Crabtree Valley
            {"d0000000-0000-0000-0000-000000000002", "FAMILY_WITH_CHILDREN"}, // Capital Blvd
            {"d0000000-0000-0000-0000-000000000003", "FAMILY_WITH_CHILDREN"}, // New Beginnings
            {"d0000000-0000-0000-0000-000000000007", "VETERAN"},          // Wake County Veterans
            {"d0000000-0000-0000-0000-000000000008", "YOUTH_18_24"},      // Youth Hope
            {"d0000000-0000-0000-0000-000000000004", "SINGLE_ADULT"},     // Oak City (repeat for load)
    };

    private static final Random RANDOM = new Random();

    Iterator<Map<String, Object>> shelterFeeder = Stream.generate(
            (Supplier<Map<String, Object>>) () -> {
                int idx = RANDOM.nextInt(SHELTERS.length);
                int occupied = RANDOM.nextInt(50);
                return Map.of(
                        "shelterId", SHELTERS[idx][0],
                        "body", String.format(
                                "{\"populationType\":\"%s\",\"bedsTotal\":50,\"bedsOccupied\":%d,\"bedsOnHold\":0,\"acceptingNewGuests\":true}",
                                SHELTERS[idx][1], occupied)
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

    // Scenario B — same-shelter concurrent updates (Oak City — SINGLE_ADULT)
    ScenarioBuilder scenarioB = scenario("Same-Shelter Updates")
            .during(Duration.ofMinutes(1)).on(
                    exec(
                            http("PATCH availability (same-shelter)")
                                    .patch("/api/v1/shelters/d0000000-0000-0000-0000-000000000004/availability")
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
