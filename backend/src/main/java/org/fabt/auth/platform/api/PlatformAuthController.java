package org.fabt.auth.platform.api;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.fabt.auth.platform.PlatformAuthService;
import org.fabt.auth.platform.PlatformJwtException;
import org.fabt.auth.platform.PlatformJwtService;
import org.fabt.auth.platform.PlatformOperatorAnonymizedException;
import org.fabt.auth.platform.PlatformScopeMismatchException;
import org.fabt.auth.platform.PlatformUser;
import org.fabt.auth.platform.dto.PlatformOperatorMeDto;
import org.fabt.auth.platform.repository.PlatformUserRepository;
import org.fabt.auth.platform.repository.PlatformUserRepository.PlatformOperatorMeRow;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final PlatformUserRepository userRepository;

    public PlatformAuthController(PlatformAuthService authService,
                                   PlatformJwtService jwtService,
                                   PlatformUserRepository userRepository) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
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
        // F11 §6.3 — Cache-Control: no-store prevents the browser back-
        // button (or any caching proxy) from resurrecting the plaintext
        // backup codes after the operator navigates away from the
        // enrollment page. `BackupCodesDisplay.tsx` JSDoc references
        // this header by name; pre-fix it was an unimplemented contract.
        // Pragma + Expires belt-and-braces for legacy intermediaries.
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store, no-cache, must-revalidate, private")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .body(Map.of(
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
        PlatformAuthService.VerifyMfaResult result =
                authService.verifyMfaWithState(claims.sub(), body.code());
        if (!result.success()) {
            // F11 spec round 3 H7 + warroom round 6 A1: the SPA needs to
            // distinguish "wrong code, N attempts remaining" from "account
            // locked, do not retry" so the error UI can render the correct
            // copy. The {@code error} string drives the SPA's branch:
            //   account_locked   → "Too many failed attempts. Account locked
            //                       for 15 minutes…"
            //   invalid_mfa_code → "Code invalid. N attempts remaining…"
            // attemptsRemaining is omitted from the locked-out body
            // (frontend treats it as 0 by branch).
            if (result.accountLocked()) {
                return ResponseEntity.status(401)
                        .body(Map.of("error", "account_locked"));
            }
            return ResponseEntity.status(401)
                    .body(Map.of(
                            "error", "invalid_mfa_code",
                            "attemptsRemaining", result.attemptsRemaining()));
        }
        PlatformUser user = authService.lookupForToken(claims.sub());
        return ResponseEntity.ok(Map.of(
                "token", jwtService.generateAccessToken(user),
                "expiresInSeconds", jwtService.getAccessTokenExpirySeconds()));
    }

    /**
     * Returns the authenticated operator's self-metadata for the F11
     * dashboard header (operator email, last login, MFA enrollment date,
     * backup-codes-remaining badge). Requires a post-MFA access token —
     * scoped tokens (mfa-setup, mfa-verify) are rejected with 403.
     */
    @GetMapping("/me")
    public ResponseEntity<?> getMe(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        PlatformJwtService.PlatformJwtClaims claims = requireAccessToken(authHeader);
        PlatformOperatorMeRow row = userRepository.findMeMetadata(claims.sub())
                .orElseThrow(() -> new PlatformOperatorAnonymizedException(
                        "Authenticated operator's platform_user row is anonymized or missing"));
        return ResponseEntity.ok(new PlatformOperatorMeDto(
                row.id(),
                row.email(),
                row.mfaEnabled(),
                row.lastLoginAt(),
                row.mfaEnrolledAt(),
                row.backupCodesRemaining()));
    }

    /**
     * No-op server-side logout for v0.54. The 15-minute access-token TTL +
     * client-side {@code sessionStorage} clear is the actual revocation
     * mechanism; this endpoint exists so the SPA has a clean affordance and
     * so a future Phase H+ {@code token_invalidation_at} column has a
     * natural hook to plug into. Returns 204 on success.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        requireAccessToken(authHeader);
        return ResponseEntity.noContent().build();
    }

    /**
     * Validates a post-MFA access token (no {@code scope} claim — only
     * post-MFA-confirm and post-MFA-verify access tokens have null
     * {@code scope}). Throws {@link PlatformJwtException} (→ HTTP 401 via
     * {@code PlatformAuthExceptionHandler}) for missing/malformed/invalid
     * tokens. Throws {@link PlatformScopeMismatchException} (→ HTTP 403 via
     * its {@code @ResponseStatus} annotation) if the token is otherwise
     * valid but is a scoped token (mfa-setup or mfa-verify) — the SPA's
     * 403 handler routes these back to the appropriate scoped endpoint
     * without wiping sessionStorage.
     *
     * <p>Note: we deliberately don't add a {@code claims.mfaVerified()}
     * defensive check. {@code generateAccessToken} hard-codes the bit, so
     * any code path that currently produces a null-scope claim necessarily
     * has {@code mfaVerified=true}. A future change to the issuer that
     * relaxes this would need its own test, not a dead-code defense here.
     */
    private PlatformJwtService.PlatformJwtClaims requireAccessToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new PlatformJwtException("Missing or malformed Authorization header");
        }
        String token = authHeader.substring("Bearer ".length()).trim();
        PlatformJwtService.PlatformJwtClaims claims = jwtService.validateToken(token);
        if (claims.scope() != null) {
            throw new PlatformScopeMismatchException(
                    "Access token required; presented scoped token");
        }
        return claims;
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
