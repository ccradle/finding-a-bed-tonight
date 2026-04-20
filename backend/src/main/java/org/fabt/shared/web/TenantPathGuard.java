package org.fabt.shared.web;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * URL-path-sink guard for cross-tenant write/read protection (design D11,
 * Phase D — cross-tenant-isolation-audit).
 *
 * <p>Controllers under {@code /api/v1/tenants/{tenantId}/...} accept a
 * tenantId in the URL path. Without this guard a PLATFORM_ADMIN (or any
 * caller with a valid JWT) could address another tenant's resources by
 * substituting a different UUID into the URL — a cross-tenant write via
 * URL manipulation. The backend response must treat any path-supplied
 * tenantId as untrusted input and reconcile it against the authenticated
 * tenant before acting.
 *
 * <p>The rule is simple: if the path tenantId does not equal
 * {@link TenantContext#getTenantId()}, throw {@link NoSuchElementException}
 * ({@code 404}) — matching the existence-leak posture (D3) used across
 * read paths. A {@code 403} would signal the resource exists but is
 * forbidden; {@code 404} is symmetric regardless of actual existence.
 *
 * <p>Call this as the first line of any {@code @PathVariable tenantId}
 * controller method. Services downstream source tenantId from
 * {@code TenantContext}, so even a bypass of this guard cannot reach
 * cross-tenant data — this is the defence-in-depth outermost layer.
 */
public final class TenantPathGuard {

    private TenantPathGuard() {}

    /**
     * Require the URL-path tenantId to match the authenticated tenant.
     *
     * @throws NoSuchElementException (yielding 404 at the controller boundary)
     *         if the path tenantId differs from {@link TenantContext#getTenantId()},
     *         or if no tenant is bound in context.
     */
    public static void requireMatchingTenant(UUID pathTenantId) {
        UUID authenticated = TenantContext.getTenantId();
        if (authenticated == null || !authenticated.equals(pathTenantId)) {
            throw new NoSuchElementException("Tenant not found: " + pathTenantId);
        }
    }
}
