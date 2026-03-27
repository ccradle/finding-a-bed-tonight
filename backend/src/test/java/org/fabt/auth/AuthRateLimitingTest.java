package org.fabt.auth;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

// Re-enable bucket4j for this test — lite profile disables it for Karate e2e.
@TestPropertySource(properties = "bucket4j.enabled=true")
class AuthRateLimitingTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
    }

    @Test
    void loginEndpoint_returns429_afterExceedingRateLimit() {
        String loginBody = """
                {"email": "wrong@test.fabt.org", "password": "wrong", "tenantSlug": "%s"}
                """.formatted(authHelper.getTestTenantSlug());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(loginBody, headers);

        // Make 10 requests — all should return 401 (wrong credentials, but not rate limited)
        for (int i = 0; i < 10; i++) {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "/api/v1/auth/login", request, String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        // 11th request should be rate limited
        ResponseEntity<String> rateLimited = restTemplate.postForEntity(
                "/api/v1/auth/login", request, String.class);
        assertThat(rateLimited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rateLimited.getBody()).contains("rate_limited");
    }

    @Test
    void nonAuthEndpoint_notRateLimited() {
        // Even after rate limit is hit on login, shelters endpoint should work
        HttpHeaders authedHeaders = authHelper.headersForUser(authHelper.setupAdminUser());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/shelters", org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(authedHeaders), String.class);
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
