package fabt

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

/**
 * BedSearchSimulation — exercises POST /api/v1/queries/beds under load.
 *
 * Load profile: Ramp 1→50 VUs over 30s, hold 2 min, ramp down 15s.
 * 4 payload variants for realistic query diversity.
 *
 * SLO assertions:
 *   p50 < 100ms, p95 < 500ms, p99 < 1000ms, < 1% error rate
 */
class BedSearchSimulation extends FabtSimulation {

  val queries = Array(
    """{"populationType": "SINGLE_ADULT", "limit": 10}""",
    """{"populationType": "FAMILY_WITH_CHILDREN", "constraints": {"petsAllowed": true}, "limit": 5}""",
    """{"populationType": "SINGLE_ADULT", "constraints": {"wheelchairAccessible": true}, "limit": 10}""",
    """{"populationType": "VETERAN", "limit": 10}"""
  )

  val queryFeeder = Iterator.continually(Map("query" -> queries(scala.util.Random.nextInt(queries.length))))

  val searchScenario = scenario("Bed Search")
    .feed(queryFeeder)
    .exec(
      http("POST /api/v1/queries/beds")
        .post("/api/v1/queries/beds")
        .header("Authorization", s"Bearer $outreachToken")
        .body(StringBody("${query}"))
        .check(status.is(200))
    )

  setUp(
    searchScenario.inject(
      rampUsers(50).during(30.seconds),
      constantUsersPerSec(25).during(2.minutes),
      rampUsers(0).during(15.seconds)
    )
  ).protocols(httpProtocol)
    .assertions(
      global.responseTime.percentile(50).lt(100),
      global.responseTime.percentile(95).lt(500),
      global.responseTime.percentile(99).lt(1000),
      global.failedRequests.percent.lt(1)
    )
}
