package org.fabt.auth;

import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.api.TokenResponse;
import org.fabt.auth.domain.User;
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

class AuthIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
    }

    @Test
    void test_login_success() {
        String body = """
                {
                    "email": "%s",
                    "password": "%s",
                    "tenantSlug": "%s"
                }
                """.formatted(
                TestAuthHelper.ADMIN_EMAIL,
                TestAuthHelper.TEST_PASSWORD,
                authHelper.getTestTenantSlug()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<TokenResponse> response = restTemplate.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                TokenResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accessToken()).isNotBlank();
        assertThat(response.getBody().refreshToken()).isNotBlank();
        // Access and refresh tokens should be different
        assertThat(response.getBody().accessToken()).isNotEqualTo(response.getBody().refreshToken());
    }

    @Test
    void test_login_wrongPassword() {
        String body = """
                {
                    "email": "%s",
                    "password": "WrongPassword999!",
                    "tenantSlug": "%s"
                }
                """.formatted(
                TestAuthHelper.ADMIN_EMAIL,
                authHelper.getTestTenantSlug()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("Invalid credentials");
    }

    @Test
    void test_login_unknownEmail() {
        String body = """
                {
                    "email": "nobody@nowhere.org",
                    "password": "%s",
                    "tenantSlug": "%s"
                }
                """.formatted(
                TestAuthHelper.TEST_PASSWORD,
                authHelper.getTestTenantSlug()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("Invalid credentials");
    }

    @Test
    void test_login_unknownTenant() {
        String body = """
                {
                    "email": "%s",
                    "password": "%s",
                    "tenantSlug": "nonexistent-tenant-slug"
                }
                """.formatted(
                TestAuthHelper.ADMIN_EMAIL,
                TestAuthHelper.TEST_PASSWORD
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("Invalid credentials");
    }

    @Test
    void test_refresh_success() {
        // First, login to get tokens
        String loginBody = """
                {
                    "email": "%s",
                    "password": "%s",
                    "tenantSlug": "%s"
                }
                """.formatted(
                TestAuthHelper.ADMIN_EMAIL,
                TestAuthHelper.TEST_PASSWORD,
                authHelper.getTestTenantSlug()
        );

        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<TokenResponse> loginResponse = restTemplate.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(loginBody, loginHeaders),
                TokenResponse.class
        );
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String refreshToken = loginResponse.getBody().refreshToken();

        // Use the refresh token to get a new access token
        String refreshBody = """
                {"refreshToken": "%s"}
                """.formatted(refreshToken);

        ResponseEntity<TokenResponse> response = restTemplate.exchange(
                "/api/v1/auth/refresh",
                HttpMethod.POST,
                new HttpEntity<>(refreshBody, loginHeaders),
                TokenResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accessToken()).isNotBlank();
        // Refresh token is echoed back
        assertThat(response.getBody().refreshToken()).isEqualTo(refreshToken);
    }

    @Test
    void test_refresh_withAccessToken() {
        // First, login to get tokens
        String loginBody = """
                {
                    "email": "%s",
                    "password": "%s",
                    "tenantSlug": "%s"
                }
                """.formatted(
                TestAuthHelper.ADMIN_EMAIL,
                TestAuthHelper.TEST_PASSWORD,
                authHelper.getTestTenantSlug()
        );

        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<TokenResponse> loginResponse = restTemplate.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(loginBody, loginHeaders),
                TokenResponse.class
        );
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String accessToken = loginResponse.getBody().accessToken();

        // Try to use the ACCESS token as a refresh token - should fail
        String refreshBody = """
                {"refreshToken": "%s"}
                """.formatted(accessToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/refresh",
                HttpMethod.POST,
                new HttpEntity<>(refreshBody, loginHeaders),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("Invalid refresh token");
    }

    @Test
    void test_refresh_withInvalidToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String refreshBody = """
                {"refreshToken": "not.a.valid.token"}
                """;

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/refresh",
                HttpMethod.POST,
                new HttpEntity<>(refreshBody, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("Invalid refresh token");
    }
}
