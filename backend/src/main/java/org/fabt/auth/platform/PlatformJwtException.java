package org.fabt.auth.platform;

/**
 * Thrown when an {@code iss = "fabt-platform"} JWT fails validation —
 * malformed, wrong alg, kid mismatch, signature invalid, or expired.
 *
 * <p>Distinct from the tenant-side {@code IllegalArgumentException} +
 * {@code CrossTenantJwtException} hierarchy used by
 * {@link org.fabt.auth.service.JwtService} so that the iss-routed
 * {@code JwtDecoder} dispatch in {@code SecurityConfig} (G-4.2 task 3.9) can
 * funnel platform-token failures through their own exception handler
 * without entangling the tenant cross-check semantics.
 *
 * <p>Maps to HTTP 401 at the auth-controller / security-filter boundary.
 */
public class PlatformJwtException extends RuntimeException {

    public PlatformJwtException(String message) {
        super(message);
    }

    public PlatformJwtException(String message, Throwable cause) {
        super(message, cause);
    }
}
