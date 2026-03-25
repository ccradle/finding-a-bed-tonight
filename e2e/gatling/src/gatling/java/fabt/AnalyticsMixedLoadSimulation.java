package fabt;

import io.gatling.javaapi.core.ScenarioBuilder;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * AnalyticsMixedLoadSimulation — verifies OLTP bed search performance is NOT degraded
 * when analytics queries run concurrently (Design D15).
 *
 * Baseline OLTP: 50 bed searches + 20 availability updates
 * Concurrent analytics: 3 queries (utilization, demand, HIC export)
 *
 * SLO: bed search p99 must stay under 200ms during mixed load.
 * Analytics queries: under 5s.
 */
public class AnalyticsMixedLoadSimulation extends FabtSimulation {

    private static final String[] QUERIES = {
            "{\"populationType\": \"SINGLE_ADULT\", \"limit\": 10}",
            "{\"populationType\": \"FAMILY_WITH_CHILDREN\", \"constraints\": {\"petsAllowed\": true}, \"limit\": 5}",
            "{\"populationType\": \"VETERAN\", \"limit\": 10}",
            "{\"limit\": 10}"
    };

    private static final Random RANDOM = new Random();
    private static final String TODAY = LocalDate.now().toString();
    private static final String THIRTY_DAYS_AGO = LocalDate.now().minusDays(30).toString();

    Iterator<Map<String, Object>> queryFeeder = Stream.generate(
            (Supplier<Map<String, Object>>) () -> Map.of("query", QUERIES[RANDOM.nextInt(QUERIES.length)])
    ).iterator();

    // OLTP: bed search scenario (50 concurrent users)
    ScenarioBuilder bedSearchScenario = scenario("OLTP Bed Search")
            .feed(queryFeeder)
            .exec(
                    http("POST /api/v1/queries/beds")
                            .post("/api/v1/queries/beds")
                            .header("Authorization", "Bearer " + OUTREACH_TOKEN)
                            .body(StringBody("#{query}"))
                            .check(status().is(200))
            );

    // OLTP: availability update scenario (20 concurrent users)
    ScenarioBuilder availUpdateScenario = scenario("OLTP Availability Update")
            .exec(
                    http("GET shelters")
                            .get("/api/v1/shelters")
                            .header("Authorization", "Bearer " + COCADMIN_TOKEN)
                            .check(status().is(200))
            );

    // Analytics: utilization query
    ScenarioBuilder utilizationScenario = scenario("Analytics Utilization")
            .exec(
                    http("GET /api/v1/analytics/utilization")
                            .get("/api/v1/analytics/utilization?from=" + THIRTY_DAYS_AGO + "&to=" + TODAY + "&granularity=daily")
                            .header("Authorization", "Bearer " + COCADMIN_TOKEN)
                            .check(status().is(200))
            );

    // Analytics: demand query
    ScenarioBuilder demandScenario = scenario("Analytics Demand")
            .exec(
                    http("GET /api/v1/analytics/demand")
                            .get("/api/v1/analytics/demand?from=" + THIRTY_DAYS_AGO + "&to=" + TODAY)
                            .header("Authorization", "Bearer " + COCADMIN_TOKEN)
                            .check(status().is(200))
            );

    // Analytics: HIC export
    ScenarioBuilder hicExportScenario = scenario("Analytics HIC Export")
            .exec(
                    http("GET /api/v1/analytics/hic")
                            .get("/api/v1/analytics/hic?date=" + TODAY)
                            .header("Authorization", "Bearer " + COCADMIN_TOKEN)
                            .check(status().is(200))
            );

    {
        setUp(
                // Baseline OLTP load
                bedSearchScenario.injectOpen(
                        rampUsers(50).during(Duration.ofSeconds(15)),
                        constantUsersPerSec(25).during(Duration.ofMinutes(2))
                ),
                availUpdateScenario.injectOpen(
                        rampUsers(20).during(Duration.ofSeconds(15)),
                        constantUsersPerSec(10).during(Duration.ofMinutes(2))
                ),
                // Concurrent analytics load (starts after 15s warm-up)
                utilizationScenario.injectOpen(
                        nothingFor(Duration.ofSeconds(15)),
                        constantUsersPerSec(1).during(Duration.ofMinutes(2))
                ),
                demandScenario.injectOpen(
                        nothingFor(Duration.ofSeconds(15)),
                        constantUsersPerSec(1).during(Duration.ofMinutes(2))
                ),
                hicExportScenario.injectOpen(
                        nothingFor(Duration.ofSeconds(15)),
                        constantUsersPerSec(0.5).during(Duration.ofMinutes(2))
                )
        ).protocols(httpProtocol)
                .assertions(
                        // Bed search p99 must stay under 200ms during analytics load
                        details("POST /api/v1/queries/beds").responseTime().percentile(99.0).lt(200),
                        // Analytics queries: under 5s
                        details("GET /api/v1/analytics/utilization").responseTime().percentile(99.0).lt(5000),
                        details("GET /api/v1/analytics/demand").responseTime().percentile(99.0).lt(5000),
                        details("GET /api/v1/analytics/hic").responseTime().percentile(99.0).lt(5000),
                        // Error rates
                        global().failedRequests().percent().lt(1.0)
                );
    }
}
