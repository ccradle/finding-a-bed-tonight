package org.fabt.auth.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.databind.ObjectMapper;

import org.fabt.auth.domain.User;
import org.fabt.auth.service.JwtService.JwtClaims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Security-attack regression tests for {@link JwtService#validateToken}.
 *
 * <p>Per the post-A4-design warroom: hand-rolled JWT implementations are
 * acceptable IF and ONLY IF we own explicit attack-class coverage. The
 * existing implementation defends against alg-none + algorithm confusion
 * (line 184-188 explicitly whitelists HS256), but pre-A4 had ZERO tests
 * proving these defenses work — a future refactor that loosened the alg
 * check would silently regress.
 *
 * <p>This file lands BEFORE the A4.2 JwtService refactor as a safety net.
 * Every attack vector below MUST be rejected. Tests assert that the token
 * does not validate; specific exception types are flexible (some current
 * paths throw NPE on missing required claims rather than IAE; the A4
 * refactor normalizes this, but for now we assert any rejection).
 *
 * <p>Coverage matrix (warroom + OWASP JWT cheat sheet):
 *
 * <ul>
 *   <li>{@code alg=none}: classic CVE-2015-9235 attack family</li>
 *   <li>{@code alg=RS256}: algorithm confusion (sign with HMAC, claim
 *       asymmetric to bypass naive validators)</li>
 *   <li>{@code alg=HS512}: any non-HS256 must be rejected even if also
 *       HMAC-family</li>
 *   <li>Signature byte tamper: detection by constant-time compare</li>
 *   <li>Expiry boundary: exp = past, must reject</li>
 *   <li>Missing exp claim: must reject (cannot validate freshness)</li>
 *   <li>Wrong parts count: malformed JWT (not 3 dot-separated parts)</li>
 *   <li>Empty parts</li>
 * </ul>
 */
@DisplayName("JwtService security attacks (pre-A4.2 safety net)")
class JwtServiceSecurityAttackTest {

    private static final String SECRET =
            "sufficiently-long-jwt-secret-for-tests-only-not-for-production-use-XXXXX";

    private final ObjectMapper mapper = new ObjectMapper();

    private JwtService service() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"lite", "test"});
        // Phase A4 new dependencies are null because every test in this file
        // exercises the LEGACY validate path (no kid header). The legacy path
        // does not touch keyDerivation/kidRegistry/revokedKidCache.
        return new JwtService(SECRET, 15, 7, mapper, env, null, null, null);
    }

    /** Issue a real, well-formed token via the service so tampering tests have a baseline. */
    private String legitimateToken() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setTenantId(UUID.randomUUID());
        user.setDisplayName("Attack Test User");
        user.setRoles(new String[]{"COC_ADMIN"});
        user.setDvAccess(false);
        user.setTokenVersion(1);
        return service().generateAccessToken(user);
    }

    // ------------------------------------------------------------------
    // Manual JWT composition helpers — building hostile tokens by hand
    // ------------------------------------------------------------------

    private String composeToken(String alg, Map<String, Object> payload) throws Exception {
        Map<String, String> header = Map.of("alg", alg, "typ", "JWT");
        String headerB64 = b64(mapper.writeValueAsString(header));
        String payloadB64 = b64(mapper.writeValueAsString(payload));
        String signingInput = headerB64 + "." + payloadB64;
        // Sign with HMAC-SHA256 using the test secret regardless of declared alg —
        // attacks that lie about alg should still be rejected by the alg whitelist.
        byte[] sig = hmacSha256(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + b64(sig);
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static byte[] hmacSha256(byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(data);
    }

    private Map<String, Object> validClaimsBase() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("sub", UUID.randomUUID().toString());
        p.put("tenantId", UUID.randomUUID().toString());
        p.put("type", "access");
        p.put("iat", java.time.Instant.now().getEpochSecond());
        p.put("exp", java.time.Instant.now().getEpochSecond() + 900);
        return p;
    }

    // ------------------------------------------------------------------
    // alg-confusion attacks
    // ------------------------------------------------------------------

    @Test
    @DisplayName("alg=none header → token rejected (CVE-2015-9235 family)")
    void algNoneRejected() throws Exception {
        String token = composeToken("none", validClaimsBase());
        assertThrows(IllegalArgumentException.class, () -> service().validateToken(token),
                "alg=none must be rejected — accepting it would let any attacker forge tokens");
    }

    @Test
    @DisplayName("alg=RS256 header (signed with HMAC) → token rejected (algorithm confusion)")
    void algRs256Rejected() throws Exception {
        String token = composeToken("RS256", validClaimsBase());
        assertThrows(IllegalArgumentException.class, () -> service().validateToken(token),
                "alg=RS256 must be rejected — attacker could bypass naive validators "
                + "that use the public key as HMAC secret");
    }

    @Test
    @DisplayName("alg=HS512 header → token rejected (only HS256 is on the allowlist)")
    void algHs512Rejected() throws Exception {
        String token = composeToken("HS512", validClaimsBase());
        assertThrows(IllegalArgumentException.class, () -> service().validateToken(token),
                "any non-HS256 alg, even HMAC-family, must be rejected per the explicit allowlist");
    }

    @Test
    @DisplayName("alg=ES256 header (asymmetric) → token rejected")
    void algEs256Rejected() throws Exception {
        String token = composeToken("ES256", validClaimsBase());
        assertThrows(IllegalArgumentException.class, () -> service().validateToken(token));
    }

    @Test
    @DisplayName("alg with surprising case (\"hs256\") → token rejected (case-sensitive allowlist)")
    void algLowercaseRejected() throws Exception {
        String token = composeToken("hs256", validClaimsBase());
        assertThrows(IllegalArgumentException.class, () -> service().validateToken(token),
                "JWT spec mandates exact match on alg value; \"hs256\" != \"HS256\"");
    }

    // ------------------------------------------------------------------
    // signature attacks
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Signature byte-tamper (flip one byte) → token rejected")
    void signatureTamperRejected() {
        String legit = legitimateToken();
        String[] parts = legit.split("\\.");

        // Flip one bit in the signature's first byte
        byte[] sig = Base64.getUrlDecoder().decode(parts[2]);
        sig[0] = (byte) (sig[0] ^ 0x01);
        String tamperedSig = Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        String tamperedToken = parts[0] + "." + parts[1] + "." + tamperedSig;

        assertThrows(IllegalArgumentException.class, () -> service().validateToken(tamperedToken),
                "single-bit signature corruption must be detected by constant-time compare");
    }

    @Test
    @DisplayName("Empty signature → token rejected")
    void emptySignatureRejected() {
        String legit = legitimateToken();
        String[] parts = legit.split("\\.");
        String token = parts[0] + "." + parts[1] + ".";
        assertThrows(Exception.class, () -> service().validateToken(token),
                "empty signature must not validate");
    }

    @Test
    @DisplayName("Payload tampered (claim re-encoded with extra role) but keeping original signature → rejected")
    void payloadTamperRejected() throws Exception {
        // Generate a legit token
        String legit = legitimateToken();
        String[] parts = legit.split("\\.");

        // Re-encode payload with elevated role; keep the original signature
        byte[] originalPayload = Base64.getUrlDecoder().decode(parts[1]);
        @SuppressWarnings("unchecked")
        Map<String, Object> claims = mapper.readValue(originalPayload, Map.class);
        claims.put("roles", new String[]{"PLATFORM_ADMIN"}); // privilege escalation attempt
        String tamperedPayloadB64 = b64(mapper.writeValueAsString(claims));
        String tampered = parts[0] + "." + tamperedPayloadB64 + "." + parts[2];

        assertThrows(IllegalArgumentException.class, () -> service().validateToken(tampered),
                "payload tampering with old signature must fail signature verify");
    }

    // ------------------------------------------------------------------
    // expiry + claim attacks
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Expired token (exp = 1ms ago) → rejected")
    void expiredTokenRejected() throws Exception {
        Map<String, Object> claims = validClaimsBase();
        claims.put("exp", java.time.Instant.now().getEpochSecond() - 1);
        String token = composeToken("HS256", claims);
        assertThrows(IllegalArgumentException.class, () -> service().validateToken(token),
                "exp in the past must reject");
    }

    @Test
    @DisplayName("Missing exp claim → token rejected (cannot validate freshness)")
    void missingExpRejected() throws Exception {
        Map<String, Object> claims = validClaimsBase();
        claims.remove("exp");
        String token = composeToken("HS256", claims);
        assertThrows(Exception.class, () -> service().validateToken(token),
                "JWT without exp claim must not validate (currently NPE; A4.2 normalizes to IAE)");
    }

    @Test
    @DisplayName("Far-future expiry → token validates (sanity baseline that the framework works)")
    void farFutureExpiryValidates() throws Exception {
        Map<String, Object> claims = validClaimsBase();
        claims.put("exp", java.time.Instant.now().getEpochSecond() + 86400);
        String token = composeToken("HS256", claims);
        JwtClaims parsed = service().validateToken(token);
        assertNotNull(parsed, "well-formed token in the future must validate (baseline)");
    }

    // ------------------------------------------------------------------
    // structural attacks
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Wrong parts count (only header.payload, no signature) → rejected")
    void wrongPartsCountRejected() {
        String token = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}") + "."
                + b64("{\"sub\":\"x\"}");
        assertThrows(IllegalArgumentException.class, () -> service().validateToken(token));
    }

    @Test
    @DisplayName("Single dot (truly malformed) → rejected")
    void singleDotRejected() {
        assertThrows(IllegalArgumentException.class, () -> service().validateToken("."));
    }

    @Test
    @DisplayName("Empty string → rejected")
    void emptyStringRejected() {
        assertThrows(IllegalArgumentException.class, () -> service().validateToken(""));
    }

    @Test
    @DisplayName("Garbage string → rejected")
    void garbageRejected() {
        assertThrows(Exception.class, () -> service().validateToken("not-a-jwt-at-all"));
    }
}
