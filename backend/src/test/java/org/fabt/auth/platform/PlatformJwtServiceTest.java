package org.fabt.auth.platform;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PlatformJwtService}. Covers:
 *
 * <ul>
 *   <li>Round-trip sign + validate of access / mfa-setup / mfa-verify tokens</li>
 *   <li>{@code alg=none} rejection (CVE-2015-9235 family)</li>
 *   <li>Algorithm-mismatch rejection</li>
 *   <li>kid-mismatch rejection (presented kid != active platform_key_material kid)</li>
 *   <li>Expired-token rejection</li>
 *   <li>{@code iss != "fabt-platform"} rejection</li>
 *   <li>Scope claim survives the round-trip</li>
 * </ul>
 *
 * <p>Pure unit — uses a Mockito {@link PlatformKeyRotationService} so no
 * Spring context or DB is needed. {@code PlatformAuthIntegrationTest} (the
 * end-to-end harness) covers the wired-up scenarios.
 */
@DisplayName("PlatformJwtService")
class PlatformJwtServiceTest {

    private PlatformKeyRotationService keyService;
    private PlatformJwtService jwtService;
    private ObjectMapper objectMapper;
    private PlatformKeyRotationService.ActiveKey activeKey;

    @BeforeEach
    void setUp() {
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        activeKey = new PlatformKeyRotationService.ActiveKey(
                UUID.randomUUID(), 1, "kid-" + UUID.randomUUID(),
                new SecretKeySpec(keyBytes, "HmacSHA256"));
        keyService = mock(PlatformKeyRotationService.class);
        when(keyService.findActiveKey()).thenReturn(activeKey);
        objectMapper = JsonMapper.builder().build();
        jwtService = new PlatformJwtService(keyService, objectMapper);
    }

    private PlatformUser stubUser() {
        PlatformUser u = new PlatformUser();
        u.setId(UUID.randomUUID());
        u.setEmail("ops@example.com");
        u.setMfaEnabled(true);
        return u;
    }

    @Test
    @DisplayName("access token round-trips: iss=fabt-platform, roles=[PLATFORM_OPERATOR], mfaVerified=true, ttl=15min")
    void accessTokenRoundTrip() {
        PlatformUser user = stubUser();
        String token = jwtService.generateAccessToken(user);

        PlatformJwtService.PlatformJwtClaims claims = jwtService.validateToken(token);

        assertThat(claims.sub()).isEqualTo(user.getId());
        assertThat(claims.roles()).containsExactly(PlatformJwtService.ROLE_PLATFORM_OPERATOR);
        assertThat(claims.mfaVerified()).isTrue();
        assertThat(claims.scope()).isNull();
        long ttl = claims.expiresAt().getEpochSecond() - claims.issuedAt().getEpochSecond();
        assertThat(ttl).as("access token TTL is exactly 15 min (900s)").isEqualTo(900L);
    }

    @Test
    @DisplayName("mfa-setup token carries scope=mfa-setup, no roles, 10-min TTL")
    void mfaSetupTokenShape() {
        PlatformUser user = stubUser();
        String token = jwtService.generateMfaSetupToken(user);

        PlatformJwtService.PlatformJwtClaims claims = jwtService.validateToken(token);

        assertThat(claims.scope()).isEqualTo(PlatformJwtService.SCOPE_MFA_SETUP);
        assertThat(claims.roles()).isNullOrEmpty();
        assertThat(claims.mfaVerified())
                .as("scoped tokens MUST NOT carry mfaVerified=true")
                .isFalse();
        long ttl = claims.expiresAt().getEpochSecond() - claims.issuedAt().getEpochSecond();
        assertThat(ttl).isEqualTo(600L);
    }

    @Test
    @DisplayName("mfa-verify token carries scope=mfa-verify, no roles, 5-min TTL")
    void mfaVerifyTokenShape() {
        PlatformUser user = stubUser();
        String token = jwtService.generateMfaVerifyToken(user);

        PlatformJwtService.PlatformJwtClaims claims = jwtService.validateToken(token);

        assertThat(claims.scope()).isEqualTo(PlatformJwtService.SCOPE_MFA_VERIFY);
        assertThat(claims.roles()).isNullOrEmpty();
        long ttl = claims.expiresAt().getEpochSecond() - claims.issuedAt().getEpochSecond();
        assertThat(ttl).isEqualTo(300L);
    }

    @Test
    @DisplayName("alg=none is rejected (CVE-2015-9235)")
    void rejectsAlgNone() throws Exception {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "none");
        header.put("typ", "JWT");
        header.put("kid", activeKey.kid());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", PlatformJwtService.ISSUER);
        payload.put("sub", UUID.randomUUID().toString());
        payload.put("iat", Instant.now().getEpochSecond());
        payload.put("exp", Instant.now().plusSeconds(300).getEpochSecond());

        String headerEnc = b64Url(objectMapper.writeValueAsBytes(header));
        String payloadEnc = b64Url(objectMapper.writeValueAsBytes(payload));
        // alg=none JWT in the wild often has empty signature, but for split
        // semantics we include a placeholder; the alg-whitelist check fires
        // before any signature processing, so the placeholder content is
        // irrelevant.
        String forged = headerEnc + "." + payloadEnc + ".sig";

