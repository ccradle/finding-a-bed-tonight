package org.fabt.auth.platform;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when the caller presents a structurally-valid platform JWT but
 * the token scope does not match what the endpoint requires. Distinct
 * from {@link PlatformJwtException} (→ 401, "your auth is bad — try
 * again"); this is "your auth is fine but not for this endpoint" (→ 403),
 * which the SPA's 403 handler uses to redirect to the appropriate scoped
 * endpoint without wiping sessionStorage.
 *
 * <p>Concrete cases in v0.54:
 * <ul>
 *   <li>An MFA-setup-only token presented to {@code /me} or {@code /logout}
 *   <li>An MFA-verify-only token presented to {@code /me} or {@code /logout}
 *   <li>An access token without {@code mfaVerified=true} (defense-in-depth;
 *       should never happen with the current {@code generateAccessToken})
 * </ul>
 *
 * <p>{@code @ResponseStatus(FORBIDDEN)} causes
 * {@code GlobalExceptionHandler.handleUnexpected} to honor the 403
 * mapping rather than falling through to its generic 500.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class PlatformScopeMismatchException extends RuntimeException {

    public PlatformScopeMismatchException(String message) {
        super(message);
    }
}
