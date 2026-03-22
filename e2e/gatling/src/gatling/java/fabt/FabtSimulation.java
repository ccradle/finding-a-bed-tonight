package fabt;

import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.gatling.javaapi.http.HttpDsl.http;

/**
 * Base simulation class — shared HTTP protocol config and JWT token acquisition.
 * All FABT Gatling simulations extend this class.
 *
 * Token is acquired once per simulation, not per virtual user
 * (per portfolio hard-won lesson #14).
 */
public abstract class FabtSimulation extends Simulation {

    protected static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8080");
    protected static final String TENANT_SLUG = "dev-coc";

    protected static String acquireToken(String email, String password) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            String body = String.format(
                    "{\"tenantSlug\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}",
                    TENANT_SLUG, email, password);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            Pattern pattern = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(response.body());
            if (matcher.find()) {
                return matcher.group(1);
            }
            throw new RuntimeException("No accessToken in response: " + response.statusCode());
        } catch (Exception e) {
            throw new RuntimeException("Token acquisition failed", e);
        }
    }

    protected static final String OUTREACH_TOKEN = acquireToken("outreach@dev.fabt.org", "admin123");
    protected static final String COCADMIN_TOKEN = acquireToken("cocadmin@dev.fabt.org", "admin123");

    protected HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");
}
