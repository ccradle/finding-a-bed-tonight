package org.fabt.observability;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Separate security filter chain for the management port.
 * Only active when management.server.port is set (i.e., dev --observability mode).
 *
 * When management endpoints run on a separate port (e.g., 9091), that port is
 * network-isolated — only Docker containers on the same network can reach it.
 * This allows Prometheus to scrape /actuator/prometheus without JWT auth.
 *
 * The main application port (8080) remains fully secured with anyRequest().authenticated().
 */
@Configuration
@ConditionalOnProperty("management.server.port")
public class ManagementSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain managementSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/actuator/**")
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll())
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
