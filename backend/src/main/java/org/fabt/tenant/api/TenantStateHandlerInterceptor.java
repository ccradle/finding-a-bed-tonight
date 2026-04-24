package org.fabt.tenant.api;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.fabt.shared.web.TenantContext;
import org.fabt.tenant.service.TenantStateGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Single-point read-path enforcement for F-3.3. Runs after {@code JwtAuthenticationFilter}
 * (and {@code ApiKeyAuthenticationFilter}) have bound {@link TenantContext}, but before
 * the controller method executes. Short-circuits any request whose caller belongs to a
 * non-ACTIVE tenant with a 404 / 503 via {@link TenantStateGuardException} →
 * {@link TenantLifecycleExceptionAdvice}.
 *
 * <p><b>What this covers:</b> every MVC request that landed an authenticated caller on a
 * tenant. The interceptor delegates to {@link TenantStateGuard#requireActive} so the
 * 10s cache bounds DB load. Defense-in-depth complement to the F-3.2 SQL JOIN guards
 * (which protect the target data's tenant state; this protects the caller's tenant
 * state).</p>
 *
 * <p><b>What this does NOT cover (intentionally):</b>
 * <ul>
 *   <li>Pre-auth / public endpoints — no {@link TenantContext} bound, interceptor skips.
 *   <li>Batch jobs / scheduled tasks — not MVC, interceptor doesn't fire; those paths
 *       are explicitly tenant-unscoped or bind {@link TenantContext#SYSTEM_TENANT_ID}.
 *   <li>Platform-admin cross-tenant operations where the admin's tenant is ACTIVE but
 *       the TARGET tenant is non-ACTIVE — the admin's request passes this gate; the
 *       F-3.2 SQL JOIN on tenant-owned reads blocks the target-side access.
 *   <li>{@link TenantContext#SYSTEM_TENANT_ID} — skipped; system tenant is a sentinel,
 *       not a real tenant with a lifecycle.
 * </ul>
 */
@Component
public class TenantStateHandlerInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TenantStateHandlerInterceptor.class);

    private final TenantStateGuard tenantStateGuard;

    public TenantStateHandlerInterceptor(TenantStateGuard tenantStateGuard) {
        this.tenantStateGuard = tenantStateGuard;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            // No tenant context — pre-auth endpoint, public endpoint, or batch path.
            // Nothing to guard. Let the request proceed.
            return true;
        }
        if (TenantContext.SYSTEM_TENANT_ID.equals(tenantId)) {
            // System sentinel — internal batch/migration paths that happen to route
            // through MVC. No lifecycle state to check.
            return true;
        }
        // Throws TenantStateGuardException on non-ACTIVE / NOT_FOUND; caught by
        // TenantLifecycleExceptionAdvice → 404/503 response.
        tenantStateGuard.requireActive(tenantId);
        return true;
    }
}
