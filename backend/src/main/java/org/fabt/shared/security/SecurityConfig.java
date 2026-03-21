package org.fabt.shared.security;

import java.util.Arrays;
import java.util.List;

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
    private final TenantContextCleanupFilter tenantContextCleanupFilter;

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
                          TenantContextCleanupFilter tenantContextCleanupFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.apiKeyAuthenticationFilter = apiKeyAuthenticationFilter;
        this.tenantContextCleanupFilter = tenantContextCleanupFilter;
    }

    /**
     * LESSON 58: Static resources + oauth2ResourceServer requires WebSecurityCustomizer
     * with web.ignoring() for static paths. permitAll() DOES NOT WORK for static
     * resources when BearerTokenAuthenticationFilter is in the chain.
     */
    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return (web) -> web.ignoring()
                .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico", "/favicon.svg");
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints (no auth required)
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/api/v1/api-docs/**").permitAll()
                        .requestMatchers("/api/v1/docs/**").permitAll()
                        .requestMatchers("/swagger-ui/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/tenants/*/oauth2-providers/public").permitAll()

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

                        // Bed search queries — any authenticated role
                        .requestMatchers("/api/v1/queries/**").authenticated()

                        // Shelter operations — any authenticated role (fine-grained control via @PreAuthorize)
                        .requestMatchers(HttpMethod.GET, "/api/v1/shelters/**").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/shelters/*/availability").hasAnyRole("COORDINATOR", "COC_ADMIN", "PLATFORM_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/shelters/**").hasAnyRole("COC_ADMIN", "PLATFORM_ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/shelters/**").hasAnyRole("COORDINATOR", "COC_ADMIN", "PLATFORM_ADMIN")

                        // Import — COC_ADMIN or PLATFORM_ADMIN
                        .requestMatchers("/api/v1/import/**").hasAnyRole("COC_ADMIN", "PLATFORM_ADMIN")

                        // Subscriptions — any authenticated role
                        .requestMatchers("/api/v1/subscriptions/**").authenticated()

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
                .addFilterBefore(tenantContextCleanupFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(apiKeyAuthenticationFilter, JwtAuthenticationFilter.class);

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
