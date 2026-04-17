package org.fabt.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import jakarta.annotation.PostConstruct;
import org.fabt.auth.domain.User;
import org.fabt.shared.security.CrossTenantJwtException;
import org.fabt.shared.security.KeyDerivationService;
import org.fabt.shared.security.KidRegistryService;
import org.fabt.shared.security.RevokedJwtException;
import org.fabt.shared.security.RevokedKidCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Hand-rolled JWT signing + validation service.
 *
 * <p><b>Why hand-rolled (warroom decision 2026-04-17):</b> the file is
 * ~480 lines, auditable in under an hour. We don't need the JOSE breadth
 * a library would provide (no JWE encryption, no asymmetric algos, no
 * JWKS rotation). The trade-off — we own correctness of the security-
 * critical paths — is documented + tested per the OWASP audit below.
 *
 * <p><b>OWASP JWT defenses + where each lives in this file:</b>
 * <ul>
 *   <li><b>Algorithm whitelisting</b> — explicit {@code alg == "HS256"}
 *       gate before signature verify; rejects {@code alg=none}
 *       (CVE-2015-9235 family) + algorithm confusion attacks.
 *       Verified by {@code JwtServiceSecurityAttackTest}: 5 alg-attack
 *       cases.</li>
 *   <li><b>Constant-time signature compare</b> — {@code MessageDigest.isEqual}
 *       (timing-safe). Verified: signature byte-tamper test.</li>
 *   <li><b>Base64 URL decoding</b> — {@code Base64.getUrlDecoder()},
 *       not standard Base64. Verified: malformed-input tests.</li>
 *   <li><b>Mandatory expiry</b> — explicit {@code Instant.now().isAfter(exp)}
 *       check; missing {@code exp} also rejects. Verified: 2 expiry tests.</li>
 *   <li><b>Cache keyed by signature hash</b> — repeat validations skip
 *       work without exposing signing material; revocation check happens
 *       BEFORE cache lookup so revoked kids never serve cached claims
 *       (Phase A4 addition).</li>
 *   <li><b>Phase A4 cross-tenant claim cross-check</b> — kid resolves to
 *       a tenant; body claim {@code tenantId} must match;
 *       {@link CrossTenantJwtException} on mismatch. Defends against
 *       kid-confusion forgery.</li>
 * </ul>
 *
 * <p><b>Re-evaluate library adoption when:</b> (1) Phase F regulated tier
 * adds RS256/ES256 asymmetric signing for HIPAA BAA tenants; (2) this
 * file exceeds ~1000 lines; (3) a procurement reviewer at a regulated
 * tenant explicitly disqualifies hand-rolled crypto. Library choice if
 * triggered: {@code nimbus-jose-jwt} (Apache 2.0, FIPS-validatable, used
 * by Spring Security OAuth2).
 */
@Service
public class JwtService {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String DEV_DEFAULT_SECRET = "default-dev-secret-change-in-production";

    private final String secret;
    private final byte[] secretKey;
    private final long accessTokenExpiryMinutes;
    private final long refreshTokenExpiryDays;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final Cache<String, JwtClaims> claimsCache;

    // Phase A4 dependencies. Optional (null in pre-A4 unit tests) so the
    // standalone JwtServiceSecurityAttackTest + SecurityStartupTest can
    // exercise the legacy path without wiring per-tenant infrastructure.
    private final KeyDerivationService keyDerivationService;
    private final KidRegistryService kidRegistryService;
    private final RevokedKidCache revokedKidCache;

