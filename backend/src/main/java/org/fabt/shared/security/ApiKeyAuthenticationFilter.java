package org.fabt.shared.security;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

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

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
    private static final String API_KEY_HEADER = "X-API-Key";

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
            }
        } catch (Exception e) {
            log.debug("API key authentication failed for request {}: {}",
                    request.getRequestURI(), e.getMessage());
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
}
