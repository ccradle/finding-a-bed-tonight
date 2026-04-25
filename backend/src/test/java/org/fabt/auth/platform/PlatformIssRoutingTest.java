package org.fabt.auth.platform;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.fabt.Application;
import org.fabt.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Iss-routed JwtDecoder dispatch regression tests (G-4.2 task 3.13).
 *
 * <p>Asserts that a forged {@code iss=fabt-platform} JWT — signed with a
 * random key under a random kid — presented to ANY endpoint is rejected.
 * The {@link org.fabt.shared.security.JwtAuthenticationFilter} peeks at
 * the iss claim BEFORE signature verification to route the token to the
 * correct validator; the platform validator then fails on kid mismatch
 * (the random kid does not match the active {@code platform_key_material}
 * row).
 *
 * <p>This pins the contract that an attacker cannot bypass the platform-
 * key validator by setting iss=fabt-platform — the fast-routing decision
 * is made on the iss field, but the actual security comes from the
 * issuer-specific signature check.
 */
@DisplayName("Iss-routed JwtDecoder dispatch — forged-token rejection")
@SpringBootTest(classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformIssRoutingTest extends BaseIntegrationTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("forged iss=fabt-platform token presented to a protected tenant endpoint returns 401/403")
    void forgedPlatformTokenRejectedOnTenantEndpoint() throws Exception {
        String forged = forgePlatformIssJwt(
                Map.of(
                        "iss", PlatformJwtService.ISSUER,
                        "sub", UUID.randomUUID().toString(),
                        "roles", java.util.List.of(PlatformJwtService.ROLE_PLATFORM_OPERATOR),
                        "mfaVerified", true,
                        "iat", Instant.now().getEpochSecond(),
                        "exp", Instant.now().plusSeconds(900).getEpochSecond()),
                "kid-forged-" + UUID.randomUUID());

        // Pick any authenticated tenant endpoint. /api/v1/shelters is gated.
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(forged);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/shelters",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        // Forged token's kid does not match active platform_key_material row.
        // PlatformJwtService throws PlatformJwtException; the filter clears
        // SecurityContext; the endpoint sees no authentication.
        assertThat(response.getStatusCode())
                .as("forged platform token must be rejected — 401 (no auth) or 403 (auth-but-not-allowed)")
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("forged iss=fabt-platform token presented to /auth/platform/mfa-setup is rejected (in-controller scope check)")
    void forgedScopedTokenRejectedAtPlatformEndpoint() throws Exception {
        String forged = forgePlatformIssJwt(
                Map.of(
                        "iss", PlatformJwtService.ISSUER,
                        "sub", UUID.randomUUID().toString(),
                        "scope", PlatformJwtService.SCOPE_MFA_SETUP,
                        "iat", Instant.now().getEpochSecond(),
                        "exp", Instant.now().plusSeconds(600).getEpochSecond()),
                "kid-forged-" + UUID.randomUUID());

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(forged);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/auth/platform/mfa-setup",
                HttpMethod.POST,
                new HttpEntity<>(null, headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("error")).isEqualTo("invalid_platform_token");
    }

    @Test
    @DisplayName("missing-iss token bypasses platform routing and is rejected by tenant validator")
    void missingIssGoesToTenantValidator() throws Exception {
        // No iss claim → peekIssuer returns null → falls through to tenant
        // JwtService.validateToken which fails (random sig).
        String forged = forgeJwtWithRandomKey(
                Map.of("alg", "HS256", "typ", "JWT"),
                Map.of(
                        "sub", UUID.randomUUID().toString(),
                        "tenantId", UUID.randomUUID().toString(),
                        "iat", Instant.now().getEpochSecond(),
                        "exp", Instant.now().plusSeconds(900).getEpochSecond()));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(forged);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/shelters",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(response.getStatusCode())
                .as("forged tenant-style token (no iss, no valid sig) must be rejected")
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    private static String forgePlatformIssJwt(Map<String, Object> payload, String kid)
            throws Exception {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");
        header.put("kid", kid);
        return forgeJwtWithRandomKey(header, payload);
    }

    private static String forgeJwtWithRandomKey(Map<String, Object> header,
                                                 Map<String, Object> payload) throws Exception {
        String headerEnc = b64Url(JSON.writeValueAsBytes(header));
        String payloadEnc = b64Url(JSON.writeValueAsBytes(payload));
        String signingInput = headerEnc + "." + payloadEnc;
        byte[] randomKey = new byte[32];
        new SecureRandom().nextBytes(randomKey);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(randomKey, "HmacSHA256"));
        byte[] sig = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + b64Url(sig);
    }

    private static String b64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
