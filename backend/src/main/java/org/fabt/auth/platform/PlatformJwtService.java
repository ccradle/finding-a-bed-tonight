package org.fabt.auth.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.SecretKey;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

/**
 * Issues + validates {@code iss = "fabt-platform"} JWTs for
 * {@link PlatformUser} sessions (Phase G-4 / issue #141).
 *
 * <h2>Why a separate service from {@link org.fabt.auth.service.JwtService}</h2>
 *
 * <p>Per design Decision 3, platform JWTs and tenant JWTs are kept on
 * separate validation paths so that the Phase A4 D25 cross-tenant cross-check
 * — kid resolves to a tenant; body claim {@code tenantId} must match —
 * remains a 1-branch invariant rather than growing 3-branch logic to
 * accommodate "platform tokens have no tenant." See {@code JwtService}
 * line 409-424 for the tenant cross-check; that code is bypassed entirely
 * for platform tokens because it is never reached.
 *
 * <h2>Token shapes</h2>
 *
 * <ul>
 *   <li><b>Access</b> (15 min) — issued after MFA verification. Full
 *       {@code PLATFORM_OPERATOR} authority. Carries
 *       {@code mfaVerified=true} so downstream code can require MFA-fresh
 *       sessions on sensitive actions.</li>
 *   <li><b>MFA-setup</b> (10 min) — issued on first password auth when
 *       {@code mfa_enabled = false}. Carries {@code scope = "mfa-setup"},
 *       NO roles. Per Marcus's hard constraint: the
 *       {@code PlatformAuthController} MUST server-validate the scope claim
 *       before honoring an MFA-setup request — URL-path-only enforcement is
 *       not enough because a confused-deputy bug could replay the token at
 *       another endpoint.</li>
 *   <li><b>MFA-verify</b> (5 min) — issued on subsequent password auth when
 *       {@code mfa_enabled = true}, BEFORE the operator submits the TOTP
 *       code. Carries {@code scope = "mfa-verify"}, NO roles. Exchanged at
 *       {@code POST /auth/platform/login/mfa-verify} for a real access
 *       token.</li>
 * </ul>
 *
 * <h2>Signature</h2>
 *
 * <p>HMAC-SHA256 over {@code base64url(header) + "." + base64url(payload)}.
 * Key sourced from {@link PlatformKeyRotationService#findActiveKey()} —
 * one active row in {@code platform_key_material}, kid in JWT header
 * matches the row's kid.
 *
 * <h2>Validation order</h2>
 *
 * <ol>
 *   <li>Header parse + {@code alg == "HS256"} whitelist (defends
 *       CVE-2015-9235 family — alg=none + algorithm confusion).</li>
 *   <li>{@code kid} present + parses as UUID-or-string; resolves against
 *       the active row in {@code platform_key_material}. Mismatch =
 *       {@link PlatformJwtException}.</li>
 *   <li>Constant-time signature compare via {@link MessageDigest#isEqual}.</li>
 *   <li>Payload parse + {@code iss == "fabt-platform"} + {@code exp} in
 *       future.</li>
 * </ol>
 *
 * <p>No claims cache here. Platform endpoints are low-traffic (operator
 * actions, not tenant request volume); the per-request HMAC is cheap and
 * caching adds revocation complexity for negligible benefit.
 */
@Service
public class PlatformJwtService {

    public static final String ISSUER = "fabt-platform";
    public static final String ROLE_PLATFORM_OPERATOR = "PLATFORM_OPERATOR";

    public static final String SCOPE_MFA_SETUP = "mfa-setup";
    public static final String SCOPE_MFA_VERIFY = "mfa-verify";

    private static final String ALGORITHM = "HmacSHA256";
    private static final long ACCESS_TOKEN_TTL_SECONDS = 15L * 60;
    private static final long MFA_SETUP_TOKEN_TTL_SECONDS = 10L * 60;
    private static final long MFA_VERIFY_TOKEN_TTL_SECONDS = 5L * 60;

    private final PlatformKeyRotationService keyService;
    private final ObjectMapper objectMapper;

    public PlatformJwtService(PlatformKeyRotationService keyService, ObjectMapper objectMapper) {
        this.keyService = keyService;
        this.objectMapper = objectMapper;
    }

    public long getAccessTokenExpirySeconds() {
        return ACCESS_TOKEN_TTL_SECONDS;
    }

