package fabt

import io.gatling.core.Predef._
import io.gatling.http.Predef._

/**
 * SurgeLoadSimulation — STUB. Full implementation requires surge-mode OpenSpec change.
 *
 * Spec (for post-surge-mode implementation):
 *
 * Phase 1 — Pre-surge baseline (2 min): 20 concurrent users querying beds
 * Phase 2 — Surge activation (instant): Admin POST /api/v1/surge-events
 * Phase 3 — Post-surge spike (3 min): Ramp 20→100 VUs in 15s, hold 2 min
 * Phase 4 — Surge deactivation (instant): Admin deactivates surge
 *
 * SLO (post-surge spike):
 *   p95 < 1000ms (2x normal threshold during spike)
 *   < 2% error rate (connection pool exhaustion / cache stampede acceptable)
 *
 * TODO: Implement after surge-mode OpenSpec change is complete.
 *       Requires POST /api/v1/surge-events endpoint.
 */
class SurgeLoadSimulation extends FabtSimulation {

  // Placeholder scenario — runs a minimal bed search to validate compilation
  val placeholder = scenario("Surge Placeholder")
    .exec(
      http("Placeholder bed search")
        .post("/api/v1/queries/beds")
        .header("Authorization", s"Bearer $outreachToken")
        .body(StringBody("""{"limit": 1}"""))
        .check(status.is(200))
    )

  setUp(
    placeholder.inject(atOnceUsers(1))
  ).protocols(httpProtocol)
}
