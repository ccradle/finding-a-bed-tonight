package org.fabt.shared.web;

import java.util.UUID;

public final class TenantContext {

    private record Context(UUID tenantId, boolean dvAccess) {}

    private static final ThreadLocal<Context> CONTEXT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(UUID tenantId) {
        Context current = CONTEXT.get();
        boolean dvAccess = current != null && current.dvAccess();
        CONTEXT.set(new Context(tenantId, dvAccess));
    }

    public static UUID getTenantId() {
        Context current = CONTEXT.get();
        return current != null ? current.tenantId() : null;
    }

    public static void setDvAccess(boolean dvAccess) {
        Context current = CONTEXT.get();
        UUID tenantId = current != null ? current.tenantId() : null;
        CONTEXT.set(new Context(tenantId, dvAccess));
    }

    public static boolean getDvAccess() {
        Context current = CONTEXT.get();
        return current != null && current.dvAccess();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
