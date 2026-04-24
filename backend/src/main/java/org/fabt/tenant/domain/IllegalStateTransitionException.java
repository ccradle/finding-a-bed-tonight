package org.fabt.tenant.domain;

/**
 * Thrown when a tenant lifecycle transition violates the §D8 FSM (see {@link TenantState}).
 * Callers that map to HTTP should translate to {@code 409 Conflict} — the resource exists
 * but its current state does not permit the requested operation.
 */
public class IllegalStateTransitionException extends RuntimeException {

    private final TenantState from;
    private final TenantState to;

    public IllegalStateTransitionException(TenantState from, TenantState to, String reason) {
        super("illegal tenant state transition " + from + " -> " + to + ": " + reason);
        this.from = from;
        this.to = to;
    }

    public TenantState from() {
        return from;
    }

    public TenantState to() {
        return to;
    }
}
