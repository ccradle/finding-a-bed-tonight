package org.fabt.shared.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Trusted-proxy client IP resolution.
 *
 * <p>Production (Cloudflare → nginx → backend container): nginx sets
 * {@code X-Real-IP} from {@code $remote_addr} (which itself is the
 * Cloudflare edge that connected to nginx) and {@code X-Forwarded-For}
 * with the chain. The backend trusts {@code X-Real-IP} because nginx is
 * the only direct upstream and we explicitly set the header in
 * {@code infra/docker/nginx.conf} (lines 24, 57, 83).</p>
 *
 * <p>Local dev (Vite → backend, no proxy) and Playwright (TestRestTemplate
 * → embedded Tomcat): no proxy in front of the backend, so {@code X-Real-IP}
 * is absent and {@link HttpServletRequest#getRemoteAddr()} is the actual
 * client IP. Same code path works for both.</p>
 *
 * <p><b>Why a separate utility (Marcus warroom B1):</b> rate limiting and
 * abuse-detection counters that key on client IP MUST use this helper —
 * a bare {@code request.getRemoteAddr()} returns nginx's container IP in
 * production, which collapses every distinct client to one bucket / one
 * label and silently neutralizes the throttle / metric. {@code
 * ApiKeyAuthenticationFilter} already had a private copy of this logic;
 * lifted to a shared static utility so {@code ReferralTokenController}
 * and {@code DvReferralCrossSiteFilter} (and any future per-IP code site)
 * resolve consistently. Tracked as F23 to fold the existing private copy
 * into this utility in a separate commit.</p>
 */
public final class ClientIpResolver {

    private ClientIpResolver() {
        // utility
    }

    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
