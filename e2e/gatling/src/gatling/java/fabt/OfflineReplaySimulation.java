package fabt;

import io.gatling.javaapi.core.ScenarioBuilder;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * OfflineReplaySimulation — exercises the offline queue replay path.
 *
 * Simulates what happens when a shelter's WiFi comes back and multiple
 * coordinators/outreach workers reconnect simultaneously, replaying
 * their queued actions.
 *
 * Scenario A: 5 users replay holds for the SAME shelter simultaneously.
 *   Tests advisory lock contention (pg_advisory_xact_lock serialization).
 *   SLO: p95 < 2000ms, 0% duplicates.
 *
 * Scenario B: Same user replays same idempotency key 5 times rapidly.
 *   Tests dedup short-circuit (must skip advisory lock entirely).
 *   SLO: p95 < 100ms (after the first create).
 *
 * Scenario C: 5 users replay mixed actions (holds + availability updates).
 *   Tests connection pool pressure with mixed lock/no-lock operations.
 *   SLO: p95 < 1000ms, < 1% errors.
 */
public class OfflineReplaySimulation extends FabtSimulation {

    // Use real shelters with matching population types for their constraints
    private static final String TARGET_SHELTER = "d0000000-0000-0000-0000-000000000004"; // Oak City Community Shelter (SINGLE_ADULT)
    private static final String MIXED_SHELTER = "d0000000-0000-0000-0000-000000000001"; // Crabtree Valley Family Haven (FAMILY_WITH_CHILDREN)

    // Track idempotency keys for verification
    private static final AtomicInteger holdCount = new AtomicInteger(0);

    // -------------------------------------------------------------------------
    // Scenario A: Concurrent hold replay — same shelter, different users
    // Simulates: 5 outreach workers reconnect after WiFi outage, all held beds
    // at the same shelter while offline, all replay simultaneously.
    // -------------------------------------------------------------------------

    Iterator<Map<String, Object>> concurrentHoldFeeder = Stream.generate(
            (Supplier<Map<String, Object>>) () -> {
                String key = UUID.randomUUID().toString();
                return Map.of(
                        "idempotencyKey", key,
                        "body", String.format(
                                "{\"shelterId\":\"%s\",\"populationType\":\"SINGLE_ADULT\"}",
                                TARGET_SHELTER)
                );
            }
    ).iterator();

    ScenarioBuilder scenarioA = scenario("A: Concurrent Hold Replay (same shelter)")
            .feed(concurrentHoldFeeder)
            .exec(
                    http("POST reservation (concurrent replay)")
                            .post("/api/v1/reservations")
                            .header("Authorization", "Bearer " + OUTREACH_TOKEN)
                            .header("X-Idempotency-Key", "#{idempotencyKey}")
                            .body(StringBody("#{body}"))
                            .check(status().in(201, 409)) // 201 = created, 409 = no beds left (expected under contention)
            );

    // -------------------------------------------------------------------------
    // Scenario B: Idempotent replay — same key, rapid fire
    // Simulates: app-level queue replays a hold, but user triggers online event
    // again before the first replay completes (belt-and-suspenders dedup).
    // The backend should return the existing reservation without acquiring
    // the advisory lock.
    // -------------------------------------------------------------------------

    private static final String IDEMPOTENT_KEY = UUID.randomUUID().toString();

    ScenarioBuilder scenarioB = scenario("B: Idempotent Replay (same key)")
            .exec(
                    // First call creates the reservation
                    http("POST reservation (first - creates)")
                            .post("/api/v1/reservations")
                            .header("Authorization", "Bearer " + OUTREACH_TOKEN)
                            .header("X-Idempotency-Key", IDEMPOTENT_KEY)
                            .body(StringBody(String.format(
                                    "{\"shelterId\":\"%s\",\"populationType\":\"SINGLE_ADULT\"}",
                                    TARGET_SHELTER)))
                            .check(status().in(201, 409))
            )
            .pause(Duration.ofMillis(50))
            .repeat(4).on(
                    exec(
                            // Subsequent calls should short-circuit via idempotency check
                            http("POST reservation (idempotent replay)")
                                    .post("/api/v1/reservations")
                                    .header("Authorization", "Bearer " + OUTREACH_TOKEN)
                                    .header("X-Idempotency-Key", IDEMPOTENT_KEY)
                                    .body(StringBody(String.format(
                                            "{\"shelterId\":\"%s\",\"populationType\":\"SINGLE_ADULT\"}",
                                            TARGET_SHELTER)))
                                    .check(status().in(200, 409)) // 200 = idempotent match, 409 = beds gone
                    ).pause(Duration.ofMillis(20))
            );

