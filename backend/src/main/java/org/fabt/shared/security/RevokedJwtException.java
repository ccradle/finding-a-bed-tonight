package org.fabt.shared.security;

import java.util.UUID;

/**
 * Thrown when a JWT's kid is present in the {@code jwt_revocations}
 * blocklist (revoked via tenant suspend, key rotation, or operator
 * emergency-revoke). Per A4 D26.
 *
 * <p>{@code GlobalExceptionHandler} maps to HTTP 401 (NOT 403) — this
 * is an expired-credential scenario, not a forgery attempt. Distinct
 * from {@link CrossTenantJwtException} (403) on purpose: 401 prompts
 * the client to re-authenticate; 403 indicates a forgery attempt that
 * shouldn't be retried.
 *
 * <p>No audit event published — revoked-kid validate attempts are
 * expected post-rotation (every in-flight token of the prior
 * generation will fail this way until it naturally expires). The
 * legacy {@code claimsCache} miss + {@code jwt_revocations} fast-path
 * are the only signal we need; auditing every revoked attempt would
 * flood the audit log post-rotation.
 *
 * <p>If the operator wants to track repeated revoked-kid attempts as
 * suspicious, the {@code fabt.security.revoked_jwt_validate.count}
 * counter (incremented in handler) provides per-replica visibility.
 */
public class RevokedJwtException extends RuntimeException {

    private final UUID kid;

    public RevokedJwtException(UUID kid) {
        super("JWT kid has been revoked: " + kid);
        this.kid = kid;
    }

    public UUID getKid() {
        return kid;
    }
}
