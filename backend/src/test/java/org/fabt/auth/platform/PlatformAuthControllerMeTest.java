package org.fabt.auth.platform;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.Application;
import org.fabt.BaseIntegrationTest;
import org.fabt.auth.platform.PlatformJwtService;
import org.fabt.auth.platform.PlatformUser;
import org.fabt.auth.service.PasswordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.time.SystemTimeProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@code GET /api/v1/auth/platform/me} (F11 task 2.2 +
 * warroom round 3 strengthening).
 *
 * <p>Covers: authenticated retrieval (with strict value assertions on every
 * field, including {@code lastLoginAt} freshness), missing JWT (401),
 * mfa-setup-only token (403), mfa-verify-scoped token (403), garbage
 * Bearer token (401), backup-code consumption decrements
 * {@code backupCodesRemaining}, anonymized operator (410).
 *
 * <p>Uses the same fixture pattern as {@link PlatformAuthIntegrationTest}:
 * bootstrap-row activation in {@code @BeforeEach}.
 */
@DisplayName("PlatformAuthController GET /me")
@SpringBootTest(classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformAuthControllerMeTest extends BaseIntegrationTest {

    private static final UUID BOOTSTRAP_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000fab");
    private static final String OPERATOR_EMAIL = "ops-me-it@example.com";
    private static final String OPERATOR_PASSWORD = "InitialPassword!@#$1234";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordService passwordService;
    @Autowired private PlatformJwtService jwtService;

    @BeforeEach
    void restoreBootstrap() {
        jdbc.queryForObject("SELECT platform_user_reset_to_bootstrap(?::uuid)",
                Boolean.class, BOOTSTRAP_ID);
        jdbc.queryForObject(
                "SELECT platform_user_update_credentials(?::uuid, ?, NULL, NULL, ?)",
                Object.class, BOOTSTRAP_ID,
                passwordService.hash(OPERATOR_PASSWORD), false);
        jdbc.queryForObject("SELECT platform_user_set_email(?::uuid, ?)",
                Object.class, BOOTSTRAP_ID, OPERATOR_EMAIL);
    }

    @AfterEach
    void resetForNextSuite() {
        jdbc.queryForObject("SELECT platform_user_reset_to_bootstrap(?::uuid)",
                Boolean.class, BOOTSTRAP_ID);
    }

    @Test
    @DisplayName("authenticated operator retrieves own metadata with all expected field values")
    void getMeReturnsAllFields() {
        Instant testRunCutoff = Instant.now().minusSeconds(3600);
        EnrolledOperator op = enrollAndGetAccessToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(op.accessToken());
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/auth/platform/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        // Strict value assertions per warroom round 3 (Alex #4 + Test #1)
        assertThat(body.get("id")).isEqualTo(BOOTSTRAP_ID.toString());
        assertThat(body.get("email")).isEqualTo(OPERATOR_EMAIL);
        assertThat(body.get("mfaEnabled")).isEqualTo(Boolean.TRUE);
        assertThat(body.get("backupCodesRemaining")).isEqualTo(10);
        // lastLoginAt + mfaEnrolledAt: present, within the last hour, not in
        // the future. We don't pin to "after this test started" because the
        // bootstrap row's last_login_at survives @BeforeEach resets and may
        // carry a value from an earlier test in the shared Spring context;
        // the contract here is "field is wired up correctly and reflects a
        // real recent timestamp," not "field was updated *during this test*".
        assertThat(body.get("lastLoginAt")).isNotNull();
        Instant lastLogin = Instant.parse(body.get("lastLoginAt").toString());
        assertThat(lastLogin).isAfter(testRunCutoff);
        assertThat(lastLogin).isBefore(Instant.now().plusSeconds(2));
        assertThat(body.get("mfaEnrolledAt")).isNotNull();
        Instant mfaEnrolled = Instant.parse(body.get("mfaEnrolledAt").toString());
        assertThat(mfaEnrolled).isAfter(testRunCutoff);
        assertThat(mfaEnrolled).isBefore(Instant.now().plusSeconds(2));
        // Defense: secrets MUST NOT leak into the response
        assertThat(body).doesNotContainKeys("passwordHash", "mfaSecret", "backupCodes");
    }

    @Test
    @DisplayName("missing Authorization header → 401 invalid_platform_token")
    void getMeWithoutTokenReturns401() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/auth/platform/me",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("error")).isEqualTo("invalid_platform_token");
    }

    @Test
    @DisplayName("MFA-setup-only token presented to /me → 403")
    void getMeWithMfaSetupTokenReturns403() {
        // First login (not yet enrolled) returns an mfa-setup-scoped token
        ResponseEntity<Map> login = restTemplate.postForEntity(
                "/api/v1/auth/platform/login",
                Map.of("email", OPERATOR_EMAIL, "password", OPERATOR_PASSWORD),
                Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String setupToken = (String) login.getBody().get("token");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(setupToken);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/auth/platform/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("MFA-verify scoped token presented to /me → 403 (warroom H2)")
    void getMeWithMfaVerifyTokenReturns403() {
        // Enroll once so subsequent login returns an mfa-verify scoped token
        // (NOT an mfa-setup token). This is the more common scoped token in
        // active circulation — every operator login after enrollment.
        enrollAndGetAccessToken();

        ResponseEntity<Map> login = restTemplate.postForEntity(
                "/api/v1/auth/platform/login",
                Map.of("email", OPERATOR_EMAIL, "password", OPERATOR_PASSWORD),
                Map.class);
        assertThat(login.getBody().get("scope"))
                .isEqualTo(PlatformJwtService.SCOPE_MFA_VERIFY);
        String verifyToken = (String) login.getBody().get("token");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(verifyToken);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/auth/platform/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("garbage Bearer token → 401 invalid_platform_token")
    void getMeWithGarbageTokenReturns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("not.a.real.jwt");
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/auth/platform/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("error")).isEqualTo("invalid_platform_token");
    }

    @Test
    @DisplayName("backup-code consumption decrements backupCodesRemaining (warroom H3)")
    void backupCodeConsumptionDecrementsCount() {
        // Enroll once to get backup codes
        EnrolledOperator op = enrollAndGetAccessToken();
        assertThat(op.backupCodes()).hasSize(10);

        // Use a backup code via /login → /login/mfa-verify
        ResponseEntity<Map> login = restTemplate.postForEntity(
                "/api/v1/auth/platform/login",
                Map.of("email", OPERATOR_EMAIL, "password", OPERATOR_PASSWORD),
                Map.class);
        String verifyToken = (String) login.getBody().get("token");

        HttpHeaders verifyHeaders = new HttpHeaders();
        verifyHeaders.setBearerAuth(verifyToken);
        verifyHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> verify = restTemplate.exchange(
                "/api/v1/auth/platform/login/mfa-verify",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("code", op.backupCodes().get(0)), verifyHeaders),
                Map.class);
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);
        String accessToken = (String) verify.getBody().get("token");

        // /me should now report 9 codes remaining
        HttpHeaders meHeaders = new HttpHeaders();
        meHeaders.setBearerAuth(accessToken);
        ResponseEntity<Map> me = restTemplate.exchange(
                "/api/v1/auth/platform/me",
                HttpMethod.GET,
                new HttpEntity<>(meHeaders),
                Map.class);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().get("backupCodesRemaining")).isEqualTo(9);
    }

    @Test
    @DisplayName("operator's row missing entirely (synthetic UUID) → 410 Gone (warroom H1, missing branch)")
    void missingOperatorRowReturns410() {
        // Mint a structurally-valid platform access token for a UUID that
        // does NOT exist in platform_user. Exercises the `id != p_id`
        // branch of platform_user_get_me's WHERE clause.
        PlatformUser ghost = new PlatformUser();
        ghost.setId(UUID.randomUUID());
        ghost.setEmail("ghost@example.com");
        String ghostToken = jwtService.generateAccessToken(ghost);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ghostToken);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/auth/platform/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    @DisplayName("operator's row anonymized after token issued → 410 Gone (warroom H5, anonymized branch)")
    void anonymizedOperatorReturns410() {
        // Distinct from the synthetic-UUID test above: this exercises the
        // `anonymized_at IS NULL` branch of platform_user_get_me's WHERE
        // clause specifically. A regression that DROPPED that filter would
        // pass the synthetic-UUID test (because the row would still be
        // missing under that scenario) but fail HERE — the bootstrap row
        // exists, only its anonymized_at flag distinguishes it.
        EnrolledOperator op = enrollAndGetAccessToken();

        // Anonymize via the V90 SECURITY DEFINER primitive (REVOKE on
        // platform_user blocks direct UPDATE from fabt_app).
        Boolean anonymized = jdbc.queryForObject(
                "SELECT platform_user_anonymize(?::uuid)",
                Boolean.class, BOOTSTRAP_ID);
        assertThat(anonymized).isTrue();

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(op.accessToken());
            ResponseEntity<Map> response = restTemplate.exchange(
                    "/api/v1/auth/platform/me",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        } finally {
            // Restore so @AfterEach's reset_to_bootstrap (which only
            // matches WHERE anonymized_at IS NULL) can find the row.
            jdbc.queryForObject(
                    "SELECT platform_user_restore(?::uuid)",
                    Boolean.class, BOOTSTRAP_ID);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private EnrolledOperator enrollAndGetAccessToken() {
        ResponseEntity<Map> login = restTemplate.postForEntity(
                "/api/v1/auth/platform/login",
                Map.of("email", OPERATOR_EMAIL, "password", OPERATOR_PASSWORD),
                Map.class);
        String setupToken = (String) login.getBody().get("token");

        HttpHeaders setupHeaders = new HttpHeaders();
        setupHeaders.setBearerAuth(setupToken);
        ResponseEntity<Map> setup = restTemplate.exchange(
                "/api/v1/auth/platform/mfa-setup",
                HttpMethod.POST,
                new HttpEntity<>(null, setupHeaders),
                Map.class);
        String secret = (String) setup.getBody().get("secret");
        @SuppressWarnings("unchecked")
        List<String> backupCodes = (List<String>) setup.getBody().get("backupCodes");

        String totpCode = currentTotpCode(secret);
        HttpHeaders confirmHeaders = new HttpHeaders();
        confirmHeaders.setBearerAuth(setupToken);
        confirmHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> confirm = restTemplate.exchange(
                "/api/v1/auth/platform/mfa-confirm",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("code", totpCode), confirmHeaders),
                Map.class);
        String accessToken = (String) confirm.getBody().get("token");

        return new EnrolledOperator(accessToken, backupCodes);
    }

    private String currentTotpCode(String secret) {
        try {
            CodeGenerator gen = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
            long timeBucket = new SystemTimeProvider().getTime() / 30;
            return gen.generate(secret, timeBucket);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private record EnrolledOperator(String accessToken, List<String> backupCodes) {
    }
}
