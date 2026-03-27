package org.fabt.shared.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Logs rate-limited requests at WARN level with client IP (REQ-RL-5).
 * bucket4j-spring-boot-starter does not provide built-in logging.
 * This filter runs after the bucket4j servlet filter and checks for 429 responses.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(request, response);

        if (response.getStatus() == 429) {
            log.warn("Rate limit exceeded: ip={}, method={}, path={}",
                    request.getRemoteAddr(), request.getMethod(), request.getRequestURI());
        }
    }
}
