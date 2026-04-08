package org.fabt.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

/**
 * Enables Spring Framework 7 native @Retryable and @ConcurrencyLimit annotations.
 * No external dependency needed — built into spring-core/spring-context.
 * resilience4j remains for circuit breakers only (separate concern).
 */
@Configuration
@EnableResilientMethods
public class ResilienceConfig {
}
