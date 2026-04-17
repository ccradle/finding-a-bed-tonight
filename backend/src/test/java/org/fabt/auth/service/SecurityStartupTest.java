package org.fabt.auth.service;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityStartupTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JwtService createService(String secret, String... activeProfiles) {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(activeProfiles);
        // Phase A4: new deps null — this test only exercises @PostConstruct
        // validation (validateJwtSecret), not validateToken. Null new-path
        // deps never get called from this test surface.
        return new JwtService(secret, 15, 7, objectMapper, env, null, null, null);
    }

    @Test
    void emptySecret_preventsStartup() {
        JwtService service = createService("", "lite", "test");
        assertThatThrownBy(service::validateJwtSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FABT_JWT_SECRET must be set")
                .hasMessageContaining("openssl rand -base64 64");
    }

    @Test
    void blankSecret_preventsStartup() {
        JwtService service = createService("   ", "lite", "test");
        assertThatThrownBy(service::validateJwtSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FABT_JWT_SECRET must be set");
    }

    @Test
    void shortSecret_preventsStartup() {
        JwtService service = createService("tooshort", "lite", "test");
        assertThatThrownBy(service::validateJwtSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Minimum 32 characters");
    }

    @Test
    void devDefaultSecret_preventsStartupInProdProfile() {
        JwtService service = createService(
                "default-dev-secret-change-in-production", "prod");
        assertThatThrownBy(service::validateJwtSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not use the default dev secret");
    }

    @Test
    void devDefaultSecret_allowedInDevProfile() {
        JwtService service = createService(
                "default-dev-secret-change-in-production", "lite", "dev");
        assertThatNoException().isThrownBy(service::validateJwtSecret);
    }

    @Test
    void validSecret_startsSuccessfully() {
        JwtService service = createService(
                "a-valid-secret-that-is-definitely-long-enough-for-hs256-signing", "prod");
        assertThatNoException().isThrownBy(service::validateJwtSecret);
    }
}
