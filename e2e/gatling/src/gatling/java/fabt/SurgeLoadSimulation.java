package fabt;

import io.gatling.javaapi.core.ScenarioBuilder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * SurgeLoadSimulation — STUB. Full implementation ready (surge-mode is complete).
 *
 * Runs a minimal bed search to validate compilation.
 * Full 4-phase simulation (baseline → surge activate → spike → deactivate)
 * to be implemented when surge load testing is prioritized.
 */
public class SurgeLoadSimulation extends FabtSimulation {

    ScenarioBuilder placeholder = scenario("Surge Placeholder")
            .exec(
                    http("Placeholder bed search")
                            .post("/api/v1/queries/beds")
                            .header("Authorization", "Bearer " + OUTREACH_TOKEN)
                            .body(StringBody("{\"limit\": 1}"))
                            .check(status().is(200))
            );

    {
        setUp(
                placeholder.injectOpen(atOnceUsers(1))
        ).protocols(httpProtocol);
    }
}
