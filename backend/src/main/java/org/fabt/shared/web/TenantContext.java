package org.fabt.shared.web;

import java.util.UUID;

/**
 * Holds tenant identity, user identity, and DV access for the current execution scope.
 *
 * Uses {@link ScopedValue} (Java 25, JEP 506) instead of ThreadLocal — immutable,
 * automatically inherited by child virtual threads, no manual cleanup needed.
 *
 * Context is bound via {@link #runWithContext} or {@link #callWithContext} and
 * automatically released when the scope exits.
 */
public final class TenantContext {

    /**
     * @param userId may be null for system operations (scheduled jobs, API key auth)
     */
    public record Context(UUID tenantId, UUID userId, boolean dvAccess) {}

    public static final ScopedValue<Context> CONTEXT = ScopedValue.newInstance();

    private TenantContext() {}

    public static UUID getTenantId() {
        return CONTEXT.isBound() ? CONTEXT.get().tenantId() : null;
    }

    public static UUID getUserId() {
        return CONTEXT.isBound() ? CONTEXT.get().userId() : null;
    }

    public static boolean getDvAccess() {
        return CONTEXT.isBound() && CONTEXT.get().dvAccess();
    }

    /**
     * Execute a {@link Runnable} with tenant context bound. Context is automatically
     * released when the action completes. Supports nesting (inner scope overrides outer).
     * userId defaults to null (system operation).
     */
    public static void runWithContext(UUID tenantId, boolean dvAccess, Runnable action) {
        ScopedValue.where(CONTEXT, new Context(tenantId, null, dvAccess)).run(action);
    }

    public static void runWithContext(UUID tenantId, UUID userId, boolean dvAccess, Runnable action) {
        ScopedValue.where(CONTEXT, new Context(tenantId, userId, dvAccess)).run(action);
    }

    /**
     * Execute a {@link ScopedValue.CallableOp} with tenant context bound. Context is
     * automatically released when the action completes. Use for code that throws checked
     * exceptions (e.g., servlet filter chains).
     * userId defaults to null (system operation).
     */
    public static <T, X extends Throwable> T callWithContext(UUID tenantId, boolean dvAccess,
            ScopedValue.CallableOp<? extends T, X> action) throws X {
        return ScopedValue.where(CONTEXT, new Context(tenantId, null, dvAccess)).call(action);
    }

    public static <T, X extends Throwable> T callWithContext(UUID tenantId, UUID userId, boolean dvAccess,
            ScopedValue.CallableOp<? extends T, X> action) throws X {
        return ScopedValue.where(CONTEXT, new Context(tenantId, userId, dvAccess)).call(action);
    }
}
