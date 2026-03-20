package org.fabt.observability;

import java.util.Map;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        authHelper.setupCocAdminUser();
    }

    // --- Task 8.7: Health endpoint tests ---

    @Test
    void test_livenessEndpoint_returnsOk() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/actuator/health/liveness",
                HttpMethod.GET,
                null,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("UP");
    }

    @Test
    void test_readinessEndpoint_returnsOk() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/actuator/health/readiness",
                HttpMethod.GET,
                null,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("UP");
    }

    @Test
    void test_healthEndpoint_includesDbComponent() {
        HttpHeaders headers = authHelper.adminHeaders();

        ResponseEntity<String> response = restTemplate.exchange(
                "/actuator/health",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("db");
    }

    // --- Task 8.9: Localized error responses ---

    @Test
    void test_errorResponse_defaultEnglish() {
        // Request a nonexistent tenant — should return English error
        HttpHeaders headers = authHelper.adminHeaders();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/tenants/" + UUID.randomUUID(),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("not_found");
    }

    @Test
    void test_errorResponse_spanishLocale() {
        HttpHeaders headers = authHelper.adminHeaders();
        headers.set("Accept-Language", "es");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/tenants/" + UUID.randomUUID(),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Should contain Spanish error message
        assertThat(response.getBody()).contains("no encontrado");
    }

    // --- Task 8.10: No PII in responses ---

    @Test
    void test_errorResponses_containStructuredErrorCode() {
        // Verify MCP-ready error response structure
        HttpHeaders headers = authHelper.adminHeaders();

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/tenants/" + UUID.randomUUID(),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKey("error");
        assertThat(body).containsKey("message");
        assertThat(body).containsKey("status");
        assertThat(body).containsKey("timestamp");
        assertThat(body.get("error")).isEqualTo("not_found");
        assertThat(body.get("status")).isEqualTo(404);
    }
}
