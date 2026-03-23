package org.fabt.shared.security;

import java.util.function.Supplier;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * JWT decoder with Resilience4J circuit breaker protection for JWKS fetches.
 * Only active when FABT_JWKS_URI is configured (i.e., when OAuth2/Keycloak is in use).
 *
 * Portfolio Lessons applied:
 * - Lesson 37: JWKS circuit breaker race condition — auto-transition-to-half-open
 * - Lesson 52: JWKS warmup through actual NimbusJwtDecoder (not standalone RestTemplate)
 * - Lesson 54: Keycloak realm-aware healthcheck (in docker-compose, not here)
 */
@Configuration
@ConditionalOnProperty("FABT_JWKS_URI")
public class JwtDecoderConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtDecoderConfig.class);

    @Value("${FABT_JWKS_URI}")
    private String jwksUri;

    private NimbusJwtDecoder delegate;

    @Bean
    public JwtDecoder jwtDecoder(CircuitBreakerRegistry circuitBreakerRegistry) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("fabt-jwks-endpoint");
        delegate = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();

        return token -> {
            Supplier<Jwt> decodeSupplier = CircuitBreaker.decorateSupplier(
                    circuitBreaker, () -> delegate.decode(token));
            try {
                return decodeSupplier.get();
            } catch (JwtException e) {
                // JWT validation errors (bad signature, expired) are NOT infra failures
                throw e;
            } catch (Exception e) {
                throw new JwtException("JWT validation unavailable — JWKS circuit breaker may be open", e);
            }
        };
    }

    /**
     * Warms the JWKS cache at startup by decoding a minimal JWS through the actual decoder.
     * The decode fails (bad signature), but the JWK set gets cached in Nimbus.
     * Portfolio Lesson 52: RestTemplate GET to JWKS does NOT populate the decoder's cache.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmJwksCache() {
        if (delegate == null || jwksUri == null || jwksUri.isBlank()) {
            log.info("JWKS warmup skipped — no JWKS URI configured");
            return;
        }

        // Minimal JWS compact serialization — triggers JWKS fetch
        String warmupJwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ3YXJtdXAifQ.";

        try {
            // Use the retry registry if available, otherwise just try once
            try {
                delegate.decode(warmupJwt);
            } catch (JwtException e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                Throwable cause = e.getCause();
                boolean isFetchError = msg.contains("retrieve") || msg.contains("connect")
                        || msg.contains("Connection") || msg.contains("I/O")
                        || (cause != null && (cause instanceof java.io.IOException
                        || cause instanceof org.springframework.web.client.ResourceAccessException));
                if (isFetchError) {
                    log.warn("JWKS warmup failed (fetch error), will retry on first real request: {}", msg);
                } else {
                    // Signature validation error = success — JWKS is now cached
                    log.info("JWKS cache warmed successfully from {}", jwksUri);
                }
            }
        } catch (Exception e) {
            log.warn("JWKS warmup failed: {}. JWT validation will work once Keycloak is available.", e.getMessage());
        }
    }
}
