package org.fabt.auth.platform;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a platform JWT is structurally valid but the operator's
 * {@code platform_user} row has been anonymized (set
 * {@code anonymized_at IS NOT NULL}) between login and the request.
 *
 * <p>Distinct from {@link PlatformJwtException} (→ 401, "your token is
 * bad — try logging in again"): for an anonymized operator, re-logging-in
 * would also fail (the auth path filters anonymized rows), creating a
 * client-side login loop. {@code @ResponseStatus(GONE)} signals "the
 * resource is gone permanently — don't retry" so the SPA's 410 handler
 * can show "your account has been removed; contact support" without
 * looping.
 */
@ResponseStatus(HttpStatus.GONE)
public class PlatformOperatorAnonymizedException extends RuntimeException {

    public PlatformOperatorAnonymizedException(String message) {
        super(message);
    }
}
