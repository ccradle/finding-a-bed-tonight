package org.fabt.shared.web;

import java.util.UUID;

/**
 * Holds tenant identity and DV access for the current execution scope.
 *
 * Uses {@link ScopedValue} (Java 25, JEP 506) instead of ThreadLocal — immutable,
 * automatically inherited by child virtual threads, no manual cleanup needed.
 *
 * Context is bound via {@link #runWithContext} or {@link #callWithContext} and
 * automatically released when the scope exits.
 */
public final class TenantContext {

    public record Context(UUID tenantId, boolean dvAccess) {}

    public static final ScopedValue<Context> CONTEXT = ScopedValue.newInstance();

    private TenantContext() {}

    public static UUID getTenantId() {
        return CONTEXT.isBound() ? CONTEXT.get().tenantId() : null;
    }

    public static boolean getDvAccess() {
        return CONTEXT.isBound() && CONTEXT.get().dvAccess();
    }

    /**
     * Execute a {@link Runnable} with tenant context bound. Context is automatically
     * released when the action completes. Supports nesting (inner scope overrides outer).
     */
    public static void runWithContext(UUID tenantId, boolean dvAccess, Runnable action) {
        ScopedValue.where(CONTEXT, new Context(tenantId, dvAccess)).run(action);
    }

    /**
     * Execute a {@link ScopedValue.CallableOp} with tenant context bound. Context is
     * automatically released when the action completes. Use for code that throws checked
     * exceptions (e.g., servlet filter chains).
     */
    public static <T, X extends Throwable> T callWithContext(UUID tenantId, boolean dvAccess,
            ScopedValue.CallableOp<? extends T, X> action) throws X {
        return ScopedValue.where(CONTEXT, new Context(tenantId, dvAccess)).call(action);
    }
}
