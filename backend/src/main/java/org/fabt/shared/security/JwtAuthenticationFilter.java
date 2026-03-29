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

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader(AUTHORIZATION_HEADER);

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());
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

            // Check if token was issued before the user's last password change.
            // JWT iat is epoch-seconds (no fractional seconds), but password_changed_at
            // has microsecond precision. Truncate both to seconds for a fair comparison —
            // otherwise a token issued in the same second as the password change would be
            // incorrectly rejected because iat=12:00:00.000 < password_changed_at=12:00:00.456.
            User user = userRepository.findById(claims.userId()).orElse(null);

            // Reject tokens for deactivated users
            if (user != null && !user.isActive()) {
                log.debug("JWT rejected: user {} is deactivated", claims.userId());
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }

            // Reject tokens with stale token version (role change, dvAccess change, deactivation)
            if (user != null && claims.tokenVersion() != user.getTokenVersion()) {
                log.debug("JWT rejected: token version {} does not match current {} for user {}",
                        claims.tokenVersion(), user.getTokenVersion(), claims.userId());
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }

            // Reject tokens issued before password change
            if (claims.issuedAt() != null && user != null && user.getPasswordChangedAt() != null
                    && claims.issuedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                            .isBefore(user.getPasswordChangedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))) {
                log.debug("JWT rejected: issued before password change for user {}", claims.userId());
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(claims.userId(), null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);

            tenantId = claims.tenantId();
            dvAccess = claims.dvAccess();

        } catch (Exception e) {
            log.debug("JWT authentication failed: {}", e.getMessage());
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
