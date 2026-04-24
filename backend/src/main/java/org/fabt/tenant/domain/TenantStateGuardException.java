package org.fabt.tenant.domain;

import java.util.UUID;

/**
 * Thrown when an operation targets a tenant whose lifecycle state forbids the request.
 * Drives the F-3 read-path enforcement: reads from non-ACTIVE tenants return 404 (no
 * existence leak per design §D3), writes return 503 with {@code Retry-After}. See
 * {@link org.fabt.shared.web.GlobalExceptionHandler} for the HTTP-method branching.
 *
 * <p>Two shapes:
 * <ul>
 *   <li>{@link Kind#NOT_FOUND} — the tenant id does not resolve to a row. Always 404,
 *       regardless of HTTP method. This is structurally identical to "wrong tenant"
 *       (an attacker probing UUIDs gets the same signal whether the tenant exists or
 *       not), which is the point.</li>
 *   <li>{@link Kind#NON_ACTIVE} — the tenant exists but is SUSPENDED / OFFBOARDING /
 *       ARCHIVED / DELETED. 404 on reads (no existence leak), 503 with
 *       {@code Retry-After} on writes (operator action required before the tenant
 *       can accept mutations again).</li>
 * </ul>
 */
public class TenantStateGuardException extends RuntimeException {

    public enum Kind {
        NOT_FOUND,
        NON_ACTIVE
    }

    private final Kind kind;
    private final UUID tenantId;
    private final TenantState observedState;

    public static TenantStateGuardException notFound(UUID tenantId) {
        return new TenantStateGuardException(Kind.NOT_FOUND, tenantId, null,
            "tenant not found: " + tenantId);
    }

    public static TenantStateGuardException nonActive(UUID tenantId, TenantState observedState) {
        return new TenantStateGuardException(Kind.NON_ACTIVE, tenantId, observedState,
            "tenant " + tenantId + " is not ACTIVE (observed state: " + observedState + ")");
    }

    private TenantStateGuardException(Kind kind, UUID tenantId, TenantState observedState,
                                       String message) {
        super(message);
        this.kind = kind;
        this.tenantId = tenantId;
        this.observedState = observedState;
    }

    public Kind kind() {
        return kind;
    }

    public UUID tenantId() {
        return tenantId;
    }

    /**
     * Observed state for {@link Kind#NON_ACTIVE}; {@code null} for {@link Kind#NOT_FOUND}.
     * Deliberately NOT surfaced in the HTTP response body — leaking "SUSPENDED" vs
     * "OFFBOARDING" vs "ARCHIVED" tells an attacker where in the lifecycle a tenant
     * sits. Controllers / exception handlers see this for logging + audit; response
     * bodies get a generic "not_found" or "service_unavailable".
     */
    public TenantState observedState() {
        return observedState;
    }
}
