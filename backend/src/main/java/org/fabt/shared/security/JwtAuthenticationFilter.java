package org.fabt.shared.security;

import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.fabt.auth.domain.User;
import org.fabt.auth.platform.PlatformJwtException;
import org.fabt.auth.platform.PlatformJwtService;
import org.fabt.auth.repository.UserRepository;
import org.fabt.auth.service.JwtService;
import org.fabt.shared.web.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    /**
     * iss-routed dispatch (Phase G-4 / G-4.2 task 3.9). Optional so unit
     * tests that don't wire the full Spring context still work — production
     * always has it. Per design Decision 3, platform tokens go through
     * {@link PlatformJwtService} (separate code path, not a loosened
     * conditional in {@link JwtService}).
     */
    private final PlatformJwtService platformJwtService;
    private final ObjectMapper jsonForIssPeek;

    @Autowired
    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository,
                                    PlatformJwtService platformJwtService,
                                    ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.platformJwtService = platformJwtService;
        this.jsonForIssPeek = objectMapper;
    }

    /** Test-only — no platform routing, behaves as pre-G-4.2. */
    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this(jwtService, userRepository, null, null);
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

        // ---- iss-routed dispatch (G-4.2 task 3.9) ----
        // Peek at the iss claim BEFORE signature verify (per spec line 99-102:
        // "validate iss BEFORE signature verification — avoid wasted compute
        // on wrong-key signature attempts"). The peek is a routing decision,
        // not a trust boundary; the actual security is the per-issuer
        // validate-with-correct-key path that follows.
        if (platformJwtService != null
                && PlatformJwtService.ISSUER.equals(peekIssuer(token))) {
            handlePlatformToken(token, request, response, filterChain);
            return;
        }

        UUID userId = null;
        UUID tenantId = null;
        boolean dvAccess = false;

        try {
            JwtService.JwtClaims claims = jwtService.validateToken(token);

            // Skip mfa tokens — they are NOT access tokens (D9: filter chain separation)
            if ("mfa".equals(claims.type())) {
                filterChain.doFilter(request, response);
                return;
            }

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

            userId = claims.userId();
            tenantId = claims.tenantId();
            dvAccess = claims.dvAccess();

        } catch (Exception e) {
            log.debug("JWT authentication failed: {}", e.getMessage());
            SecurityContextHolder.clearContext();
        }

        if (tenantId != null) {
            try {
                TenantContext.callWithContext(tenantId, userId, dvAccess, () -> {
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
     * Validates an {@code iss=fabt-platform} JWT and binds a SecurityContext
     * for full access tokens (post-MFA, {@code roles=[PLATFORM_OPERATOR]}).
     *
     * <p>Scoped tokens ({@code mfa-setup}, {@code mfa-verify}) carry no
     * roles and are NOT bound to SecurityContext here — those are consumed
     * directly by {@code PlatformAuthController} which reads the
     * {@code Authorization} header in-line. Letting Spring Security ignore
     * them is the correct behavior: the controller is the only legitimate
     * consumer.
     *
     * <p>Platform tokens carry NO {@code tenantId}; we therefore do NOT
     * enter a {@link TenantContext} scope — platform actions run outside
     * tenant scope by design (Decision 3 + 13). G-4.3's
     * {@code PlatformAdminLogger} aspect chooses the audit row's
     * {@code tenant_id} from method parameters, not from
     * {@link TenantContext}.
     */
    private void handlePlatformToken(String token,
                                      HttpServletRequest request,
                                      HttpServletResponse response,
                                      FilterChain filterChain) throws ServletException, IOException {
        try {
            PlatformJwtService.PlatformJwtClaims claims = platformJwtService.validateToken(token);
            String[] roles = claims.roles();
            if (roles != null && roles.length > 0 && claims.mfaVerified()) {
                // Build the authority list with TWO classes of authority:
                // 1. ROLE_<role> per claim role (drives @PreAuthorize hasRole)
                // 2. MFA_VERIFIED — marker authority added ONLY when the JWT
                //    carries mfaVerified=true. Per design follow-up F2 / G-4.4
                //    task 5.4b, this provides defense-in-depth: the
                //    @PlatformAdminOnly aspect asserts the marker's presence
                //    before writing audit rows. If a future change to this
                //    filter accidentally grants ROLE_PLATFORM_OPERATOR without
                //    requiring mfaVerified, the aspect still rejects.
                List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
                Arrays.stream(roles)
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .forEach(authorities::add);
                authorities.add(new SimpleGrantedAuthority("MFA_VERIFIED"));
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(claims.sub(), null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
            // Else: scoped token (mfa-setup / mfa-verify) — controller validates
            // in-line. Don't bind SecurityContext.
        } catch (PlatformJwtException e) {
            log.debug("Platform JWT rejected: {}", e.getMessage());
            SecurityContextHolder.clearContext();
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Returns the {@code iss} claim from a JWT payload, or {@code null} if
     * the token is malformed / payload not parseable. NEVER trusts the
     * value for security decisions — the caller uses it only to route to
     * the correct validator. The validator then verifies the signature with
     * the issuer-appropriate key.
     */
    private String peekIssuer(String token) {
        if (jsonForIssPeek == null) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            Map<String, Object> payload = jsonForIssPeek.readValue(
                    payloadBytes, new TypeReference<>() {});
            Object iss = payload.get("iss");
            return iss instanceof String s ? s : null;
        } catch (Exception e) {
            return null;
        }
    }
}
