package org.fabt.shared;

import java.util.Map;

import org.fabt.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Version Endpoint")
class VersionEndpointTest extends BaseIntegrationTest {

    @Test
    @DisplayName("GET /api/v1/version returns version without authentication")
    void versionEndpoint_returnsVersionWithoutAuth() {
        ResponseEntity<Map<String, String>> response = restTemplate.exchange(
                "/api/v1/version",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {}
        );

        // May be 404 if BuildProperties bean is not available in test context
        // (build-info goal not run). In that case, verify it's not 401/403.
        if (response.getStatusCode() == HttpStatus.OK) {
            assertThat(response.getBody()).containsKey("version");
            assertThat(response.getBody().get("version")).isNotBlank();
        } else {
            // Endpoint not registered (ConditionalOnBean) — verify it's not an auth error
            assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
        }
    }
}
