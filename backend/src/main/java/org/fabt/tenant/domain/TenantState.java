package org.fabt.tenant.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Tenant lifecycle state. Mirrors the PostgreSQL {@code tenant_state} enum declared in
 * migration V60 ({@code ACTIVE | SUSPENDED | OFFBOARDING | ARCHIVED | DELETED}). The FSM
 * is the authoritative gate for write-path operations — see
 * {@code multi-tenant-production-readiness} design §D8.
 *
 * <p>Allowed transitions (design §D8):</p>
 * <pre>
 *   ACTIVE     -> SUSPENDED, OFFBOARDING
 *   SUSPENDED  -> ACTIVE, OFFBOARDING
 *   OFFBOARDING -> ARCHIVED
 *   ARCHIVED   -> DELETED
 *   DELETED    -> (terminal; no outgoing transitions)
 * </pre>
 *
 * <p>Disallowed transitions of note: {@code DELETED -> *} and {@code ARCHIVED -> ACTIVE}
 * (re-onboarding is a fresh {@code create}, not a revival). Self-transitions are rejected
 * so idempotency-checking callers surface as 409 rather than a silent no-op.</p>
 */
public enum TenantState {
    ACTIVE,
    SUSPENDED,
    OFFBOARDING,
    ARCHIVED,
    DELETED;

    private Set<TenantState> allowedNext;

    static {
        ACTIVE.allowedNext = EnumSet.of(SUSPENDED, OFFBOARDING);
        SUSPENDED.allowedNext = EnumSet.of(ACTIVE, OFFBOARDING);
        OFFBOARDING.allowedNext = EnumSet.of(ARCHIVED);
        ARCHIVED.allowedNext = EnumSet.of(DELETED);
        DELETED.allowedNext = EnumSet.noneOf(TenantState.class);
    }

    public boolean canTransitionTo(TenantState target) {
        return allowedNext.contains(target);
    }

    public Set<TenantState> allowedNext() {
        return EnumSet.copyOf(allowedNext);
    }

    /**
     * Asserts that transitioning {@code from -> to} is permitted by §D8. Throws
     * {@link IllegalStateTransitionException} when the transition would violate the FSM,
     * including when {@code from == to} (self-transitions are an idempotency concern and
     * must be handled by the caller before reaching the assertion).
     */
    public static void assertTransition(TenantState from, TenantState to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to must be non-null");
        }
        if (from == to) {
            throw new IllegalStateTransitionException(from, to,
                "self-transition not allowed — caller must no-op or return 409 before calling assertTransition");
        }
        if (!from.canTransitionTo(to)) {
            throw new IllegalStateTransitionException(from, to,
                "transition not permitted by FSM §D8");
        }
    }
}
