package org.fabt.shared.security;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Blocks destructive API operations in the demo environment.
 *
 * Activated by the "demo" Spring profile. When active:
 * - GET/HEAD/OPTIONS always pass through (full read access to all screens)
 * - Allowlisted safe mutations pass through (login, bed search, holds, referrals, availability)
 * - All other POST/PUT/PATCH/DELETE return 403 with demo_restricted error
 *
 * Admin bypass: requests from localhost (SSH tunnel to :8080) or from the Docker
 * bridge network without X-Forwarded-For (SSH tunnel to :8081 via container nginx)
 * are exempt. Public traffic always has X-Forwarded-For set by host nginx/Cloudflare.
 */
@Component
@Profile("demo")
public class DemoGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DemoGuardFilter.class);

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /**
     * Safe mutations allowlisted in demo mode. Fail-secure: any endpoint NOT listed
     * here is blocked for non-GET methods. New endpoints are blocked by default.
     */
    private static final List<AllowedMutation> ALLOWED_MUTATIONS = List.of(
            // Authentication
            new AllowedMutation("POST", "/api/v1/auth/login"),
            new AllowedMutation("POST", "/api/v1/auth/refresh"),
            new AllowedMutation("POST", "/api/v1/auth/verify-totp"),
            new AllowedMutation("POST", "/api/v1/auth/enroll-totp"),
            new AllowedMutation("POST", "/api/v1/auth/confirm-totp-enrollment"),
            new AllowedMutation("POST", "/api/v1/auth/access-code"),
            // Bed operations
            new AllowedMutation("POST", "/api/v1/queries/beds"),
            new AllowedMutation("POST", "/api/v1/reservations"),
            new AllowedMutation("PATCH", "/api/v1/reservations/*/confirm"),
            new AllowedMutation("PATCH", "/api/v1/reservations/*/cancel"),
            // DV referrals
            new AllowedMutation("POST", "/api/v1/dv-referrals"),
            new AllowedMutation("PATCH", "/api/v1/dv-referrals/*/accept"),
            new AllowedMutation("PATCH", "/api/v1/dv-referrals/*/reject"),
            // Coordinator availability update
            new AllowedMutation("PATCH", "/api/v1/shelters/*/availability"),
            // Notifications (read/acted/read-all are safe — they only mark timestamps)
            new AllowedMutation("PATCH", "/api/v1/notifications/*/read"),
            new AllowedMutation("PATCH", "/api/v1/notifications/*/acted"),
            new AllowedMutation("POST", "/api/v1/notifications/read-all"),
            // Webhooks
            new AllowedMutation("POST", "/api/v1/subscriptions"),
            new AllowedMutation("DELETE", "/api/v1/subscriptions/*")
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        // Read-only methods always pass
        if (READ_METHODS.contains(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        // Admin bypass: two detection mechanisms (Design D2).
        //
        // Primary (via container nginx): X-FABT-Traffic-Source header.
        //   - Container nginx sets this via a map directive based on XFF presence.
        //   - "tunnel" = no incoming XFF (SSH tunnel to :8081, nothing upstream).
        //   - "public" = incoming XFF present (Cloudflare → host nginx → container nginx).
        //   - proxy_set_header REPLACES client-sent values — forgery impossible.
        //   - Security: port 8081 is 127.0.0.1-only, iptables DROP policy blocks external.
        //
        // Fallback (port 8080 direct): IP-chain check.
        //   - Direct curl to :8080 doesn't go through nginx, so no header is set.
        //   - Falls back to checking remoteAddr + XFF are all private/localhost.
        String trafficSource = request.getHeader("X-FABT-Traffic-Source");
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String remoteAddr = request.getRemoteAddr();

        if ("tunnel".equals(trafficSource)) {
            log.info("Demo guard bypassed: tunnel traffic (X-FABT-Traffic-Source=tunnel), remoteAddr={}", remoteAddr);
            filterChain.doFilter(request, response);
            return;
        }

        if (isInternalTraffic(remoteAddr, forwardedFor)) {
            log.info("Demo guard bypassed: internal IP chain, remoteAddr={}, xff={}", remoteAddr, forwardedFor);
            filterChain.doFilter(request, response);
            return;
        }

        // Check if this mutation is allowlisted
        String method = request.getMethod();
        String path = request.getRequestURI();

        for (AllowedMutation allowed : ALLOWED_MUTATIONS) {
            if (allowed.matches(method, path)) {
                filterChain.doFilter(request, response);
                return;
            }
        }

        // Block: not allowlisted, not localhost, not tunnel
        log.info("Demo guard blocked: {} {} from {} (source={})", method, path, remoteAddr, trafficSource);
        response.setStatus(403);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"error\":\"demo_restricted\","
                + "\"message\":\"" + getBlockMessage(path) + "\","
                + "\"status\":403,"
                + "\"timestamp\":\"" + Instant.now() + "\"}");
    }

    /**
     * Determine if the request is internal (SSH tunnel or Docker-internal) rather than
     * public traffic through Cloudflare. Internal traffic has ONLY private/localhost IPs
     * in the entire request chain (remoteAddr + all X-Forwarded-For entries).
     * Public traffic always has at least one real public IP from Cloudflare.
     */
    private static boolean isInternalTraffic(String remoteAddr, String forwardedFor) {
        if (!isPrivateOrLocalhost(remoteAddr)) return false;
        if (forwardedFor == null || forwardedFor.isBlank()) return true;
        // Check every IP in the X-Forwarded-For chain
        for (String ip : forwardedFor.split(",")) {
            if (!isPrivateOrLocalhost(ip.trim())) return false;
        }
        return true;
    }

    private static boolean isPrivateOrLocalhost(String addr) {
        if (addr == null) return false;
        return addr.equals("127.0.0.1")
                || addr.equals("0:0:0:0:0:0:0:1")
                || addr.equals("::1")
                || isPrivateAddress(addr);
    }

    private static boolean isPrivateAddress(String addr) {
        return addr != null && (
                addr.startsWith("10.") ||
                addr.startsWith("172.16.") || addr.startsWith("172.17.") ||
                addr.startsWith("172.18.") || addr.startsWith("172.19.") ||
                addr.startsWith("172.2") || addr.startsWith("172.3") ||
                addr.startsWith("192.168."));
    }

    private static String getBlockMessage(String path) {
        if (path.startsWith("/api/v1/users")) return "User management is disabled in the demo environment.";
        // Manual offline holds have cross-visitor impact (a held bed disappears from
        // other visitors' bed search results), so they are intentionally not allowlisted
        // and surface a friendly message instead of the generic shelter block.
        if (path.matches("/api/v1/shelters/[^/]+/manual-hold")) {
            return "Manual offline holds are disabled in the demo environment — would interfere with other visitors' bed search results.";
        }
        if (path.startsWith("/api/v1/shelters")) return "Shelter modification is disabled in the demo environment.";
        if (path.startsWith("/api/v1/auth/password")) return "Password changes are disabled in the demo environment.";
        if (path.startsWith("/api/v1/surge")) return "Surge management is disabled in the demo environment.";
        if (path.startsWith("/api/v1/tenants")) return "Tenant management is disabled in the demo environment.";
        if (path.startsWith("/api/v1/import")) return "Data import is disabled in the demo environment.";
        if (path.startsWith("/api/v1/batch")) return "Batch job management is disabled in the demo environment.";
        if (path.startsWith("/api/v1/hmis")) return "HMIS push is disabled in the demo environment.";
        if (path.startsWith("/api/v1/api-keys")) return "API key management is disabled in the demo environment.";
        return "This operation is disabled in the demo environment.";
    }

    private record AllowedMutation(String method, String pattern) {
        boolean matches(String reqMethod, String reqPath) {
            if (!method.equals(reqMethod)) return false;
            if (!pattern.contains("*")) return pattern.equals(reqPath);
            // Simple wildcard: /api/v1/reservations/*/confirm
            String regex = pattern.replace("*", "[^/]+");
            return reqPath.matches(regex);
        }
    }
}
