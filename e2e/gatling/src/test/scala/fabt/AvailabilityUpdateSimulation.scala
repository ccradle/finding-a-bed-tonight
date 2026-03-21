package fabt

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

/**
 * AvailabilityUpdateSimulation — exercises PATCH /api/v1/shelters/{id}/availability.
 *
 * Scenario A: 10 coordinators, different shelters, 2 min (normal operation)
 * Scenario B: 5 coordinators, same shelter, 1 min (ON CONFLICT stress test)
 *
 * SLO: p95 < 200ms, < 1% errors
 * Post-sim: bedsAvailable must never be negative
 */
class AvailabilityUpdateSimulation extends FabtSimulation {

  val shelterIds = Array(
    "d0000000-0000-0000-0000-000000000001",
    "d0000000-0000-0000-0000-000000000002",
    "d0000000-0000-0000-0000-000000000003",
    "d0000000-0000-0000-0000-000000000004",
    "d0000000-0000-0000-0000-000000000005",
    "d0000000-0000-0000-0000-000000000006",
    "d0000000-0000-0000-0000-000000000007",
    "d0000000-0000-0000-0000-000000000008",
    "d0000000-0000-0000-0000-000000000010",
    "d0000000-0000-0000-0000-000000000001" // wrap for 10th
  )

  val multiShelterFeeder = Iterator.from(0).map(i => Map("shelterId" -> shelterIds(i % shelterIds.length)))

  // Scenario A — multi-shelter concurrent updates (normal operation)
  val scenarioA = scenario("Multi-Shelter Updates")
    .feed(multiShelterFeeder)
    .during(2.minutes) {
      exec(
        http("PATCH availability (multi-shelter)")
          .patch("/api/v1/shelters/${shelterId}/availability")
          .header("Authorization", s"Bearer $cocadminToken")
          .body(StringBody(
            """{"populationType":"SINGLE_ADULT","bedsTotal":50,"bedsOccupied":""" +
              scala.util.Random.nextInt(50) +
              ""","bedsOnHold":0,"acceptingNewGuests":true}"""
          ))
          .check(status.is(200))
      ).pause(5.seconds)
    }

  // Scenario B — same-shelter concurrent updates (stress test)
  val scenarioB = scenario("Same-Shelter Updates")
    .during(1.minute) {
      exec(
        http("PATCH availability (same-shelter)")
          .patch("/api/v1/shelters/d0000000-0000-0000-0000-000000000001/availability")
          .header("Authorization", s"Bearer $cocadminToken")
          .body(StringBody(
            """{"populationType":"SINGLE_ADULT","bedsTotal":50,"bedsOccupied":""" +
              scala.util.Random.nextInt(50) +
              ""","bedsOnHold":0,"acceptingNewGuests":true}"""
          ))
          .check(status.is(200))
      ).pause(2.seconds)
    }

  setUp(
    scenarioA.inject(atOnceUsers(10)),
    scenarioB.inject(nothingFor(2.minutes + 10.seconds), atOnceUsers(5))
  ).protocols(httpProtocol)
    .assertions(
      global.responseTime.percentile(95).lt(200),
      global.failedRequests.percent.lt(1)
    )
}
