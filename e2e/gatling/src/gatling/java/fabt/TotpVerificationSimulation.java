package fabt;

import io.gatling.javaapi.core.ScenarioBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * TotpVerificationSimulation — exercises POST /api/v1/auth/verify-totp under load.
 *
 * Simulates a shelter shift change: 100 workers logging in simultaneously,
 * each completing TOTP verification.
 *
 * Setup: creates a test user with TOTP enabled, then generates mfaTokens
 * and valid TOTP codes for each virtual user.
 *
 * SLO assertions: p50 < 50ms, p95 < 100ms (TOTP is a single HMAC, should be fast)
 *
 * NOTE: Each VU gets its own mfaToken (from a fresh login) but shares the same
 * TOTP secret. This is realistic — the same user might have multiple login
 * attempts during a shift change scenario.
 */
public class TotpVerificationSimulation extends FabtSimulation {

    // Setup: create a TOTP-enabled test user and get the secret
    private static final String TEST_EMAIL = "gatling-totp@dev.fabt.org";
    private static final String TEST_PASSWORD = "admin123";
    private static String TOTP_SECRET;

    static {
        try {
            // Create user if needed + enable TOTP
            String adminToken = acquireToken("cocadmin@dev.fabt.org", "admin123");
            HttpClient client = HttpClient.newHttpClient();

            // Create test user (ignore 409 if exists)
            client.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/users"))
                    .header("Authorization", "Bearer " + adminToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"email\":\"" + TEST_EMAIL + "\",\"displayName\":\"Gatling TOTP\",\"password\":\"" + TEST_PASSWORD + "\"," +
                            "\"roles\":[\"OUTREACH_WORKER\"],\"dvAccess\":false}"))
                    .build(), HttpResponse.BodyHandlers.ofString());

            // Login as test user
            String userToken = acquireToken(TEST_EMAIL, TEST_PASSWORD);

            // Start TOTP enrollment
            HttpResponse<String> enrollRes = client.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/auth/enroll-totp"))
                    .header("Authorization", "Bearer " + userToken)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(), HttpResponse.BodyHandlers.ofString());

            Pattern secretPattern = Pattern.compile("\"secret\"\\s*:\\s*\"([^\"]+)\"");
            Matcher m = secretPattern.matcher(enrollRes.body());
            if (!m.find()) throw new RuntimeException("No secret in enrollment response: " + enrollRes.body());
            TOTP_SECRET = m.group(1);

            // Confirm enrollment with a valid code
            String code = generateTotp(TOTP_SECRET);
            client.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/auth/confirm-totp-enrollment"))
                    .header("Authorization", "Bearer " + userToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"" + code + "\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());

            System.out.println("TOTP setup complete for " + TEST_EMAIL + " (secret length: " + TOTP_SECRET.length() + ")");
        } catch (Exception e) {
            System.err.println("TOTP setup failed — simulation will fail: " + e.getMessage());
            TOTP_SECRET = null;
        }
    }

    /**
     * Generate a TOTP code using HMAC-SHA1 (RFC 6238).
     * Equivalent to TotpTestHelper but in pure Java for Gatling context.
     */
    private static String generateTotp(String base32Secret) {
        try {
            // Base32 decode
            byte[] key = base32Decode(base32Secret);
            long timeStep = System.currentTimeMillis() / 1000 / 30;
            byte[] timeBytes = new byte[8];
            for (int i = 7; i >= 0; i--) {
                timeBytes[i] = (byte) (timeStep & 0xFF);
                timeStep >>= 8;
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(timeBytes);
            int offset = hash[hash.length - 1] & 0xF;
            int code = ((hash[offset] & 0x7F) << 24 | (hash[offset + 1] & 0xFF) << 16 |
                    (hash[offset + 2] & 0xFF) << 8 | (hash[offset + 3] & 0xFF)) % 1_000_000;
            return String.format("%06d", code);
        } catch (Exception e) {
            throw new RuntimeException("TOTP generation failed", e);
        }
    }

    private static byte[] base32Decode(String base32) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        base32 = base32.toUpperCase().replaceAll("[^A-Z2-7]", "");
        int outputLen = base32.length() * 5 / 8;
        byte[] result = new byte[outputLen];
        int buffer = 0, bitsLeft = 0, index = 0;
        for (char c : base32.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                result[index++] = (byte) (buffer >> (bitsLeft - 8));
                bitsLeft -= 8;
            }
        }
        return result;
    }

    // Each VU: login (get mfaToken) → verify TOTP
    ScenarioBuilder totpVerifyScenario = scenario("TOTP Verification")
            .exec(session -> {
                // Get a fresh mfaToken by logging in
                try {
                    HttpClient client = HttpClient.newHttpClient();
                    HttpResponse<String> loginRes = client.send(HttpRequest.newBuilder()
                            .uri(URI.create(BASE_URL + "/api/v1/auth/login"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"tenantSlug\":\"" + TENANT_SLUG + "\",\"email\":\"" + TEST_EMAIL +
                                    "\",\"password\":\"" + TEST_PASSWORD + "\"}"))
                            .build(), HttpResponse.BodyHandlers.ofString());

                    Pattern mfaPattern = Pattern.compile("\"mfaToken\"\\s*:\\s*\"([^\"]+)\"");
                    Matcher m = mfaPattern.matcher(loginRes.body());
                    if (m.find()) {
                        return session.set("mfaToken", m.group(1))
                                     .set("totpCode", generateTotp(TOTP_SECRET));
                    }
                } catch (Exception e) {
                    System.err.println("Login failed: " + e.getMessage());
                }
                return session.markAsFailed();
            })
            .exec(
                    http("POST /api/v1/auth/verify-totp")
                            .post("/api/v1/auth/verify-totp")
                            .body(StringBody("{\"mfaToken\":\"#{mfaToken}\",\"code\":\"#{totpCode}\"}"))
                            .check(status().is(200))
            );

    {
        setUp(
                totpVerifyScenario.injectOpen(
                        rampUsers(100).during(Duration.ofSeconds(30))
                )
        ).protocols(httpProtocol)
         .assertions(
                 global().responseTime().percentile(50.0).lt(50),
                 global().responseTime().percentile(95.0).lt(100),
                 global().successfulRequests().percent().gt(95.0)
         );
    }
}
