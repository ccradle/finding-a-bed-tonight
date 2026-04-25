package org.fabt.auth.platform;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@link PlatformJwtException} thrown from {@link PlatformAuthController}
 * (and any future {@code @PlatformAdminOnly} endpoint that calls
 * {@link PlatformJwtService#validateToken(String)} directly) into HTTP 401.
 *
 * <p>Scoped via {@code @RestControllerAdvice(assignableTypes = ...)} would
 * over-constrain to one controller; scoped via {@code basePackages} keeps
 * the mapping local to the platform-auth surface so a general
 * {@code IllegalArgumentException} elsewhere doesn't accidentally land here.
 */
@RestControllerAdvice(basePackages = "org.fabt.auth.platform")
public class PlatformAuthExceptionHandler {

    @ExceptionHandler(PlatformJwtException.class)
    public ResponseEntity<Map<String, String>> handleJwtException(PlatformJwtException ex) {
        // Detail (which field of validation failed) stays in server logs only —
        // the wire body is a stable opaque code so the response does not leak
        // which validation step rejected the token (warroom M6).
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "invalid_platform_token"));
    }
}