    // -------------------------------------------------------------------------
    // Scenario C: Mixed replay storm — holds + availability interleaved
    // Simulates: coordinators and outreach workers reconnect simultaneously,
    // replaying a mix of bed holds (advisory lock) and availability updates
    // (no advisory lock). Tests connection pool under mixed workload.
    // -------------------------------------------------------------------------

    Iterator<Map<String, Object>> mixedFeeder = Stream.generate(
            (Supplier<Map<String, Object>>) () -> {
                String key = UUID.randomUUID().toString();
                int occupied = 5 + (int) (Math.random() * 40);
                return Map.of(
                        "idempotencyKey", key,
                        "occupied", String.valueOf(occupied)
                );
            }
    ).iterator();

    ScenarioBuilder scenarioC = scenario("C: Mixed Replay Storm")
            .feed(mixedFeeder)
            // Action 1: Hold a bed (advisory lock path)
            .exec(
                    http("POST reservation (mixed - hold)")
                            .post("/api/v1/reservations")
                            .header("Authorization", "Bearer " + OUTREACH_TOKEN)
                            .header("X-Idempotency-Key", "#{idempotencyKey}")
                            .body(StringBody(String.format(
                                    "{\"shelterId\":\"%s\",\"populationType\":\"SINGLE_ADULT\"}",
                                    MIXED_SHELTER)))
                            .check(status().in(201, 409))
            )
            .pause(Duration.ofMillis(100))
            // Action 2: Availability update (no advisory lock)
            .exec(
                    http("PATCH availability (mixed - update)")
                            .patch(String.format("/api/v1/shelters/%s/availability", MIXED_SHELTER))
                            .header("Authorization", "Bearer " + COCADMIN_TOKEN)
                            .body(StringBody("{\"populationType\":\"SINGLE_ADULT\",\"bedsTotal\":50,\"bedsOccupied\":#{occupied},\"bedsOnHold\":0,\"acceptingNewGuests\":true}"))
                            .check(status().is(200))
            )
            .pause(Duration.ofMillis(100))
            // Action 3: Another hold (different population type)
            .exec(
                    http("POST reservation (mixed - hold 2)")
                            .post("/api/v1/reservations")
                            .header("Authorization", "Bearer " + OUTREACH_TOKEN)
                            .header("X-Idempotency-Key", UUID.randomUUID().toString())
                            .body(StringBody(String.format(
                                    "{\"shelterId\":\"%s\",\"populationType\":\"FAMILY_WITH_CHILDREN\"}",
                                    MIXED_SHELTER)))
                            .check(status().in(201, 409))
            );

    // -------------------------------------------------------------------------
    // Scenario D: Shelter WiFi outage — 10 users, same shelter
    // Realistic large-shelter scenario (e.g., Capital Boulevard, 50 beds,
    // 8-10 staff). All reconnect within ~100ms.
    // -------------------------------------------------------------------------

    Iterator<Map<String, Object>> shelterOutageFeeder = Stream.generate(
            (Supplier<Map<String, Object>>) () -> Map.of(
                    "idempotencyKey", UUID.randomUUID().toString(),
                    "body", String.format(
                            "{\"shelterId\":\"%s\",\"populationType\":\"SINGLE_ADULT\"}",
                            TARGET_SHELTER)
            )
    ).iterator();

