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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.fabt.auth.domain.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secretKey;
    private final long accessTokenExpiryMinutes;
    private final long refreshTokenExpiryDays;
    private final ObjectMapper objectMapper;
    private final Cache<String, JwtClaims> claimsCache;

    public JwtService(
            @Value("${fabt.jwt.secret:default-dev-secret-change-in-production}") String secret,
            @Value("${fabt.jwt.access-token-expiry-minutes:15}") long accessTokenExpiryMinutes,
            @Value("${fabt.jwt.refresh-token-expiry-days:7}") long refreshTokenExpiryDays,
            ObjectMapper objectMapper) {
        this.secretKey = secret.getBytes(StandardCharsets.UTF_8);
        this.accessTokenExpiryMinutes = accessTokenExpiryMinutes;
        this.refreshTokenExpiryDays = refreshTokenExpiryDays;
        this.objectMapper = objectMapper;
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

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(accessTokenExpiryMinutes * 60);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", user.getId().toString());
        payload.put("tenantId", user.getTenantId().toString());
        payload.put("roles", user.getRoles());
        payload.put("dvAccess", user.isDvAccess());
        payload.put("type", "access");
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", exp.getEpochSecond());

        return buildToken(payload);
    }

    public String generateRefreshToken(User user) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(refreshTokenExpiryDays * 86400);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", user.getId().toString());
        payload.put("type", "refresh");
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", exp.getEpochSecond());

        return buildToken(payload);
    }

    public JwtClaims validateToken(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid JWT format");
        }

        String signaturePart = parts[2];
        String cacheKey = sha256Hex(signaturePart);

        JwtClaims cached = claimsCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        // Verify signature
        String signingInput = parts[0] + "." + parts[1];
        byte[] expectedSignature = hmacSha256(signingInput.getBytes(StandardCharsets.UTF_8));
        byte[] actualSignature = Base64.getUrlDecoder().decode(parts[2]);

        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
            throw new IllegalArgumentException("Invalid JWT signature");
        }

        // Parse payload
        Map<String, Object> payload;
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            payload = objectMapper.readValue(payloadBytes, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JWT payload", e);
        }

        // Check expiry
        long exp = ((Number) payload.get("exp")).longValue();
        Instant expInstant = Instant.ofEpochSecond(exp);
        if (Instant.now().isAfter(expInstant)) {
            throw new IllegalArgumentException("JWT has expired");
        }

        // Build claims
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

        // Calculate TTL for cache: exp - now - 30s
        long ttlSeconds = Duration.between(Instant.now(), expInstant).getSeconds() - 30;
        long remainingNanos = ttlSeconds > 0 ? Duration.ofSeconds(ttlSeconds).toNanos() : 0;

        JwtClaims claims = new JwtClaims(userId, tenantId, roles, dvAccess, type, remainingNanos);

        if (ttlSeconds > 0) {
            claimsCache.put(cacheKey, claims);
        }

        return claims;
    }

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

    private byte[] hmacSha256(byte[] data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretKey, ALGORITHM));
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
                            long remainingNanos) {
    }
}
