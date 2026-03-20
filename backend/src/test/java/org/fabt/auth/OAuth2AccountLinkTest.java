package org.fabt.auth;

import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.TestAuthHelper;
import org.fabt.auth.service.JwtService;
import org.fabt.auth.service.OAuth2AccountLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests OAuth2 account linking (Option C: pre-created accounts only).
 * Tests the service directly since the actual OAuth2 redirect/callback flow
 * requires a running OAuth2 provider.
 */
class OAuth2AccountLinkTest extends BaseIntegrationTest {

    @Autowired
    private TestAuthHelper authHelper;

    @Autowired
    private OAuth2AccountLinkService linkService;

    @Autowired
    private JwtService jwtService;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        authHelper.setupTestTenant();
        authHelper.setupAdminUser();
        tenantId = authHelper.getTestTenantId();
    }

    @Test
    void test_linkWithPreCreatedAccount_succeeds() {
        // Admin user exists with email admin@test.fabt.org
        OAuth2AccountLinkService.LinkResult result = linkService.linkOrReject(
                "google",
                "google-subject-12345",
                TestAuthHelper.ADMIN_EMAIL,
                tenantId
        );

        assertThat(result.success()).isTrue();
        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshToken()).isNotBlank();
        assertThat(result.error()).isNull();
        assertThat(result.userId()).isNotNull();

        // Verify the issued JWT has the same structure as a password-based JWT
        JwtService.JwtClaims claims = jwtService.validateToken(result.accessToken());
        assertThat(claims.tenantId()).isEqualTo(tenantId);
        assertThat(claims.roles()).contains("PLATFORM_ADMIN");
        assertThat(claims.type()).isEqualTo("access");
    }

    @Test
    void test_linkWithUnknownEmail_rejected() {
        OAuth2AccountLinkService.LinkResult result = linkService.linkOrReject(
                "google",
                "google-subject-unknown",
                "nobody@example.com",
                tenantId
        );

        assertThat(result.success()).isFalse();
        assertThat(result.accessToken()).isNull();
        assertThat(result.error()).contains("No account found");
        assertThat(result.userId()).isNull();
    }

    @Test
    void test_subsequentLoginWithLinkedAccount_succeeds() {
        // First login — creates the link
        OAuth2AccountLinkService.LinkResult firstLogin = linkService.linkOrReject(
                "google",
                "google-subject-repeat",
                TestAuthHelper.ADMIN_EMAIL,
                tenantId
        );
        assertThat(firstLogin.success()).isTrue();

        // Second login — reuses the existing link
        OAuth2AccountLinkService.LinkResult secondLogin = linkService.linkOrReject(
                "google",
                "google-subject-repeat",
                TestAuthHelper.ADMIN_EMAIL,
                tenantId
        );
        assertThat(secondLogin.success()).isTrue();
        assertThat(secondLogin.userId()).isEqualTo(firstLogin.userId());
    }

    @Test
    void test_jwtFromOAuth2_identicalToPasswordJwt() {
        // Get JWT via OAuth2 link
        OAuth2AccountLinkService.LinkResult oauthResult = linkService.linkOrReject(
                "google",
                "google-subject-jwt-compare",
                TestAuthHelper.ADMIN_EMAIL,
                tenantId
        );
        JwtService.JwtClaims oauthClaims = jwtService.validateToken(oauthResult.accessToken());

        // Get JWT via password login (from TestAuthHelper)
        String passwordToken = jwtService.generateAccessToken(
                authHelper.getUserRepository().findByTenantIdAndEmail(tenantId, TestAuthHelper.ADMIN_EMAIL).orElseThrow()
        );
        JwtService.JwtClaims passwordClaims = jwtService.validateToken(passwordToken);

        // Verify structural identity
        assertThat(oauthClaims.userId()).isEqualTo(passwordClaims.userId());
        assertThat(oauthClaims.tenantId()).isEqualTo(passwordClaims.tenantId());
        assertThat(oauthClaims.roles()).isEqualTo(passwordClaims.roles());
        assertThat(oauthClaims.dvAccess()).isEqualTo(passwordClaims.dvAccess());
        assertThat(oauthClaims.type()).isEqualTo(passwordClaims.type());
    }
}