    /**
     * Generates a full-authority access token for a platform_user that has
     * completed MFA verification. The {@code mfaVerified=true} claim is the
     * authoritative signal that the operator presented a valid TOTP/backup
     * code on this login flow; downstream code may inspect it for sensitive
     * actions that should refuse pre-MFA tokens.
     */
    public String generateAccessToken(PlatformUser user) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ACCESS_TOKEN_TTL_SECONDS);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", ISSUER);
        payload.put("sub", user.getId().toString());
        payload.put("roles", List.of(ROLE_PLATFORM_OPERATOR));
        payload.put("mfaVerified", true);
        payload.put("ver", 0);
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", exp.getEpochSecond());
        return signPlatformToken(payload);
    }

    /**
     * Generates an MFA-setup token issued on first-password-success when
     * the operator has not yet enrolled MFA. {@code scope = "mfa-setup"};
     * the {@code PlatformAuthController} server-validates the scope claim
     * before accepting MFA setup requests.
     */
    public String generateMfaSetupToken(PlatformUser user) {
        return generateScopedToken(user, SCOPE_MFA_SETUP, MFA_SETUP_TOKEN_TTL_SECONDS);
    }

    /**
     * Generates an MFA-verify token issued on subsequent-password-success
     * before TOTP entry. {@code scope = "mfa-verify"}; the
     * {@code PlatformAuthController} accepts this only at the
     * {@code /auth/platform/login/mfa-verify} endpoint and only if the
     * scope claim matches.
     */
    public String generateMfaVerifyToken(PlatformUser user) {
        return generateScopedToken(user, SCOPE_MFA_VERIFY, MFA_VERIFY_TOKEN_TTL_SECONDS);
    }

    private String generateScopedToken(PlatformUser user, String scope, long ttlSeconds) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttlSeconds);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", ISSUER);
        payload.put("sub", user.getId().toString());
        payload.put("scope", scope);
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", exp.getEpochSecond());
        return signPlatformToken(payload);
    }

    /**
     * Validates a platform JWT end-to-end and returns the parsed claims.
     * Throws {@link PlatformJwtException} on any failure — caller maps to
     * HTTP 401.
     */
    public PlatformJwtClaims validateToken(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new PlatformJwtException("Invalid platform JWT format");
        }

        Map<String, Object> header;
        try {
            byte[] headerBytes = Base64.getUrlDecoder().decode(parts[0]);
            header = objectMapper.readValue(headerBytes, new TypeReference<>() {});
        } catch (Exception e) {
            throw new PlatformJwtException("Invalid platform JWT header", e);
        }
        if (!"HS256".equals(header.get("alg"))) {
            throw new PlatformJwtException(
                    "Unsupported alg for platform JWT: " + header.get("alg"));
        }

        Object kidObj = header.get("kid");
        if (kidObj == null) {
            throw new PlatformJwtException("Platform JWT missing kid header");
        }
        String presentedKid = kidObj.toString();

        PlatformKeyRotationService.ActiveKey active = keyService.findActiveKey();
        if (!active.kid().equals(presentedKid)) {
            throw new PlatformJwtException(
                    "Platform JWT kid does not match active platform_key_material row");
        }

        String signingInput = parts[0] + "." + parts[1];
        byte[] expected = hmac(active.key(), signingInput.getBytes(StandardCharsets.UTF_8));
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new PlatformJwtException("Invalid platform JWT signature encoding", e);
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new PlatformJwtException("Invalid platform JWT signature");
        }

        Map<String, Object> payload;
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            payload = objectMapper.readValue(payloadBytes, new TypeReference<>() {});
        } catch (Exception e) {
            throw new PlatformJwtException("Invalid platform JWT payload", e);
        }

        if (!ISSUER.equals(payload.get("iss"))) {
            throw new PlatformJwtException(
                    "Platform JWT iss must be " + ISSUER + ", got: " + payload.get("iss"));
        }

        Object expObj = payload.get("exp");
        if (!(expObj instanceof Number)) {
            throw new PlatformJwtException("Platform JWT missing exp");
        }
        Instant expInstant = Instant.ofEpochSecond(((Number) expObj).longValue());
        if (Instant.now().isAfter(expInstant)) {
            throw new PlatformJwtException("Platform JWT has expired");
        }

        UUID sub;
        try {
            sub = UUID.fromString((String) payload.get("sub"));
        } catch (Exception e) {
            throw new PlatformJwtException("Platform JWT sub is not a UUID", e);
        }

        String[] roles = null;
        Object rolesObj = payload.get("roles");
        if (rolesObj instanceof List<?> rolesList) {
            roles = rolesList.stream().map(Object::toString).toArray(String[]::new);
        }
        boolean mfaVerified = Boolean.TRUE.equals(payload.get("mfaVerified"));
        String scope = payload.get("scope") instanceof String s ? s : null;
        Instant iat = payload.get("iat") instanceof Number n
                ? Instant.ofEpochSecond(n.longValue()) : null;
        return new PlatformJwtClaims(sub, roles, mfaVerified, scope, iat, expInstant);
    }

    private String signPlatformToken(Map<String, Object> payload) {
        PlatformKeyRotationService.ActiveKey active = keyService.findActiveKey();
        try {
            Map<String, String> header = new LinkedHashMap<>();
            header.put("alg", "HS256");
            header.put("typ", "JWT");
            header.put("kid", active.kid());

            String headerJson = objectMapper.writeValueAsString(header);
            String payloadJson = objectMapper.writeValueAsString(payload);
            String headerEncoded = base64UrlEncode(headerJson.getBytes(StandardCharsets.UTF_8));
            String payloadEncoded = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));

            String signingInput = headerEncoded + "." + payloadEncoded;
            byte[] sig = hmac(active.key(), signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + base64UrlEncode(sig);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign platform JWT", e);
        }
    }

    private static byte[] hmac(SecretKey key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 computation failed", e);
        }
    }

    private static String base64UrlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    /**
     * Parsed claims from a validated platform JWT.
     *
     * @param sub          platform_user id
     * @param roles        array of role names; {@code ["PLATFORM_OPERATOR"]} for access tokens, null/empty for scoped tokens
     * @param mfaVerified  true for access tokens issued post-MFA; false / unset on scoped tokens
     * @param scope        scope claim — {@code "mfa-setup"} or {@code "mfa-verify"} for scoped tokens, null on access tokens
     * @param issuedAt     iat as Instant
     * @param expiresAt    exp as Instant
     */
    public record PlatformJwtClaims(
            UUID sub,
            String[] roles,
            boolean mfaVerified,
            String scope,
            Instant issuedAt,
            Instant expiresAt) {
    }
}
