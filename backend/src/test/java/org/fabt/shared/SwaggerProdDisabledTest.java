package org.fabt.shared;

import org.fabt.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

// The "prod" profile activates JwtService's @PostConstruct check that rejects
// the dev default JWT secret. We provide an explicit non-default secret here
// so the context starts successfully. Without this, the test would need to
// trace through application-lite.yml to understand why startup succeeds —
// this makes the dependency explicit and self-documenting.
@ActiveProfiles({"lite", "test", "prod"})
@TestPropertySource(properties = "fabt.jwt.secret=test-secret-for-swagger-prod-disabled-test")
class SwaggerProdDisabledTest extends BaseIntegrationTest {

    @Test
    void swaggerUi_returns404_inProdProfile() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/docs", String.class);
        // With prod profile, springdoc is disabled — expect 404 or redirect to nowhere
        assertThat(response.getStatusCode().value())
                .isIn(HttpStatus.NOT_FOUND.value(), HttpStatus.MOVED_PERMANENTLY.value());
    }

    @Test
    void apiDocs_returns404_inProdProfile() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/api-docs", String.class);
        assertThat(response.getStatusCode().value())
                .isIn(HttpStatus.NOT_FOUND.value(), HttpStatus.MOVED_PERMANENTLY.value());
    }
}
