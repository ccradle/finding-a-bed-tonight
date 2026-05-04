package org.fabt.tenant.api;

import java.util.Map;

import org.fabt.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the empty-platform-config branch of {@link ContactInfoController}
 * (info-email-contact §4.6 first bullet — "Empty platform config →
 * empty platform.email response"). Lives in a separate class because
 * {@code @TestPropertySource(properties=...)} is class-scoped, and the
 * sibling {@link ContactInfoControllerTest} pins
 * {@code fabt.platform.contact-email=info@findabed.test}. Without an
 * empty-pinned variant the not-yet-deployed branch (operator hasn't set
 * {@code FABT_PLATFORM_CONTACT_EMAIL} yet) was untested — added per
 * info-email-contact §4 warroom round 1 M1-Riley.
 */
@DisplayName("GET /api/v1/public/contact-info — empty platform config")
@TestPropertySource(properties = "fabt.platform.contact-email=")
class ContactInfoControllerEmptyPlatformTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Empty platform config — platform.email is empty string, tenant=null")
    void emptyPlatformConfigReturnsEmptyEmail() {
        // M1-Riley-r2 (warroom round 2): set X-Real-IP to a unique private
        // address so this test gets its own bucket4j cache key, isolated from
        // any cross-test-class drain. The YAML filter cache-key expression
        // reads X-Real-IP first; using a different IP than the default
        // 127.0.0.1 used by ContactInfoControllerTest.rateLimitOnBurst gives
        // this test a fresh budget regardless of test class ordering.
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Real-IP", "127.0.0.42");
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/public/contact-info",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> platform = (Map<String, Object>) response.getBody().get("platform");
        assertThat(platform)
                .as("Platform block MUST be present even when contact email is unconfigured")
                .isNotNull();
        assertThat(platform)
                .as("Platform email is the empty string when env var is unset — "
                        + "frontend §6 fallback (GH-issues link) keys off this signal")
                .containsEntry("email", "");
        assertThat(response.getBody().get("tenant"))
                .as("Anonymous caller MUST NOT see a tenant block")
                .isNull();
    }
}