    public JwtService(
            @Value("${fabt.jwt.secret:default-dev-secret-change-in-production}") String secret,
            @Value("${fabt.jwt.access-token-expiry-minutes:15}") long accessTokenExpiryMinutes,
            @Value("${fabt.jwt.refresh-token-expiry-days:7}") long refreshTokenExpiryDays,
            ObjectMapper objectMapper,
            Environment environment,
            KeyDerivationService keyDerivationService,
            KidRegistryService kidRegistryService,
            RevokedKidCache revokedKidCache) {
        this.secret = secret;
        this.secretKey = secret.getBytes(StandardCharsets.UTF_8);
        this.accessTokenExpiryMinutes = accessTokenExpiryMinutes;
        this.refreshTokenExpiryDays = refreshTokenExpiryDays;
        this.objectMapper = objectMapper;
        this.environment = environment;
        this.keyDerivationService = keyDerivationService;
        this.kidRegistryService = kidRegistryService;
        this.revokedKidCache = revokedKidCache;
        this.claimsCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfter(new Expiry<String, JwtClaims>() {
                    @Override
                    public long expireAfterCreate(String key, JwtClaims value, long currentTime) {
                        return value.remainingNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, JwtClaims value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }

                    @Override
                    public long expireAfterRead(String key, JwtClaims value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    @PostConstruct
    void validateJwtSecret() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "FABT_JWT_SECRET must be set. Generate with: openssl rand -base64 64");
        }
        if (secret.length() < 32) {
            throw new IllegalStateException(
                    "FABT_JWT_SECRET is too short. Minimum 32 characters (256 bits). " +
                    "Generate with: openssl rand -base64 64");
        }
        java.util.Set<String> activeProfiles = java.util.Set.of(environment.getActiveProfiles());
        if (DEV_DEFAULT_SECRET.equals(secret) && activeProfiles.contains("prod")) {
            throw new IllegalStateException(
                    "FABT_JWT_SECRET must not use the default dev secret in production. " +
                    "Generate with: openssl rand -base64 64");
        }
    }

    public long getAccessTokenExpirySeconds() {
        return accessTokenExpiryMinutes * 60;
    }

    public String generateAccessToken(User user) {
        return generateAccessToken(user, null);
    }

    public String generateAccessToken(User user, String tenantName) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(accessTokenExpiryMinutes * 60);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", user.getId().toString());
        payload.put("tenantId", user.getTenantId().toString());
        if (tenantName != null) payload.put("tenantName", tenantName);
        payload.put("displayName", user.getDisplayName());
        payload.put("roles", user.getRoles());
        payload.put("dvAccess", user.isDvAccess());
        payload.put("ver", user.getTokenVersion());
        payload.put("type", "access");
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", exp.getEpochSecond());

        return buildTokenForUser(user, payload);
    }

    /**
     * Generate an access token with mustChangePassword=true claim.
     * Used after access-code login — user must set new password before accessing other endpoints.
     */
    public String generateAccessTokenWithPasswordChange(User user) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(accessTokenExpiryMinutes * 60);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", user.getId().toString());
        payload.put("tenantId", user.getTenantId().toString());
        payload.put("displayName", user.getDisplayName());
        payload.put("roles", user.getRoles());
        payload.put("dvAccess", user.isDvAccess());
        payload.put("ver", user.getTokenVersion());
        payload.put("type", "access");
        payload.put("mustChangePassword", true);
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", exp.getEpochSecond());

