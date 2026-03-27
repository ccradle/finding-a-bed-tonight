package org.fabt.shared;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.api.TokenResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorResponseLeakageTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    private String adminToken;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();

        // Login via API to get token
        String loginBody = """
                {"email": "%s", "password": "%s", "tenantSlug": "%s"}
                """.formatted(
                TestAuthHelper.ADMIN_EMAIL,
                TestAuthHelper.TEST_PASSWORD,
                authHelper.getTestTenantSlug());
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<TokenResponse> loginResponse = restTemplate.exchange(
                "/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(loginBody, loginHeaders), TokenResponse.class);
        adminToken = loginResponse.getBody().accessToken();
    }

    @Test
    void malformedJson_doesNotExposeStackTrace() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("not valid json {{{", headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/login", request, String.class);

        String body = response.getBody();
        assertThat(body).doesNotContain("at org.fabt");
        assertThat(body).doesNotContain("at java.");
        assertThat(body).doesNotContain("at org.springframework");
        assertThat(body).doesNotContain("stackTrace");
    }

    @Test
    void notFoundEndpoint_doesNotExposeImplementationDetails() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/nonexistent-endpoint", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        String body = response.getBody();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        if (body != null) {
            assertThat(body).doesNotContain("org.springframework");
            assertThat(body).doesNotContain("Spring Boot");
        }
    }

    @Test
    void unauthenticatedRequest_doesNotExposeServerInfo() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/shelters", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String body = response.getBody();
        if (body != null) {
            assertThat(body).doesNotContain("at org.fabt");
            assertThat(body).doesNotContain("Spring");
        }
    }
}
