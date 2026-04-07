package org.fabt.shared.security;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
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
 * key guessing. Returns 429 with Retry-After when rate limit exceeded.
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
    private static final String API_KEY_HEADER = "X-API-Key";

    // Per-IP rate limiting for failed API key attempts: 5 per minute
    private static final int FAILURE_LIMIT = 5;
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(1);
    private final ConcurrentHashMap<String, Bucket> failureBuckets = new ConcurrentHashMap<>();

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
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

        // Check if this IP is rate-limited before attempting validation
        String clientIp = request.getRemoteAddr();
        Bucket failureBucket = getFailureBucket(clientIp);
        if (!failureBucket.tryConsume(0)) {
            // Already exhausted — don't even attempt validation
            log.warn("API key rate limit exceeded: ip={}, path={}", clientIp, request.getRequestURI());
            response.setStatus(429);
            response.setContentType("application/json");
            response.setHeader("Retry-After", "60");
            response.getWriter().write("{\"error\":\"rate_limited\",\"message\":\"Too many failed API key attempts. Try again later.\",\"status\":429}");
            return;
        }

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
                // Invalid key — consume a token from the failure bucket
                failureBucket.tryConsume(1);
                log.debug("Invalid API key from ip={}, path={}", clientIp, request.getRequestURI());
            }
        } catch (Exception e) {
            log.debug("API key authentication failed for request {}: {}",
                    request.getRequestURI(), e.getMessage());
            failureBucket.tryConsume(1);
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

    private Bucket getFailureBucket(String ip) {
        return failureBuckets.computeIfAbsent(ip, k ->
                Bucket.builder()
                        .addLimit(Bandwidth.builder()
                                .capacity(FAILURE_LIMIT)
                                .refillGreedy(FAILURE_LIMIT, FAILURE_WINDOW)
                                .build())
                        .build());
    }
}
