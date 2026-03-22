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
 * BedSearchSimulation — exercises POST /api/v1/queries/beds under load.
 *
 * Load profile: Ramp 1→50 VUs over 30s, hold 2 min, ramp down 15s.
 * 4 payload variants for realistic query diversity.
 *
 * SLO assertions:
 *   p50 < 100ms, p95 < 500ms, p99 < 1000ms, < 1% error rate
 */
public class BedSearchSimulation extends FabtSimulation {

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

    ScenarioBuilder searchScenario = scenario("Bed Search")
            .feed(queryFeeder)
            .exec(
                    http("POST /api/v1/queries/beds")
                            .post("/api/v1/queries/beds")
                            .header("Authorization", "Bearer " + OUTREACH_TOKEN)
                            .body(StringBody("#{query}"))
                            .check(status().is(200))
            );

    {
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
}
