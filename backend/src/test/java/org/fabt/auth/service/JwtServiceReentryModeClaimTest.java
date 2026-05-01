package org.fabt.auth.service;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.fabt.auth.domain.User;
import org.fabt.tenant.service.TenantService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Round 5 §16.A.5 — JwtService reentryMode JWT-claim emission matrix.
 *
 * <p>Covers every branch of {@link JwtService#loadReentryMode(UUID)} +
 * the two payload-build sites that call it ({@code generateAccessToken}
 * + {@code generateAccessTokenWithPasswordChange}).
 *
 * <p>Why each case matters:
 * <ul>
 *   <li>Flag=true → reentry surface visible (the only case where PII flows
 *       through serialization)</li>
 *   <li>Flag=false / missing features map / missing reentryMode key → claim
 *       must be {@code false}; the §16.B serialization gate hides PII</li>
 *   <li>tenantService throws → fail-safe to {@code false}; protects tenants
 *       whose config read transiently fails</li>
 *   <li>Null tenantService → legacy unit-test wiring path; default
 *       {@code false}</li>
 *   <li>Cache hit → second call skips the (potentially expensive)
 *       {@code TenantService.getConfig} round trip</li>
 * </ul>
 */
@DisplayName("JwtService reentryMode JWT claim (Round 5 §16.A.5)")
class JwtServiceReentryModeClaimTest {

    private static final String SECRET =
            "sufficiently-long-jwt-secret-for-tests-only-not-for-production-use-XXXXX";

    private final ObjectMapper mapper = new ObjectMapper();

    private JwtService buildService(TenantService tenantService) {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"lite", "test"});
        return new JwtService(SECRET, 15, 7, mapper, env,
                null, null, null, null, tenantService);
    }

    private User userFor(UUID tenantId) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setTenantId(tenantId);
        u.setDisplayName("Reentry Claim Test User");
        u.setRoles(new String[]{"COC_ADMIN"});
        u.setDvAccess(false);
        u.setTokenVersion(1);
        return u;
    }

    /** Decodes the JWT payload (middle segment) into a Map for direct claim inspection. */
    private Map<String, Object> decodePayload(String token) throws Exception {
        String[] parts = token.split("\\.");
        byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
        return mapper.readValue(payloadBytes, new TypeReference<>() {});
    }

    private TenantService stubConfigWithReentryMode(boolean value) {
        TenantService svc = mock(TenantService.class);
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("reentryMode", value);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("features", features);
        when(svc.getConfig(any(UUID.class))).thenReturn(config);
        return svc;
    }

    // ------------------------------------------------------------------
    // Case 1 — flag=true → claim emitted as true
    // ------------------------------------------------------------------

    @Test
    @DisplayName("features.reentryMode=true → JWT payload.reentryMode == true")
    void reentryModeTrue_emitsTrueClaim() throws Exception {
        UUID tenantId = UUID.randomUUID();
        JwtService svc = buildService(stubConfigWithReentryMode(true));

        String token = svc.generateAccessToken(userFor(tenantId));
        Map<String, Object> payload = decodePayload(token);

        assertThat(payload.get("reentryMode")).isEqualTo(true);
    }

    // ------------------------------------------------------------------
    // Case 2 — flag=false → claim emitted as false
    // ------------------------------------------------------------------

    @Test
    @DisplayName("features.reentryMode=false → JWT payload.reentryMode == false")
    void reentryModeFalse_emitsFalseClaim() throws Exception {
        UUID tenantId = UUID.randomUUID();
        JwtService svc = buildService(stubConfigWithReentryMode(false));

        String token = svc.generateAccessToken(userFor(tenantId));
        Map<String, Object> payload = decodePayload(token);

        assertThat(payload.get("reentryMode")).isEqualTo(false);
    }

    // ------------------------------------------------------------------
    // Case 3 — features map exists but no reentryMode key → claim=false
    // ------------------------------------------------------------------

    @Test
    @DisplayName("features map without reentryMode key → payload.reentryMode == false")
    void reentryModeMissingKey_emitsFalseClaim() throws Exception {
        UUID tenantId = UUID.randomUUID();
        TenantService stub = mock(TenantService.class);
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("dvAccess", true); // unrelated feature key present
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("features", features);
        when(stub.getConfig(any(UUID.class))).thenReturn(config);
        JwtService svc = buildService(stub);

        String token = svc.generateAccessToken(userFor(tenantId));
        Map<String, Object> payload = decodePayload(token);

        assertThat(payload.get("reentryMode")).isEqualTo(false);
    }

    // ------------------------------------------------------------------
    // Case 4 — config has no features map at all → claim=false
    // ------------------------------------------------------------------

    @Test
    @DisplayName("config without features map → payload.reentryMode == false")
    void reentryModeMissingFeaturesMap_emitsFalseClaim() throws Exception {
        UUID tenantId = UUID.randomUUID();
        TenantService stub = mock(TenantService.class);
        when(stub.getConfig(any(UUID.class))).thenReturn(new LinkedHashMap<>());
        JwtService svc = buildService(stub);

        String token = svc.generateAccessToken(userFor(tenantId));
        Map<String, Object> payload = decodePayload(token);

        assertThat(payload.get("reentryMode")).isEqualTo(false);
    }

    // ------------------------------------------------------------------
    // Case 5 — tenantService throws → fail-safe false
    // ------------------------------------------------------------------

    @Test
    @DisplayName("tenantService.getConfig throws → payload.reentryMode == false (fail-safe)")
    void tenantServiceThrows_emitsFalseClaim() throws Exception {
        UUID tenantId = UUID.randomUUID();
        TenantService stub = mock(TenantService.class);
        when(stub.getConfig(any(UUID.class)))
                .thenThrow(new RuntimeException("simulated DB outage"));
        JwtService svc = buildService(stub);

        String token = svc.generateAccessToken(userFor(tenantId));
        Map<String, Object> payload = decodePayload(token);

        assertThat(payload.get("reentryMode")).isEqualTo(false);
    }

    // ------------------------------------------------------------------
    // Case 6 — tenantService null (legacy wiring) → claim=false
    // ------------------------------------------------------------------

    @Test
    @DisplayName("tenantService null (legacy unit-test wiring) → payload.reentryMode == false")
    void nullTenantService_emitsFalseClaim() throws Exception {
        UUID tenantId = UUID.randomUUID();
        JwtService svc = buildService(null);

        String token = svc.generateAccessToken(userFor(tenantId));
        Map<String, Object> payload = decodePayload(token);

        assertThat(payload.get("reentryMode")).isEqualTo(false);
    }

    // ------------------------------------------------------------------
    // Case 7 — generateAccessTokenWithPasswordChange emits same claim
    // ------------------------------------------------------------------

    @Test
    @DisplayName("generateAccessTokenWithPasswordChange honors features.reentryMode=true")
    void passwordChangeVariant_emitsSameClaim() throws Exception {
        UUID tenantId = UUID.randomUUID();
        JwtService svc = buildService(stubConfigWithReentryMode(true));

        String token = svc.generateAccessTokenWithPasswordChange(userFor(tenantId));
        Map<String, Object> payload = decodePayload(token);

        assertThat(payload.get("reentryMode")).isEqualTo(true);
        assertThat(payload.get("mustChangePassword")).isEqualTo(true);
    }

    // ------------------------------------------------------------------
    // Case 8 — cache hit on second call (no second TenantService round-trip)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("repeated calls for same tenant hit cache (single TenantService.getConfig invocation)")
    void cachedSecondCall_doesNotReinvokeTenantService() throws Exception {
        UUID tenantId = UUID.randomUUID();
        TenantService stub = stubConfigWithReentryMode(true);
        JwtService svc = buildService(stub);
        User user = userFor(tenantId);

        // Two issuances for the same tenant.
        svc.generateAccessToken(user);
        svc.generateAccessToken(user);

        // Helper short-circuits to cache after the first miss; a second
        // getConfig call would mean the cache is broken (or the TTL is
        // catastrophically short).
        verify(stub, times(1)).getConfig(tenantId);
    }
}
