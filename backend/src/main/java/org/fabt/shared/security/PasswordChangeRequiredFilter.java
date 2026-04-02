package org.fabt.shared.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.fabt.auth.service.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces password change after access-code login (D10).
 *
 * After logging in with a one-time access code, the user receives a JWT
 * with mustChangePassword=true. This filter blocks all requests except
 * PUT /api/v1/auth/password with 403 password_change_required.
 *
 * Position in filter chain: AFTER JwtAuthenticationFilter (which sets
 * the SecurityContext), BEFORE authorization checks.
 */
@Component
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeRequiredFilter.class);
    private static final String PASSWORD_CHANGE_PATH = "/api/v1/auth/password";

    private final JwtService jwtService;

    public PasswordChangeRequiredFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        // Only check authenticated requests
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        // Check if this is a password-change-required token
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                JwtService.JwtClaims claims = jwtService.validateToken(token);

                if (claims.mustChangePassword()) {
                    // Allow only the password change endpoint
                    String path = request.getRequestURI();
                    String method = request.getMethod();

                    if ("PUT".equals(method) && PASSWORD_CHANGE_PATH.equals(path)) {
                        filterChain.doFilter(request, response);
                        return;
                    }

                    // Block all other requests
                    response.setStatus(403);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write(
                            "{\"error\":\"password_change_required\",\"message\":\"You must change your password before accessing other features.\",\"status\":403}");
                    return;
                }
            } catch (Exception e) {
                // Token validation failed — let the normal filter chain handle it
            }
        }

        filterChain.doFilter(request, response);
    }
}
