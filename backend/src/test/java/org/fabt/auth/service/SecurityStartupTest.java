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
        // Phase A4: tests that exercise the prod profile must wire mock
        // new-deps (W-A4-3 fail-fast). Tests on non-prod profiles can
        // pass null because the constructor's prod check is skipped.
        boolean isProdProfile = java.util.Arrays.asList(activeProfiles).contains("prod");
        org.fabt.shared.security.KeyDerivationService keyDerivation = isProdProfile
                ? mock(org.fabt.shared.security.KeyDerivationService.class) : null;
        org.fabt.shared.security.KidRegistryService kidRegistry = isProdProfile
                ? mock(org.fabt.shared.security.KidRegistryService.class) : null;
        org.fabt.shared.security.RevokedKidCache revokedCache = isProdProfile
                ? mock(org.fabt.shared.security.RevokedKidCache.class) : null;
        return new JwtService(secret, 15, 7, objectMapper, env,
                keyDerivation, kidRegistry, revokedCache, null);
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

    @Test
    void prodProfile_withoutKeyDerivationService_constructorThrows() {
        // W-A4-3: prod profile must fail-fast on null Phase A4 deps. A
        // Spring wiring error (bad bean profile, broken auto-wiring) that
        // nullifies any of the 3 new deps would silently downgrade prod
        // JWT signing to legacy v0 tokens forever — this constructor check
        // prevents that.
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});

        assertThatThrownBy(() -> new JwtService(
                "a-valid-secret-that-is-definitely-long-enough-for-hs256-signing",
                15, 7, objectMapper, env,
                null,  // missing KeyDerivationService
                mock(org.fabt.shared.security.KidRegistryService.class),
                mock(org.fabt.shared.security.RevokedKidCache.class),
                null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Phase A4 dependencies");
    }

    @Test
    void prodProfile_withoutKidRegistryService_constructorThrows() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});

        assertThatThrownBy(() -> new JwtService(
                "a-valid-secret-that-is-definitely-long-enough-for-hs256-signing",
                15, 7, objectMapper, env,
                mock(org.fabt.shared.security.KeyDerivationService.class),
                null,  // missing KidRegistryService
                mock(org.fabt.shared.security.RevokedKidCache.class),
                null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Phase A4 dependencies");
    }

    @Test
    void prodProfile_withoutRevokedKidCache_constructorThrows() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});

        assertThatThrownBy(() -> new JwtService(
                "a-valid-secret-that-is-definitely-long-enough-for-hs256-signing",
                15, 7, objectMapper, env,
                mock(org.fabt.shared.security.KeyDerivationService.class),
                mock(org.fabt.shared.security.KidRegistryService.class),
                null,  // missing RevokedKidCache
                null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Phase A4 dependencies");
    }

    @Test
    void nonProdProfile_allowsNullDeps() {
        // Non-prod tests + dev environments without the new infrastructure
        // wired must continue to work via the legacy-only path; the
        // W-A4-3 fail-fast check is prod-profile-only.
        assertThatNoException().isThrownBy(() -> new JwtService(
                "a-valid-secret-that-is-definitely-long-enough-for-hs256-signing",
                15, 7, objectMapper,
                envWithProfiles("lite", "test"),
                null, null, null, null));
    }

    private Environment envWithProfiles(String... profiles) {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(profiles);
        return env;
    }
}
