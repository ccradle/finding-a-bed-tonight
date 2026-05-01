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
     * Reserved sentinel UUID for platform/system-originated audit events
     * and RLS-guarded writes from non-tenant-scoped contexts (batch jobs,
     * migrations, scheduled tasks, platform-admin cross-tenant actions).
     * Phase B D55: every system context MUST bind this UUID via
     * {@code runWithContext(SYSTEM_TENANT_ID, ...)} before any DB write
     * that could hit a regulated table; {@code AuditEventService} falls
     * back to this sentinel with a WARN log when a publisher forgot to
     * bind. No real tenant may use this UUID.
     */
    public static final UUID SYSTEM_TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * @param userId may be null for system operations (scheduled jobs, API key auth)
     */
    public record Context(UUID tenantId, UUID userId, boolean dvAccess) {}

    public static final ScopedValue<Context> CONTEXT = ScopedValue.newInstance();

    /**
     * Round 5 §16.B — request-scoped {@code features.reentryMode} flag,
     * sourced from the JWT claim emitted by {@code JwtService}. Bound by
     * {@code JwtAuthenticationFilter} alongside {@link #CONTEXT}; null /
     * unbound means "not in a JWT-authenticated request" (batch jobs,
     * scheduled tasks, system contexts) which all default to {@code false}
     * so PII is never surfaced from non-tenant contexts.
     *
     * <p>Why a separate {@link ScopedValue} rather than a 4th field on
     * {@link Context}: there are 60+ {@code callWithContext} /
     * {@code runWithContext} sites across batch jobs and services; adding
     * a field would cascade to every one. A separate binding keeps the
     * flag flow explicit (filter binds it; reader checks it) and leaves
     * batch contexts unchanged with the safe default.
     */
    public static final ScopedValue<Boolean> REENTRY_MODE = ScopedValue.newInstance();

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
     * Round 5 §16.B — current request's {@code features.reentryMode} value.
     * Returns {@code false} when unbound (the safe default — batch jobs and
     * unauthenticated requests will not surface PII through serialization
     * gates that consult this method).
     */
    public static boolean getReentryMode() {
        return REENTRY_MODE.isBound() && Boolean.TRUE.equals(REENTRY_MODE.get());
    }

    /**
     * Tenant ID as a metric tag value (D16). Returns "system" for
     * batch/scheduled contexts where TenantContext is unset. Safe for
     * Micrometer tags (never null, never empty).
     */
    public static String tenantTag() {
        UUID tid = getTenantId();
        return tid != null ? tid.toString() : "system";
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

    /**
     * Round 5 §16.B — overload that binds {@link #REENTRY_MODE} alongside
     * the main {@link #CONTEXT}. Used by {@code JwtAuthenticationFilter} to
     * propagate the JWT-claim value into the request scope; readers (e.g.
     * {@code ReservationResponse#from}) consult {@link #getReentryMode()}.
     */
    public static <T, X extends Throwable> T callWithContext(UUID tenantId, UUID userId, boolean dvAccess,
            boolean reentryMode,
            ScopedValue.CallableOp<? extends T, X> action) throws X {
        return ScopedValue.where(CONTEXT, new Context(tenantId, userId, dvAccess))
                .where(REENTRY_MODE, reentryMode)
                .call(action);
    }
}
