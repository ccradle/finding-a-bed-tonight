package org.fabt.shared.security;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.fabt.auth.service.ApiKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.fabt.shared.web.TenantContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests bearing the X-API-Key header against stored API key hashes.
 * Includes per-IP rate limiting on failed attempts (5/min) to prevent brute-force
 * key guessing. Returns 429 with Retry-After and X-RateLimit-* headers.
 *
 * Rate limit uses a single atomic tryConsumeAndReturnRemaining(1) call per request.
 * Buckets are stored in a Caffeine cache (max 10,000 IPs, 10 min TTL) to prevent
 * unbounded memory growth from IP rotation attacks.
 *
 * Client IP resolution: trusts X-Real-IP header (set by nginx from CF-Connecting-IP
 * in production, from $remote_addr in local dev). Falls back to getRemoteAddr() if
 * header absent. This is safe because:
 * - Production: iptables restricts 80/443 to Cloudflare IPs only — no direct access
 * - Local dev: no proxy, getRemoteAddr() is the real client IP
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
    private static final String API_KEY_HEADER = "X-API-Key";

    // Configurable via fabt.api-key.rate-limit (default 5/min for production, high for tests)
    private final int rateLimit;
    private final Duration rateWindow = Duration.ofMinutes(1);

    // Caffeine cache: bounded size + TTL eviction to prevent memory DoS from IP rotation
    private final Cache<String, Bucket> rateLimitBuckets = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthenticationFilter(
            ApiKeyService apiKeyService,
            @org.springframework.beans.factory.annotation.Value("${fabt.api-key.rate-limit:5}") int rateLimit) {
        this.apiKeyService = apiKeyService;
        if (rateLimit < 1) {
            log.warn("fabt.api-key.rate-limit={} is below minimum — using 1", rateLimit);
        }
        this.rateLimit = Math.max(rateLimit, 1);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String apiKeyHeader = request.getHeader(API_KEY_HEADER);

        if (apiKeyHeader == null || apiKeyHeader.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        // Only process if no existing authentication
        if (SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        // Resolve client IP: trust X-Real-IP (set by nginx), fall back to remote addr
        String clientIp = resolveClientIp(request);

        // Atomic rate limit check — consumes 1 token per request bearing X-API-Key.
        // Both valid and invalid keys consume tokens. This is intentional:
        // - Valid keys: 5/min is generous for normal API integration use
        // - Invalid keys: prevents brute-force guessing
        // - Attacker can't distinguish valid from invalid based on rate limit behavior
        Bucket bucket = rateLimitBuckets.get(clientIp, k -> createBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (!probe.isConsumed()) {
            long retryAfterSeconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1;
            log.warn("API key rate limit exceeded: ip={}, path={}", clientIp, request.getRequestURI());
            response.setStatus(429);
            response.setContentType("application/json");
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setHeader("X-RateLimit-Limit", String.valueOf(rateLimit));
            response.setHeader("X-RateLimit-Remaining", "0");
            response.setHeader("X-RateLimit-Reset", String.valueOf(retryAfterSeconds));
            response.getWriter().write("{\"error\":\"rate_limited\",\"message\":\"Too many API key attempts. Try again later.\",\"status\":429}");
            return;
        }

        // Add rate limit headers to all API key responses (valid or invalid)
        response.setHeader("X-RateLimit-Limit", String.valueOf(rateLimit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));

        UUID tenantId = null;
        try {
            var optionalKey = apiKeyService.validate(apiKeyHeader);
            if (optionalKey.isPresent()) {
                var apiKey = optionalKey.get();
                List<SimpleGrantedAuthority> authorities =
                        List.of(new SimpleGrantedAuthority("ROLE_" + apiKey.getRole()));

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(apiKey.getId(), null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);

                tenantId = apiKey.getTenantId();
            } else {
                log.debug("Invalid API key from ip={}, path={}, remaining={}", clientIp, request.getRequestURI(), probe.getRemainingTokens());
            }
        } catch (Exception e) {
            log.debug("API key authentication error for request {}: {}", request.getRequestURI(), e.getMessage());
            SecurityContextHolder.clearContext();
        }

        if (tenantId != null) {
            try {
                TenantContext.callWithContext(tenantId, false, () -> {
                    filterChain.doFilter(request, response);
                    return null;
                });
            } catch (ServletException | IOException e) {
                throw e;
            } catch (Exception e) {
                throw new ServletException(e);
            }
        } else {
            filterChain.doFilter(request, response);
        }
    }

    /**
     * Resolve client IP from trusted proxy headers.
     * Production (Cloudflare → nginx): nginx sets X-Real-IP from CF-Connecting-IP.
     * Local dev (no proxy): falls back to getRemoteAddr().
     */
    private String resolveClientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    /** Clear all rate limit buckets. Used by tests to reset state between test methods. */
    public void clearRateLimits() {
        rateLimitBuckets.invalidateAll();
    }

    private Bucket createBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(rateLimit)
                        .refillGreedy(rateLimit, rateWindow)
                        .build())
                .build();
    }
}
