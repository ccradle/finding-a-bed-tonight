package org.fabt.shared.security;

import java.util.UUID;

/**
 * Thrown when a JWT's kid resolves to one tenant but the body claim
 * {@code tenantId} names a different tenant — the kid-confusion attack.
 * Per A4 D25: an attacker who steals Tenant A's signing key can sign
 * JWTs for Tenant A's kid, but cannot forge a JWT for Tenant B's
 * kid+key combo even by swapping the body claim. The mismatch surfaces
 * here.
 *
 * <p>{@code GlobalExceptionHandler} maps to HTTP 403 with the D3
 * envelope {@code {"error":"cross_tenant","status":403,...}}. An
 * {@code audit_events} row with action {@code CROSS_TENANT_JWT_REJECTED}
 * is written by the handler. The audit JSONB shape (per warroom W1)
 * carries the offending JWT's body claims so incident responders can
 * reconstruct the attack without retrieving the token from logs:
 * {@code {kid, expectedTenantId, actualTenantId, actorUserId, sourceIp,
 * claimsTenantId, claimsSub, claimsIat, claimsExp}}.
 *
 * <p>Distinct from {@link CrossTenantCiphertextException} (A3 D21)
 * because the attack surface differs (JWT vs ciphertext); the audit
 * actions are parallel by design so dashboards + alert routing can
 * track them as siblings.
 *
 * <p>RuntimeException — A4 Q5 / A3 Q4 reasoning: forgery attempts are
 * "should never happen" failures; forcing every callsite to declare
 * {@code throws} clutters the API for an exception
 * {@code GlobalExceptionHandler} should always catch in practice.
 */
public class CrossTenantJwtException extends RuntimeException {

    private final UUID kid;
    private final UUID expectedTenantId;
    private final UUID actualTenantId;
    private final UUID claimsSub;
    private final Long claimsIat;
    private final Long claimsExp;

    public CrossTenantJwtException(UUID kid, UUID expectedTenantId, UUID actualTenantId,
                                    UUID claimsSub, Long claimsIat, Long claimsExp) {
        super("JWT kid resolves to a different tenant than the body claim. "
                + "kid=" + kid + " expectedTenantId=" + expectedTenantId
                + " actualTenantId=" + actualTenantId);
        this.kid = kid;
        this.expectedTenantId = expectedTenantId;
        this.actualTenantId = actualTenantId;
        this.claimsSub = claimsSub;
        this.claimsIat = claimsIat;
        this.claimsExp = claimsExp;
    }

    public UUID getKid() { return kid; }
    public UUID getExpectedTenantId() { return expectedTenantId; }
    public UUID getActualTenantId() { return actualTenantId; }
    public UUID getClaimsSub() { return claimsSub; }
    public Long getClaimsIat() { return claimsIat; }
    public Long getClaimsExp() { return claimsExp; }
}