        assertThatThrownBy(() -> jwtService.validateToken(forged))
                .isInstanceOf(PlatformJwtException.class)
                .hasMessageContaining("Unsupported alg");
    }

    @Test
    @DisplayName("RS256 alg confusion attack rejected")
    void rejectsAlgConfusion() throws Exception {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", activeKey.kid());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", PlatformJwtService.ISSUER);
        payload.put("sub", UUID.randomUUID().toString());
        payload.put("iat", Instant.now().getEpochSecond());
        payload.put("exp", Instant.now().plusSeconds(300).getEpochSecond());

        String headerEnc = b64Url(objectMapper.writeValueAsBytes(header));
        String payloadEnc = b64Url(objectMapper.writeValueAsBytes(payload));
        String forged = headerEnc + "." + payloadEnc + ".sig";

        assertThatThrownBy(() -> jwtService.validateToken(forged))
                .isInstanceOf(PlatformJwtException.class)
                .hasMessageContaining("Unsupported alg");
    }

    @Test
    @DisplayName("missing kid header is rejected")
    void rejectsMissingKid() throws Exception {
        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> payload = Map.of(
                "iss", PlatformJwtService.ISSUER,
                "sub", UUID.randomUUID().toString(),
                "iat", Instant.now().getEpochSecond(),
                "exp", Instant.now().plusSeconds(300).getEpochSecond());
        String forged = b64Url(objectMapper.writeValueAsBytes(header))
                + "." + b64Url(objectMapper.writeValueAsBytes(payload))
                + ".sig";

        assertThatThrownBy(() -> jwtService.validateToken(forged))
                .isInstanceOf(PlatformJwtException.class)
                .hasMessageContaining("missing kid");
    }

    @Test
    @DisplayName("kid that does not match active platform_key_material is rejected")
    void rejectsKidMismatch() {
        // Generate a token, then re-stub keyService to return a different
        // active row. The kid in the header no longer matches.
        String token = jwtService.generateAccessToken(stubUser());

        byte[] otherBytes = new byte[32];
        new SecureRandom().nextBytes(otherBytes);
        when(keyService.findActiveKey()).thenReturn(
                new PlatformKeyRotationService.ActiveKey(
                        UUID.randomUUID(), 2, "kid-different",
                        new SecretKeySpec(otherBytes, "HmacSHA256")));

        assertThatThrownBy(() -> jwtService.validateToken(token))
                .isInstanceOf(PlatformJwtException.class)
                .hasMessageContaining("kid does not match");
    }

    @Test
    @DisplayName("invalid signature rejected — different key over same payload")
    void rejectsInvalidSignature() {
        // Sign a token with our key, then re-stub keyService to return a
        // DIFFERENT key under the SAME kid (a kid collision attempt). The
        // header passes the kid check but the signature won't verify.
        String token = jwtService.generateAccessToken(stubUser());

        byte[] otherBytes = new byte[32];
        new SecureRandom().nextBytes(otherBytes);
        when(keyService.findActiveKey()).thenReturn(
                new PlatformKeyRotationService.ActiveKey(
                        activeKey.id(), activeKey.generation(), activeKey.kid(),
                        new SecretKeySpec(otherBytes, "HmacSHA256")));

        assertThatThrownBy(() -> jwtService.validateToken(token))
                .isInstanceOf(PlatformJwtException.class)
                .hasMessageContaining("Invalid platform JWT signature");
    }

    @Test
    @DisplayName("expired token rejected")
    void rejectsExpiredToken() throws Exception {
        Map<String, Object> header = Map.of(
                "alg", "HS256", "typ", "JWT", "kid", activeKey.kid());
        long now = Instant.now().getEpochSecond();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", PlatformJwtService.ISSUER);
        payload.put("sub", UUID.randomUUID().toString());
        payload.put("iat", now - 7200);
        payload.put("exp", now - 3600); // expired 1h ago

        String headerEnc = b64Url(objectMapper.writeValueAsBytes(header));
        String payloadEnc = b64Url(objectMapper.writeValueAsBytes(payload));
        // Sign properly so we get past sig check and into expiry check
        byte[] sig = hmac(activeKey.key(), (headerEnc + "." + payloadEnc).getBytes());
        String token = headerEnc + "." + payloadEnc + "." + b64Url(sig);

        assertThatThrownBy(() -> jwtService.validateToken(token))
                .isInstanceOf(PlatformJwtException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("iss != fabt-platform rejected")
    void rejectsWrongIssuer() throws Exception {
        Map<String, Object> header = Map.of(
                "alg", "HS256", "typ", "JWT", "kid", activeKey.kid());
        long now = Instant.now().getEpochSecond();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", "fabt-tenant"); // wrong
        payload.put("sub", UUID.randomUUID().toString());
        payload.put("iat", now);
        payload.put("exp", now + 300);

        String headerEnc = b64Url(objectMapper.writeValueAsBytes(header));
        String payloadEnc = b64Url(objectMapper.writeValueAsBytes(payload));
        byte[] sig = hmac(activeKey.key(), (headerEnc + "." + payloadEnc).getBytes());
        String token = headerEnc + "." + payloadEnc + "." + b64Url(sig);

        assertThatThrownBy(() -> jwtService.validateToken(token))
                .isInstanceOf(PlatformJwtException.class)
                .hasMessageContaining("iss must be fabt-platform");
    }

    @Test
    @DisplayName("malformed JWT (wrong number of parts) rejected")
    void rejectsMalformedFormat() {
        assertThatThrownBy(() -> jwtService.validateToken("a.b"))
                .isInstanceOf(PlatformJwtException.class)
                .hasMessageContaining("Invalid platform JWT format");
        assertThatThrownBy(() -> jwtService.validateToken("a.b.c.d"))
                .isInstanceOf(PlatformJwtException.class)
                .hasMessageContaining("Invalid platform JWT format");
    }

    private static String b64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static byte[] hmac(javax.crypto.SecretKey key, byte[] data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
