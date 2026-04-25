package org.fabt.auth.platform.api;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.fabt.auth.platform.PlatformAuthService;
import org.fabt.auth.platform.PlatformJwtException;
import org.fabt.auth.platform.PlatformJwtService;
import org.fabt.auth.platform.PlatformUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for {@code iss=fabt-platform} authentication (Phase G-4 /
 * issue #141). Distinct path prefix ({@code /api/v1/auth/platform/}) so the
 * iss-routed JwtDecoder dispatch in {@code SecurityConfig} (G-4.2 task 3.9)
 * can apply platform-specific filters here without affecting the tenant
 * {@code AuthController}.
 *
 * <h2>Forced-MFA-on-first-login flow</h2>
 *
 * <pre>
 *   POST /login                 →  scope=mfa-setup token (10 min) | scope=mfa-verify token (5 min) | 401
 *   POST /mfa-setup             →  TOTP secret + QR + 10 backup codes  (requires scope=mfa-setup)
 *   POST /mfa-confirm           →  access token (15 min)               (requires scope=mfa-setup, valid TOTP)
 *   POST /login/mfa-verify      →  access token (15 min)               (requires scope=mfa-verify, valid TOTP-or-backup)
 * </pre>
 *
 * <p><b>Server-validated scope</b> (Marcus's hard constraint, design
 * Decision 4): the {@link PlatformJwtService.PlatformJwtClaims#scope}
 * claim is checked at the controller level on every scoped endpoint —
 * URL-path-only enforcement is insufficient because a confused-deputy
 * bug could replay a token at the wrong path.
 *
 * <p>Lockout + rate-limit (G-4.2 task 3.7-3.8) are applied as a separate
 * layer (Spring filter / aspect) and are not visible from this controller.
 */
@RestController
@RequestMapping("/api/v1/auth/platform")
public class PlatformAuthController {

    private final PlatformAuthService authService;
    private final PlatformJwtService jwtService;

    public PlatformAuthController(PlatformAuthService authService, PlatformJwtService jwtService) {
        this.authService = authService;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest body) {
        PlatformAuthService.LoginResult result = authService.login(body.email(), body.password());
        return switch (result.outcome()) {
            case MFA_SETUP_REQUIRED -> ResponseEntity.ok(Map.of(
                    "scope", PlatformJwtService.SCOPE_MFA_SETUP,
                    "token", jwtService.generateMfaSetupToken(result.user()),
                    "expiresInSeconds", 600));
            case MFA_VERIFY_REQUIRED -> ResponseEntity.ok(Map.of(
                    "scope", PlatformJwtService.SCOPE_MFA_VERIFY,
                    "token", jwtService.generateMfaVerifyToken(result.user()),
                    "expiresInSeconds", 300));
            case REJECTED -> ResponseEntity.status(401)
                    .body(Map.of("error", "invalid_credentials"));
        };
    }

    @PostMapping("/mfa-setup")
    public ResponseEntity<?> mfaSetup(@RequestHeader("Authorization") String authHeader) {
        PlatformJwtService.PlatformJwtClaims claims = requireScopedToken(
                authHeader, PlatformJwtService.SCOPE_MFA_SETUP);
        PlatformAuthService.MfaSetup setup = authService.setupMfa(claims.sub());
        return ResponseEntity.ok(Map.of(
                "secret", setup.secret(),
                "qrUri", setup.qrUri(),
                "backupCodes", setup.plaintextBackupCodes()));
    }

    @PostMapping("/mfa-confirm")
    public ResponseEntity<?> mfaConfirm(@RequestHeader("Authorization") String authHeader,
                                         @Valid @RequestBody CodeRequest body) {
        PlatformJwtService.PlatformJwtClaims claims = requireScopedToken(
                authHeader, PlatformJwtService.SCOPE_MFA_SETUP);
        if (!authService.confirmMfaSetup(claims.sub(), body.code())) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "invalid_totp_code"));
        }
        PlatformUser user = authService.lookupForToken(claims.sub());
        return ResponseEntity.ok(Map.of(
                "token", jwtService.generateAccessToken(user),
                "expiresInSeconds", jwtService.getAccessTokenExpirySeconds()));
    }

    @PostMapping("/login/mfa-verify")
    public ResponseEntity<?> mfaVerify(@RequestHeader("Authorization") String authHeader,
                                        @Valid @RequestBody CodeRequest body) {
        PlatformJwtService.PlatformJwtClaims claims = requireScopedToken(
                authHeader, PlatformJwtService.SCOPE_MFA_VERIFY);
        if (!authService.verifyMfa(claims.sub(), body.code())) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "invalid_mfa_code"));
        }
        PlatformUser user = authService.lookupForToken(claims.sub());
        return ResponseEntity.ok(Map.of(
                "token", jwtService.generateAccessToken(user),
                "expiresInSeconds", jwtService.getAccessTokenExpirySeconds()));
    }

    private PlatformJwtService.PlatformJwtClaims requireScopedToken(String authHeader,
                                                                     String requiredScope) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new PlatformJwtException("Missing or malformed Authorization header");
        }
        String token = authHeader.substring("Bearer ".length()).trim();
        PlatformJwtService.PlatformJwtClaims claims = jwtService.validateToken(token);
        if (!requiredScope.equals(claims.scope())) {
            throw new PlatformJwtException(
                    "Token scope mismatch: required=" + requiredScope
                            + ", presented=" + claims.scope());
        }
        return claims;
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record CodeRequest(@NotBlank String code) {
    }
}