    ScenarioBuilder scenarioD = scenario("D: Shelter WiFi Outage (10 users)")
            .feed(shelterOutageFeeder)
            .exec(
                    http("POST reservation (shelter outage)")
                            .post("/api/v1/reservations")
                            .header("Authorization", "Bearer " + OUTREACH_TOKEN)
                            .header("X-Idempotency-Key", "#{idempotencyKey}")
                            .body(StringBody("#{body}"))
                            .check(status().in(201, 409))
            );

    // -------------------------------------------------------------------------
    // Scenario E: Citywide ISP outage — 20 users across 4 shelters
    // Tests connection pool pressure with distributed advisory lock contention.
    // 20 users = 100% of HikariCP pool (default 20).
    // -------------------------------------------------------------------------

    private static final String[] CITY_SHELTERS = {
            "d0000000-0000-0000-0000-000000000001",
            "d0000000-0000-0000-0000-000000000002",
            "d0000000-0000-0000-0000-000000000004",
            "d0000000-0000-0000-0000-000000000006",
    };

    Iterator<Map<String, Object>> cityOutageFeeder = Stream.generate(
            (Supplier<Map<String, Object>>) () -> {
                String shelter = CITY_SHELTERS[(int) (Math.random() * CITY_SHELTERS.length)];
                return Map.of(
                        "idempotencyKey", UUID.randomUUID().toString(),
                        "shelterId", shelter,
                        "body", String.format(
                                "{\"shelterId\":\"%s\",\"populationType\":\"SINGLE_ADULT\"}",
                                shelter)
                );
            }
    ).iterator();

    ScenarioBuilder scenarioE = scenario("E: Citywide ISP Outage (20 users, 4 shelters)")
            .feed(cityOutageFeeder)
            .exec(
                    http("POST reservation (citywide outage)")
                            .post("/api/v1/reservations")
                            .header("Authorization", "Bearer " + OUTREACH_TOKEN)
                            .header("X-Idempotency-Key", "#{idempotencyKey}")
                            .body(StringBody("#{body}"))
                            .check(status().in(201, 409))
            );

    // -------------------------------------------------------------------------
    // Setup: run scenarios sequentially to isolate metrics.
    // -------------------------------------------------------------------------

    {
        setUp(
                // A: 5 concurrent users, all at once
                scenarioA.injectOpen(atOnceUsers(5)),

                // B: 1 user doing idempotent replays (after A)
                scenarioB.injectOpen(
                        nothingFor(Duration.ofSeconds(10)),
                        atOnceUsers(1)
                ),

                // C: 5 users with mixed workloads (after B)
                scenarioC.injectOpen(
                        nothingFor(Duration.ofSeconds(20)),
                        atOnceUsers(5)
                ),

                // D: 10 users, same shelter (shelter WiFi outage)
                scenarioD.injectOpen(
                        nothingFor(Duration.ofSeconds(30)),
                        atOnceUsers(10)
                ),

                // E: 20 users, 4 shelters (citywide ISP outage — pool pressure test)
                scenarioE.injectOpen(
                        nothingFor(Duration.ofSeconds(40)),
                        atOnceUsers(20)
                )
        ).protocols(httpProtocol)
                .assertions(
                        // A: 5-user advisory lock contention
                        details("POST reservation (concurrent replay)")
                                .responseTime().percentile(95.0).lt(2000),

                        // B: idempotent replay short-circuit
                        details("POST reservation (idempotent replay)")
                                .responseTime().percentile(95.0).lt(100),

                        // C: mixed workload — no unexpected errors
                        details("PATCH availability (mixed - update)")
                                .failedRequests().percent().lt(1.0),

                        // D: 10-user shelter outage — advisory lock under real load
                        details("POST reservation (shelter outage)")
                                .responseTime().percentile(95.0).lt(3000),

                        // E: 20-user citywide outage — connection pool must not exhaust
                        details("POST reservation (citywide outage)")
                                .responseTime().percentile(95.0).lt(5000)
                );
    }
}
