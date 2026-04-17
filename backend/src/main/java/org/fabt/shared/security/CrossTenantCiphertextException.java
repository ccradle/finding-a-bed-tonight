package org.fabt.shared.security;

import java.util.UUID;

/**
 * Thrown when a v1 envelope's kid resolves to a different tenant than the
 * caller requested decryption for. Per A3 D21, this is the cryptographic
 * cross-tenant guard for stored secrets — analogous to task 2.10's JWT
 * validate cross-check.
 *
 * <p>{@code GlobalExceptionHandler} maps this to HTTP 403 with the D3
 * envelope {@code {"error":"cross_tenant","status":403,...}}. An
 * {@code audit_events} row with action {@code CROSS_TENANT_CIPHERTEXT_REJECTED}
 * is written by the throwing code path with the JSONB
 * {@code {kid, expectedTenantId, actualTenantId, actorUserId, sourceIp}}
 * shape per warroom Q5.
 *
 * <p>Distinct exception type rather than reusing {@code IllegalStateException}
 * so:
 * <ul>
 *   <li>incident responders can grep distinctly for cross-tenant
 *       cryptographic events (per Casey + Maria)</li>
 *   <li>{@code GlobalExceptionHandler} can map to 403 (rather than 500)
 *       cleanly via {@code instanceof}</li>
 *   <li>Grafana panels and alert routing can target this class
 *       independently of unrelated runtime failures</li>
 * </ul>
 *
 * <p>RuntimeException (not checked) per Alex: this is a "should never
 * happen" failure mode — caller bug or attack. Forcing every callsite to
 * declare {@code throws} would clutter the API for an exception that
 * GlobalExceptionHandler should always catch in practice.
 */
public class CrossTenantCiphertextException extends RuntimeException {

    private final UUID kid;
    private final UUID expectedTenantId;
    private final UUID actualTenantId;

    public CrossTenantCiphertextException(UUID kid, UUID expectedTenantId, UUID actualTenantId) {
        super("Ciphertext kid resolves to a different tenant than the caller requested. "
                + "kid=" + kid + " expectedTenantId=" + expectedTenantId
                + " actualTenantId=" + actualTenantId);
        this.kid = kid;
        this.expectedTenantId = expectedTenantId;
        this.actualTenantId = actualTenantId;
    }

    public UUID getKid() { return kid; }
    public UUID getExpectedTenantId() { return expectedTenantId; }
    public UUID getActualTenantId() { return actualTenantId; }
}
