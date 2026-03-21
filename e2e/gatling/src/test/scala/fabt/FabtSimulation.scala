package fabt

import io.gatling.core.Predef._
import io.gatling.http.Predef._

/**
 * Base simulation class — shared HTTP protocol config and JWT token acquisition.
 * All FABT Gatling simulations extend this class.
 *
 * Token is acquired once in setUp and shared across all virtual users
 * (per portfolio hard-won lesson #14: acquire once, not per VU).
 */
abstract class FabtSimulation extends Simulation {

  val baseUrl: String = System.getProperty("baseUrl", "http://localhost:8080")
  val tenantSlug: String = "dev-coc"
  val adminEmail: String = "admin@dev.fabt.org"
  val adminPassword: String = "admin123"
  val outreachEmail: String = "outreach@dev.fabt.org"
  val outreachPassword: String = "admin123"
  val cocadminEmail: String = "cocadmin@dev.fabt.org"
  val cocadminPassword: String = "admin123"

  // Acquire token once for the simulation
  def acquireToken(email: String, password: String): String = {
    import java.net.URI
    import java.net.http.{HttpClient, HttpRequest, HttpResponse}

    val client = HttpClient.newHttpClient()
    val body = s"""{"tenantSlug":"$tenantSlug","email":"$email","password":"$password"}"""
    val request = HttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl/api/v1/auth/login"))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    val tokenRegex = """"accessToken"\s*:\s*"([^"]+)"""".r
    tokenRegex.findFirstMatchIn(response.body()).map(_.group(1)).getOrElse(
      throw new RuntimeException(s"Failed to acquire token: ${response.statusCode()} ${response.body()}")
    )
  }

  lazy val outreachToken: String = acquireToken(outreachEmail, outreachPassword)
  lazy val cocadminToken: String = acquireToken(cocadminEmail, cocadminPassword)

  val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
}
