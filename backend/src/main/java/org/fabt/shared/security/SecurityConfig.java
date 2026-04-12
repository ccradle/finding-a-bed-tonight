package org.fabt.shared.security;

import java.util.Arrays;
import java.util.List;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Central security configuration. Two layers of defense:
 * 1. URL-level: SecurityFilterChain requestMatchers (coarse-grained)
 * 2. Method-level: @PreAuthorize on service/controller methods (fine-grained)
 *
 * OWASP A01: Default-deny — anyRequest().authenticated() as final rule.
 * Role hierarchy: PLATFORM_ADMIN > COC_ADMIN > COORDINATOR / OUTREACH_WORKER
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;
    private final SseTokenFilter sseTokenFilter;
    private final PasswordChangeRequiredFilter passwordChangeRequiredFilter;

    /**
     * Comma-separated list of allowed CORS origins.
     * Dev default: http://localhost:5173 (Vite dev server).
     * Production: set FABT_CORS_ORIGINS to the frontend's domain(s).
     * When frontend is served via nginx proxy (same origin), CORS is not needed.
     */
    @Value("${fabt.cors.allowed-origins:http://localhost:5173}")
    private String allowedOriginsConfig;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
                          SseTokenFilter sseTokenFilter,
                          PasswordChangeRequiredFilter passwordChangeRequiredFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.apiKeyAuthenticationFilter = apiKeyAuthenticationFilter;
        this.sseTokenFilter = sseTokenFilter;
        this.passwordChangeRequiredFilter = passwordChangeRequiredFilter;
    }

    /**
     * LESSON 58: Static resources + oauth2ResourceServer requires WebSecurityCustomizer
     * with web.ignoring() for static paths. permitAll() DOES NOT WORK for static
     * resources when BearerTokenAuthenticationFilter is in the chain.
     */
    /**
     * LESSON 58: Static resources + oauth2ResourceServer requires web.ignoring()
     * for static paths. permitAll() DOES NOT WORK for static resources when
     * BearerTokenAuthenticationFilter (or JwtAuthenticationFilter) is in the chain.
     * Swagger UI paths included per reviewer feedback (401 on /api/v1/swagger-ui/).
     */
    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return (web) -> web.ignoring()
                .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico", "/favicon.svg")
                .requestMatchers("/api/v1/swagger-ui/**", "/swagger-ui/**");
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers
                        // X-Content-Type-Options and X-Frame-Options are Spring Security defaults.
                        // Add Referrer-Policy and Permissions-Policy for full scan coverage.
                        // Also set in nginx.conf (defense-in-depth).
                        .referrerPolicy(referrer -> referrer
                                .policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicy(permissions -> permissions
                                .policy("geolocation=(), microphone=(), camera=()")))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // =====================================================================
                        // FILTER-VS-CONTROLLER AUTHORIZATION INVARIANT (v0.34.0 war-room lesson)
                        // =====================================================================
                        // Authorization rules in this file must NEVER be more restrictive than
                        // the @PreAuthorize annotation on the target controller method. The
                        // filter chain is the COARSE first pass; the controller's @PreAuthorize
                        // (and any in-body isAssigned/isOwner checks) is the FINE second pass.
                        // The filter must admit every role the controller would admit, so the
                        // controller's assertion has the chance to run.
                        //
                        // Why this invariant matters: both SecurityConfig and
                        // GlobalExceptionHandler return an identical JSON shape for 403 denials
                        // (`{"error":"access_denied","message":"Insufficient permissions",...}`),
                        // so an overly-restrictive filter rule silently hides a "works as
                        // designed" response from the controller. Tests cannot distinguish the
                        // two paths by status or body alone.
                        //
                        // Historical incident: v0.34.0 (bed-hold-integrity, Issue #102 RCA) had
                        // a POST /api/v1/shelters/** catch-all that omitted COORDINATOR. The
                        // new /manual-hold endpoint's @PreAuthorize admits COORDINATOR. Result:
                        // every coordinator got 403'd at the filter before reaching the
                        // controller's isAssigned check. Caught in pre-ship smoke. Fixed by
                        // inserting an explicit `POST /shelters/*/manual-hold` matcher BEFORE
                        // the catch-all (Spring matchers are first-match-wins). The
                        // `OfflineHoldEndpointTest.coordinator_not_assigned_to_shelter_403`
                        // test now asserts via the `fabt.http.access_denied.count` Micrometer
                        // counter — that counter only increments when GlobalExceptionHandler
                        // handles the rejection, NOT when this filter chain handles it. If
                        // that assertion ever starts failing, the filter has regressed past the
                        // controller contract again.
                        //
                        // Before narrowing any rule here, verify the target endpoint's
                        // @PreAuthorize annotation and the counter-based test in the relevant
                        // integration test suite. Marcus Webb sign-off required for any
                        // SecurityConfig narrowing touching a path that has a controller-level
                        // assignment / ownership check downstream.
                        // =====================================================================

                        // SSE async dispatch: when emitters error, Tomcat dispatches async.
                        // Without this, Spring Security re-challenges with 401 on the committed
                        // SSE response → "response already committed" errors (spring-security#16266).
                        // Safe site-wide: only SSE uses async dispatch; initial connection is authenticated.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        // Public endpoints (no auth required)
                        // Security audit (REQ-AUTH-PERMIT-1): each path reviewed for info disclosure.
                        // Swagger paths disabled in prod profile (application-prod.yml).
                        // /actuator/health: show-details=when-authorized, unauthenticated sees only status.
                        // /error: server.error.include-stacktrace=never, no implementation details.
                        // Password change requires authentication (must be before auth/** permitAll)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/auth/password").authenticated()
                        // TOTP enrollment/management requires authentication
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/enroll-totp").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/confirm-totp-enrollment").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/regenerate-recovery-codes").authenticated()
                        // TOTP admin operations require COC_ADMIN+
                        // Public auth endpoints covered by auth/** wildcard below:
                        // POST /auth/login, /auth/refresh, /auth/access-code, /auth/verify-totp,
                        // POST /auth/forgot-password, /auth/reset-password, GET /auth/capabilities
                        // TOTP admin operations require COC_ADMIN+
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/auth/totp/*").hasAnyRole("COC_ADMIN", "PLATFORM_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/totp/*/regenerate-recovery-codes").hasAnyRole("COC_ADMIN", "PLATFORM_ADMIN")
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/api/v1/api-docs/**").permitAll()
                        .requestMatchers("/api/v1/docs/**").permitAll()
                        .requestMatchers("/swagger-ui/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/tenants/*/oauth2-providers/public").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/version").permitAll()

                        // OAuth2 provider management — COC_ADMIN or PLATFORM_ADMIN
                        // Must be BEFORE the general /api/v1/tenants/** matcher
                        .requestMatchers("/api/v1/tenants/*/oauth2-providers/**").hasAnyRole("COC_ADMIN", "PLATFORM_ADMIN")

                        // Tenant management — PLATFORM_ADMIN only
                        .requestMatchers("/api/v1/tenants/**").hasRole("PLATFORM_ADMIN")

                        // User management — COC_ADMIN or PLATFORM_ADMIN
                        .requestMatchers("/api/v1/users/**").hasAnyRole("COC_ADMIN", "PLATFORM_ADMIN")

                        // API key management — COC_ADMIN or PLATFORM_ADMIN
                        .requestMatchers("/api/v1/api-keys/**").hasAnyRole("COC_ADMIN", "PLATFORM_ADMIN")

                        // Surge events — GET any authenticated, POST/PATCH COC_ADMIN+
                        .requestMatchers(HttpMethod.GET, "/api/v1/surge-events/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/surge-events/**").hasAnyRole("COC_ADMIN", "PLATFORM_ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/surge-events/**").hasAnyRole("COC_ADMIN", "PLATFORM_ADMIN")

                        // Reservations — outreach workers, coordinators, and admins
                        .requestMatchers("/api/v1/reservations/**").hasAnyRole("OUTREACH_WORKER", "COORDINATOR", "COC_ADMIN", "PLATFORM_ADMIN")

                        // DV opaque referrals — requires dvAccess (enforced in service), role-based per endpoint
                        .requestMatchers("/api/v1/dv-referrals/**").authenticated()

                        // HMIS bridge — admin endpoints, fine-grained via @PreAuthorize
                        .requestMatchers("/api/v1/hmis/**").authenticated()

                        // Analytics — COC_ADMIN or PLATFORM_ADMIN (fine-grained via @PreAuthorize)
                        .requestMatchers("/api/v1/analytics/**").hasAnyRole("COC_ADMIN", "PLATFORM_ADMIN")

                        // Batch job management — view: COC_ADMIN+, mutate: PLATFORM_ADMIN (via @PreAuthorize)
                        .requestMatchers(HttpMethod.GET, "/api/v1/batch/**").hasAnyRole("COC_ADMIN", "PLATFORM_ADMIN")
                        .requestMatchers("/api/v1/batch/**").hasRole("PLATFORM_ADMIN")

                        // Test reset — profile-gated (dev/test only), requires PLATFORM_ADMIN + confirmation header
                        .requestMatchers("/api/v1/test/**").hasRole("PLATFORM_ADMIN")

                        // Bed search queries — any authenticated role
                        .requestMatchers("/api/v1/queries/**").authenticated()

                        // Shelter operations — any authenticated role (fine-grained control via @PreAuthorize)
                        .requestMatchers(HttpMethod.GET, "/api/v1/shelters/**").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/shelters/*/availability").hasAnyRole("COORDINATOR", "COC_ADMIN", "PLATFORM_ADMIN")
                        // Manual offline hold (Issue #102 / bed-hold-integrity): coordinators can create
                        // offline holds at their assigned shelters. Filter chain admits the role; the
                        // fine-grained shelter-assignment check is enforced in ManualHoldController via
                        // CoordinatorAssignmentRepository.isAssigned. Two-layer authz contract — filter
                        // is the coarse first pass, controller is the fine second pass. The filter must
                        // never be more restrictive than the controller body. Must precede the broader
                        // POST /shelters/** rule below since matchers are first-match-wins.
                        .requestMatchers(HttpMethod.POST, "/api/v1/shelters/*/manual-hold").hasAnyRole("COORDINATOR", "COC_ADMIN", "PLATFORM_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/shelters/**").hasAnyRole("COC_ADMIN", "PLATFORM_ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/shelters/**").hasAnyRole("COORDINATOR", "COC_ADMIN", "PLATFORM_ADMIN")

                        // Import — COC_ADMIN or PLATFORM_ADMIN
                        .requestMatchers("/api/v1/import/**").hasAnyRole("COC_ADMIN", "PLATFORM_ADMIN")

                        // Audit events — COC_ADMIN or PLATFORM_ADMIN
                        .requestMatchers("/api/v1/audit-events/**").hasAnyRole("COC_ADMIN", "PLATFORM_ADMIN")

                        // SSE notifications — any authenticated role (token via query param)
                        .requestMatchers("/api/v1/notifications/stream").authenticated()

                        // Subscriptions — any authenticated role
                        .requestMatchers("/api/v1/subscriptions/**").authenticated()

                        // Error page must be accessible so @ResponseStatus exceptions
                        // render the correct status code (not 401). See Spring Boot #33341.
                        .requestMatchers("/error").permitAll()

                        // OWASP A01: Default deny — everything else requires authentication
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(org.springframework.http.HttpStatus.FORBIDDEN.value());
                            response.setContentType("application/json");
                            response.getWriter().write(
                                    "{\"error\":\"access_denied\",\"message\":\"Insufficient permissions\",\"status\":403}");
                        }))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(sseTokenFilter, JwtAuthenticationFilter.class)
                .addFilterAfter(passwordChangeRequiredFilter, SseTokenFilter.class)
                .addFilterAfter(apiKeyAuthenticationFilter, PasswordChangeRequiredFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOriginsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-API-Key", "Accept-Language"));
        config.setExposedHeaders(List.of("X-Signature"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/actuator/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
