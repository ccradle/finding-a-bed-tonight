package org.fabt.shared.security;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.fabt.auth.domain.User;
import org.fabt.auth.repository.UserRepository;
import org.fabt.auth.service.JwtService;
import org.fabt.shared.web.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Extracts JWT from the ?token= query parameter for SSE endpoints.
 * EventSource API does not support custom headers, so the token is passed
 * as a query parameter. This filter only applies to the SSE stream path.
 *
 * Security trade-off: Token in URL is logged by proxies and browser history.
 * Mitigations: short-lived access tokens, read-only endpoint, HTTPS.
 * This is the standard approach used by GitHub, Slack, and other SSE implementations.
 */
@Component
public class SseTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SseTokenFilter.class);
    private static final String SSE_PATH = "/api/v1/notifications/stream";
    private static final String TOKEN_PARAM = "token";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public SseTokenFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !SSE_PATH.equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Skip if already authenticated (e.g., via Authorization header in JwtAuthenticationFilter)
        if (SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = request.getParameter(TOKEN_PARAM);
        if (token == null || token.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        log.warn("SSE auth via query param is deprecated — use Authorization header instead");

        UUID tenantId = null;
        boolean dvAccess = false;

        try {
            JwtService.JwtClaims claims = jwtService.validateToken(token);

            List<SimpleGrantedAuthority> authorities = List.of();
            if (claims.roles() != null) {
                authorities = Arrays.stream(claims.roles())
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toList();
            }

            // Check if token was issued before password change (same as JwtAuthenticationFilter)
            if (claims.issuedAt() != null) {
                User user = userRepository.findById(claims.userId()).orElse(null);
                if (user != null && user.getPasswordChangedAt() != null
                        && claims.issuedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                                .isBefore(user.getPasswordChangedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))) {
                    log.debug("SSE token rejected: issued before password change for user {}", claims.userId());
                    SecurityContextHolder.clearContext();
                    filterChain.doFilter(request, response);
                    return;
                }
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(claims.userId(), null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);

            tenantId = claims.tenantId();
            dvAccess = claims.dvAccess();

        } catch (Exception e) {
            log.debug("SSE token authentication failed: {}", e.getMessage());
            SecurityContextHolder.clearContext();
        }

        if (tenantId != null) {
            try {
                TenantContext.callWithContext(tenantId, dvAccess, () -> {
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
}
