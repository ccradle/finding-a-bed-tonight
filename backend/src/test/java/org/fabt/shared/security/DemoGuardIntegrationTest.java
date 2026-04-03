package org.fabt.shared.security;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for DemoGuardFilter with the "demo" profile active.
 *
 * TestRestTemplate sends from localhost, which the filter treats as admin
 * tunnel traffic (bypass). To simulate public traffic, we add X-Forwarded-For
 * to trigger the demo guard — matching real behavior where nginx/Cloudflare
 * always set this header on public requests.
 */
@ActiveProfiles({"lite", "test", "demo"})
class DemoGuardIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
    }

    /** Simulate public traffic by adding X-Forwarded-For (as nginx/Cloudflare would). */
    private HttpHeaders publicAdminHeaders() {
        HttpHeaders headers = authHelper.adminHeaders();
        headers.set("X-Forwarded-For", "203.0.113.50");
        return headers;
    }

    @Test
    void destructive_endpoint_returns_403_for_public_traffic() {
        HttpHeaders headers = publicAdminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = """
                {
                    "email": "test@test.fabt.org",
                    "password": "Test1234!",
                    "displayName": "Test User",
                    "roles": ["OUTREACH_WORKER"]
                }
                """;

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("demo_restricted");
    }

    @Test
    void safe_endpoint_returns_200_for_public_traffic() {
        HttpHeaders headers = publicAdminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = """
                {"populationType": "SINGLE_ADULT"}
                """;

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/queries/beds",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void get_endpoint_returns_200_for_public_traffic() {
        HttpHeaders headers = publicAdminHeaders();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void password_change_blocked_for_public_traffic() {
        HttpHeaders headers = publicAdminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = """
                {
                    "currentPassword": "%s",
                    "newPassword": "NewPassword123!"
                }
                """.formatted(TestAuthHelper.TEST_PASSWORD);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/password",
                HttpMethod.PUT,
                new HttpEntity<>(body, headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("demo_restricted");
        assertThat(response.getBody()).contains("Password changes are disabled");
    }

    @Test
    void localhost_traffic_bypasses_guard() {
        // No X-Forwarded-For = tunnel/localhost traffic → bypass
        HttpHeaders headers = authHelper.adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = """
                {
                    "email": "tunneltest@test.fabt.org",
                    "password": "Test1234!",
                    "displayName": "Tunnel Test User",
                    "roles": ["OUTREACH_WORKER"]
                }
                """;

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);

        // Should succeed because localhost + no X-Forwarded-For = admin bypass
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