        return buildTokenForUser(user, payload);
    }

    public String generateRefreshToken(User user) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(refreshTokenExpiryDays * 86400);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", user.getId().toString());
        // Phase A4: refresh tokens now carry tenantId so the validate-side
        // cross-check (D25) can verify kid-resolved-tenant == claim.tenantId.
        // Pre-A4 refresh tokens (no tenantId, no kid header) continue to
        // validate via the legacy path during the 7-day cutover window (D28).
        payload.put("tenantId", user.getTenantId().toString());
        payload.put("type", "refresh");
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", exp.getEpochSecond());

        return buildTokenForUser(user, payload);
    }

    /**
     * Generate a short-lived mfaToken proving password was correct.
     * Contains purpose="mfa" and a unique jti for single-use enforcement.
     * Not a regular access token — JwtAuthenticationFilter must skip it.
     */
    public String generateMfaToken(User user) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(300); // 5 minutes

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", user.getId().toString());
        // Phase A4: tenantId added so per-tenant signing + cross-tenant
        // validate cross-check (D25) work for MFA tokens too.
        payload.put("tenantId", user.getTenantId().toString());
        payload.put("type", "mfa");
        payload.put("jti", UUID.randomUUID().toString());
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", exp.getEpochSecond());

        return buildTokenForUser(user, payload);
    }

    public JwtClaims validateToken(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid JWT format");
        }

        // Parse + validate header. Always done first (cheap; sets up alg
        // whitelist + path selection). OWASP: explicit alg check defends
        // against alg=none + algorithm confusion (CVE-2015-9235 family).
        Map<String, Object> header;
        try {
            byte[] headerBytes = Base64.getUrlDecoder().decode(parts[0]);
            header = objectMapper.readValue(headerBytes, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JWT header", e);
        }
        String alg = (String) header.get("alg");
        if (!"HS256".equals(alg)) {
            throw new IllegalArgumentException(
                    "Unsupported JWT algorithm: " + alg + ". Only HS256 is accepted.");
        }

        // Phase A4 W2 path selection — explicit if/else on kid header
        // presence, NOT try-new-catch-fall-back. The latter would
        // silently legacy-accept a JWT with an unknown kid (a forgery
        // attempt where the attacker invented a kid value).
        Object kidObj = header.get("kid");
        if (kidObj == null) {
            return validateLegacy(parts);
        }
        UUID kid;
        try {
            kid = UUID.fromString(kidObj.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid JWT kid format", e);
        }
        return validateNew(parts, kid);
    }

    /**
     * Legacy validate path — Phase 0/A0 JWTs signed under
     * {@code FABT_JWT_SECRET} HMAC. Per A4 D28, accepted for the 7-day
     * cutover window after Phase A4 deploy (bounded by refresh-token max
     * lifetime). After the window, this method is removed and the
     * {@code fabt.security.legacy_jwt_validate.count} counter goes
     * permanently zero.
     */
    private JwtClaims validateLegacy(String[] parts) {
        String signaturePart = parts[2];
        String cacheKey = sha256Hex(signaturePart);

        JwtClaims cached = claimsCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        // Verify signature (constant-time compare via MessageDigest.isEqual)
        String signingInput = parts[0] + "." + parts[1];
        byte[] expectedSignature = hmacSha256(signingInput.getBytes(StandardCharsets.UTF_8));
        byte[] actualSignature = Base64.getUrlDecoder().decode(parts[2]);

        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
            throw new IllegalArgumentException("Invalid JWT signature");
        }

        return parseClaimsAndCache(parts, cacheKey);
    }

    /**
     * Phase A4 new validate path — JWTs signed with per-tenant HKDF-derived
     * keys, identified by opaque kid header.
     *
     * <p>Order matters:
     * <ol>
     *   <li>{@link RevokedKidCache#isRevoked(UUID)} — fast-path; rejected
     *       kid skips signature verify entirely</li>
     *   <li>{@link KidRegistryService#resolveKid(UUID)} — kid → (tenant,
     *       generation); unknown kid presents as
     *       {@link CrossTenantJwtException} per the C-A3-1 pattern (no
     *       404-vs-403 tenant-existence side channel)</li>
     *   <li>Cache lookup — placed AFTER revocation + kid resolve so a
     *       revoked kid never serves cached claims</li>
     *   <li>Per-tenant key derive + HMAC verify</li>
     *   <li>Payload parse + expiry check</li>
     *   <li>D25 cross-tenant claim cross-check —
     *       {@code claim.tenantId == kid-resolved tenantId}</li>
     * </ol>
     */
    private JwtClaims validateNew(String[] parts, UUID kid) {
        // 1. Fast-path revocation check
        if (revokedKidCache != null && revokedKidCache.isRevoked(kid)) {
            throw new RevokedJwtException(kid);
        }

        // 2. Resolve kid -> (tenant, generation). Unknown-kid translates
        //    to CrossTenantJwtException per the A3 C-A3-1 pattern.
        KidRegistryService.KidResolution resolved;
        try {
            resolved = kidRegistryService.resolveKid(kid);
        } catch (java.util.NoSuchElementException unknownKid) {
            throw new CrossTenantJwtException(kid, null,
                    java.util.UUID.fromString("00000000-0000-0000-0000-000000000000"),
                    null, null, null);
        }

        // 3. Cache lookup AFTER revocation + kid resolve so revoked kids
        //    never serve cached claims (A4 fix to a subtle pre-A3 bug).
        String signaturePart = parts[2];
        String cacheKey = sha256Hex(signaturePart);
        JwtClaims cached = claimsCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 4. Derive per-tenant signing key + verify signature
        javax.crypto.SecretKey signingKey =
                keyDerivationService.deriveJwtSigningKey(resolved.tenantId());
        String signingInput = parts[0] + "." + parts[1];
        byte[] expectedSignature = hmacWithKey(signingKey, signingInput.getBytes(StandardCharsets.UTF_8));
        byte[] actualSignature = Base64.getUrlDecoder().decode(parts[2]);
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
            throw new IllegalArgumentException("Invalid JWT signature");
        }

        // 5. Parse payload + 6. cross-tenant claim cross-check (D25)
        Map<String, Object> payload;
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            payload = objectMapper.readValue(payloadBytes, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JWT payload", e);
        }

        UUID claimsTenantId = payload.containsKey("tenantId")
                ? UUID.fromString((String) payload.get("tenantId"))
                : null;
        if (claimsTenantId == null || !claimsTenantId.equals(resolved.tenantId())) {
            UUID claimsSub = null;
            try {
                if (payload.containsKey("sub")) {
                    claimsSub = UUID.fromString((String) payload.get("sub"));
                }
            } catch (IllegalArgumentException ignored) {
                // sub may not be a UUID for some token types; tolerated
            }
            Long iat = payload.containsKey("iat")
                    ? ((Number) payload.get("iat")).longValue() : null;
            Long expL = payload.containsKey("exp")
                    ? ((Number) payload.get("exp")).longValue() : null;
            throw new CrossTenantJwtException(kid, claimsTenantId,
                    resolved.tenantId(), claimsSub, iat, expL);
        }

        // Common claims-build-and-cache path (legacy + new both end here)
        return buildClaimsFromPayloadAndCache(payload, cacheKey);
    }

    /**
     * Common path: parses payload + checks expiry + builds JwtClaims +
     * populates cache. Used by validateLegacy AFTER signature verify.
     * Used by validateNew indirectly via {@link #buildClaimsFromPayloadAndCache}
     * (since validateNew already parsed payload for the cross-tenant
     * check, it skips the parse here).
     */
    private JwtClaims parseClaimsAndCache(String[] parts, String cacheKey) {
        Map<String, Object> payload;
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            payload = objectMapper.readValue(payloadBytes, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JWT payload", e);
        }
        return buildClaimsFromPayloadAndCache(payload, cacheKey);
    }

    private JwtClaims buildClaimsFromPayloadAndCache(Map<String, Object> payload, String cacheKey) {
        long exp = ((Number) payload.get("exp")).longValue();
        Instant expInstant = Instant.ofEpochSecond(exp);
        if (Instant.now().isAfter(expInstant)) {
            throw new IllegalArgumentException("JWT has expired");
        }

        UUID userId = UUID.fromString((String) payload.get("sub"));
        String type = (String) payload.get("type");

        UUID tenantId = payload.containsKey("tenantId")
                ? UUID.fromString((String) payload.get("tenantId"))
                : null;

        String[] roles = null;
        if (payload.containsKey("roles") && payload.get("roles") != null) {
            Object rolesObj = payload.get("roles");
            if (rolesObj instanceof java.util.List<?> rolesList) {
                roles = rolesList.stream()
                        .map(Object::toString)
                        .toArray(String[]::new);
            }
        }

        boolean dvAccess = payload.containsKey("dvAccess")
                && Boolean.TRUE.equals(payload.get("dvAccess"));

        Instant issuedAt = payload.containsKey("iat")
                ? Instant.ofEpochSecond(((Number) payload.get("iat")).longValue())
                : null;

        int tokenVersion = payload.containsKey("ver")
                ? ((Number) payload.get("ver")).intValue()
                : 0;

        long ttlSeconds = Duration.between(Instant.now(), expInstant).getSeconds() - 30;
        long remainingNanos = ttlSeconds > 0 ? Duration.ofSeconds(ttlSeconds).toNanos() : 0;

        String jti = payload.containsKey("jti") ? String.valueOf(payload.get("jti")) : null;
        boolean mustChangePassword = payload.containsKey("mustChangePassword")
                && Boolean.TRUE.equals(payload.get("mustChangePassword"));
        JwtClaims claims = new JwtClaims(userId, tenantId, roles, dvAccess, type,
                issuedAt, tokenVersion, remainingNanos, jti, mustChangePassword);

        if (ttlSeconds > 0) {
            claimsCache.put(cacheKey, claims);
        }
        return claims;
    }

    /**
     * Phase A4 build path. If the user's tenantId resolves to an active
     * kid via {@link KidRegistryService} AND the new infrastructure is
     * wired (non-null), emit a v1 JWT: kid header + per-tenant signing
     * key. Otherwise fall back to the legacy v0 path (no kid, FABT_JWT_SECRET
     * signing). The legacy fallback is what tests + pre-A4 environments
     * without keyDerivationService use.
     */
    private String buildTokenForUser(User user, Map<String, Object> payload) {
        if (keyDerivationService == null || kidRegistryService == null) {
            // Pre-A4 / unit-test fallback — emit a legacy v0 token.
            return buildToken(payload);
        }
        UUID kid = kidRegistryService.findOrCreateActiveKid(user.getTenantId());
        javax.crypto.SecretKey signingKey =
                keyDerivationService.deriveJwtSigningKey(user.getTenantId());
        return buildTokenWithKidAndKey(payload, kid, signingKey);
    }

    /**
     * Legacy build path — no kid header, signs with the platform
     * FABT_JWT_SECRET. Kept callable from {@link #buildTokenForUser}'s
     * fallback so unit-test surfaces work without the new dependencies.
     */
    private String buildToken(Map<String, Object> payload) {
        try {
            Map<String, String> header = Map.of("alg", "HS256", "typ", "JWT");
            String headerJson = objectMapper.writeValueAsString(header);
            String payloadJson = objectMapper.writeValueAsString(payload);
            String headerEncoded = base64UrlEncode(headerJson.getBytes(StandardCharsets.UTF_8));
            String payloadEncoded = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));
            String signingInput = headerEncoded + "." + payloadEncoded;
            byte[] signature = hmacSha256(signingInput.getBytes(StandardCharsets.UTF_8));
            String signatureEncoded = base64UrlEncode(signature);
            return signingInput + "." + signatureEncoded;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate JWT", e);
        }
    }

    /**
     * v1 build path — emits {@code "kid"} header + signs with the
     * supplied per-tenant {@link javax.crypto.SecretKey}. The kid is
     * opaque per A4 D24 — no tenant identity leak via header inspection.
     */
    private String buildTokenWithKidAndKey(Map<String, Object> payload, UUID kid,
                                             javax.crypto.SecretKey signingKey) {
        try {
            Map<String, String> header = new LinkedHashMap<>();
            header.put("alg", "HS256");
            header.put("typ", "JWT");
            header.put("kid", kid.toString());

            String headerJson = objectMapper.writeValueAsString(header);
            String payloadJson = objectMapper.writeValueAsString(payload);
            String headerEncoded = base64UrlEncode(headerJson.getBytes(StandardCharsets.UTF_8));
            String payloadEncoded = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));

            String signingInput = headerEncoded + "." + payloadEncoded;
            byte[] signature = hmacWithKey(signingKey, signingInput.getBytes(StandardCharsets.UTF_8));
            String signatureEncoded = base64UrlEncode(signature);

            return signingInput + "." + signatureEncoded;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate v1 JWT", e);
        }
    }

    private byte[] hmacSha256(byte[] data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretKey, ALGORITHM));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 computation failed", e);
        }
    }

    private static byte[] hmacWithKey(javax.crypto.SecretKey key, byte[] data) {
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

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record JwtClaims(UUID userId, UUID tenantId, String[] roles, boolean dvAccess, String type,
                            Instant issuedAt, int tokenVersion, long remainingNanos, String jti,
                            boolean mustChangePassword) {
    }
}
