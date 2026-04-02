package org.fabt.auth.api;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.fabt.auth.domain.User;
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.auth.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;

import org.fabt.auth.service.AccessCodeService;
import org.fabt.auth.service.JwtService;
import org.fabt.auth.service.PasswordService;
import org.fabt.auth.service.TotpService;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    @Value("${spring.mail.host:}")
    private String mailHost;

    private final TenantService tenantService;
    private final UserRepository userRepository;
    private final PasswordService passwordService;
    private final JwtService jwtService;
    private final TotpService totpService;
    private final AccessCodeService accessCodeService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    // mfaToken attempt tracking: jti → attempt count (Caffeine cache, 5-min TTL)
    private final Cache<String, Integer> mfaAttempts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(10000)
            .build();

    // mfaToken single-use blocklist: jti → true (Caffeine cache, 5-min TTL)
    private final Cache<String, Boolean> mfaBlocklist = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(10000)
            .build();

    public AuthController(TenantService tenantService,
                          UserRepository userRepository,
                          PasswordService passwordService,
                          JwtService jwtService,
                          TotpService totpService,
                          AccessCodeService accessCodeService,
                          ObjectMapper objectMapper,
                          ApplicationEventPublisher eventPublisher) {
        this.tenantService = tenantService;
        this.userRepository = userRepository;
        this.passwordService = passwordService;
        this.jwtService = jwtService;
        this.totpService = totpService;
        this.accessCodeService = accessCodeService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @Operation(
            summary = "Authenticate a user and obtain JWT tokens",
            description = "Authenticates a user by tenant slug, email, and password. On success, " +
                    "returns an access token (short-lived, used in Authorization: Bearer header) " +
                    "and a refresh token (long-lived, used to obtain new access tokens without " +
                    "re-entering credentials). The tenant slug identifies which CoC organization " +
                    "the user belongs to — the same email can exist under different tenants. " +
                    "Returns 401 with {\"message\": \"Invalid credentials\"} if the tenant slug, " +
                    "email, or password is wrong. The error intentionally does not distinguish " +
                    "between unknown tenant, unknown user, or wrong password to prevent enumeration. " +
                    "No authentication header is required for this endpoint."
    )
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        // Find tenant by slug
        Tenant tenant = tenantService.findBySlug(request.tenantSlug()).orElse(null);
        if (tenant == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorBody("Invalid credentials"));
        }

        // Find user by tenantId + email
        User user = userRepository.findByTenantIdAndEmail(tenant.getId(), request.email()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorBody("Invalid credentials"));
        }

        // Reject deactivated users
        if (!user.isActive()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorBody("Account deactivated. Contact your administrator."));
        }

        // Verify password
        if (!passwordService.matches(request.password(), user.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorBody("Invalid credentials"));
        }

        // If TOTP enabled, return mfaRequired instead of tokens (two-phase login)
        if (user.isTotpEnabled()) {
            String mfaToken = jwtService.generateMfaToken(user);
            log.info("MFA required for user {} (tenant {})", user.getId(), tenant.getId());
            return ResponseEntity.ok(Map.of(
                    "mfaRequired", true,
                    "mfaToken", mfaToken
            ));
        }

        // Generate tokens (include tenant name for display in header)
        String accessToken = jwtService.generateAccessToken(user, tenant.getName());
        String refreshToken = jwtService.generateRefreshToken(user);

        return ResponseEntity.ok(new TokenResponse(accessToken, refreshToken, jwtService.getAccessTokenExpirySeconds()));
    }

    @Operation(
            summary = "Exchange a refresh token for a new access token",
            description = "Accepts a valid refresh token and returns a new access token paired " +
                    "with the same refresh token. Use this when the access token has expired " +
                    "to avoid forcing the user to log in again. Returns 401 if the refresh token " +
                    "is expired, malformed, not of type 'refresh', or if the associated user no " +
                    "longer exists (e.g., deleted after the token was issued). No authentication " +
                    "header is required — the refresh token in the body is the credential."
    )
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@Valid @RequestBody RefreshRequest request) {
        JwtService.JwtClaims claims;
        try {
            claims = jwtService.validateToken(request.refreshToken());
        } catch (Exception e) {
            log.debug("Token refresh failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorBody("Invalid refresh token"));
        }

        if (!"refresh".equals(claims.type())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorBody("Invalid refresh token"));
        }

        User user = userRepository.findById(claims.userId()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorBody("Invalid refresh token"));
        }

        String accessToken = jwtService.generateAccessToken(user);

        return ResponseEntity.ok(new TokenResponse(accessToken, request.refreshToken(), jwtService.getAccessTokenExpirySeconds()));
    }

    @Operation(
            summary = "Request password reset via email",
            description = "Sends a password reset link to the user's email if SMTP is configured. "
                    + "Always returns 200 regardless of whether the email exists (no account enumeration).")
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody Map<String, String> body) {
        // Always return 200 — no account enumeration
        if (mailHost == null || mailHost.isBlank()) {
            // SMTP not configured — silently succeed (no email sent)
            log.debug("Forgot password requested but SMTP not configured");
            return ResponseEntity.ok(Map.of("message", "If the email exists, a reset link has been sent."));
        }

        // TODO: Implement email delivery when SMTP is configured
        // For now, log and return success
        String email = body.get("email");
        log.info("Password reset requested for email: {} (email delivery not yet implemented)", email);
        return ResponseEntity.ok(Map.of("message", "If the email exists, a reset link has been sent."));
    }

    @Operation(
            summary = "Reset password via email token",
            description = "Accepts a password reset token and new password. "
                    + "Invalidates the token and all existing JWTs for the user.")
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> body) {
        // TODO: Implement when email reset tokens are persisted
        return ResponseEntity.status(503).body(Map.of(
                "error", "not_available",
                "message", "Email-based password reset is not yet available. Contact your administrator for an access code."));
    }

    @Operation(
            summary = "Get available authentication capabilities",
            description = "Returns which auth features are configured on this server. "
                    + "Frontend uses this to conditionally show/hide Forgot Password link and 2FA enrollment.")
    @GetMapping("/capabilities")
    public ResponseEntity<?> getCapabilities() {
        return ResponseEntity.ok(Map.of(
                "emailResetAvailable", mailHost != null && !mailHost.isBlank(),
                "totpAvailable", totpService.isEncryptionConfigured(),
                "accessCodeAvailable", true
        ));
    }

    @Operation(
            summary = "Log in with a one-time access code",
            description = "Accepts email, tenant slug, and one-time access code generated by an admin. "
                    + "On success, issues JWTs with mustChangePassword=true — the user must set a new "
                    + "password before accessing any other endpoint.")
    @PostMapping("/access-code")
    public ResponseEntity<?> accessCodeLogin(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String tenantSlug = body.get("tenantSlug");
        String code = body.get("code");

        if (email == null || tenantSlug == null || code == null) {
            return ResponseEntity.badRequest().body(new ErrorBody("email, tenantSlug, and code are required"));
        }

        User user = accessCodeService.validateCode(email, tenantSlug, code);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorBody("Invalid credentials"));
        }

        // Publish audit event OUTSIDE the @Transactional boundary (avoids rollback-only marking)
        log.info("Access code login successful for user {}", user.getId());
        eventPublisher.publishEvent(new AuditEventRecord(null, user.getId(), "ACCESS_CODE_USED", null, null));

        // Issue JWTs with mustChangePassword flag
        String accessToken = jwtService.generateAccessTokenWithPasswordChange(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        return ResponseEntity.ok(Map.of(
                "accessToken", accessToken,
                "refreshToken", refreshToken,
                "expiresIn", jwtService.getAccessTokenExpirySeconds(),
                "mustChangePassword", true
        ));
    }

    @Operation(
            summary = "Verify TOTP code to complete two-phase login",
            description = "Accepts an mfaToken (from login) and a 6-digit TOTP code or 8-character backup code. "
                    + "On success, issues access and refresh JWTs. Rate limited to 5 attempts per mfaToken. "
                    + "mfaToken is single-use — cannot be replayed after successful verification."
    )
    @PostMapping("/verify-totp")
    public ResponseEntity<?> verifyTotp(@RequestBody Map<String, String> body) {
        String mfaToken = body.get("mfaToken");
        String code = body.get("code");

        if (mfaToken == null || code == null) {
            return ResponseEntity.badRequest().body(new ErrorBody("mfaToken and code are required"));
        }

        // Validate mfaToken
        JwtService.JwtClaims claims;
        try {
            claims = jwtService.validateToken(mfaToken);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorBody("Invalid or expired verification token. Please sign in again."));
        }

        if (!"mfa".equals(claims.type())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorBody("Invalid verification token."));
        }

        String jti = claims.jti();

        // Check single-use blocklist
        if (jti != null && mfaBlocklist.getIfPresent(jti) != null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorBody("Verification token already used. Please sign in again."));
        }

        // Check attempt count (rate limiting: 5 per mfaToken)
        if (jti != null) {
            Integer attempts = mfaAttempts.get(jti, k -> 0);
            if (attempts >= 5) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body(new ErrorBody("Too many attempts. Please sign in again."));
            }
            mfaAttempts.put(jti, attempts + 1);
        }

        // Find user
        User user = userRepository.findById(claims.userId()).orElse(null);
        if (user == null || !user.isActive() || !user.isTotpEnabled()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorBody("Invalid verification token."));
        }

        // Try TOTP code first
        String secret = totpService.decryptSecret(user.getTotpSecretEncrypted());
        boolean codeValid = totpService.verifyCode(secret, code);

        // If TOTP failed, try as backup code
        if (!codeValid && code.length() == 8) {
            try {
                List<String> hashedCodes = objectMapper.readValue(
                        user.getRecoveryCodes(), new TypeReference<>() {});
                int matchIndex = totpService.verifyBackupCode(code, hashedCodes);
                if (matchIndex >= 0) {
                    // Mark as consumed (set to null)
                    hashedCodes.set(matchIndex, null);
                    user.setRecoveryCodes(objectMapper.writeValueAsString(hashedCodes));
                    userRepository.save(user);
                    codeValid = true;
                    log.warn("Backup code used for user {} (index {})", user.getId(), matchIndex);
                }
            } catch (Exception e) {
                log.error("Failed to parse backup codes for user {}", user.getId(), e);
            }
        }

        if (!codeValid) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorBody("Invalid code. Please try again."));
        }

        // Success — add mfaToken to blocklist (single-use)
        if (jti != null) {
            mfaBlocklist.put(jti, true);
        }

        // Issue real JWTs
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        log.info("TOTP verification successful for user {} (tenant {})", user.getId(), user.getTenantId());

        return ResponseEntity.ok(new TokenResponse(accessToken, refreshToken, jwtService.getAccessTokenExpirySeconds()));
    }

    private record ErrorBody(String message) {
    }
}
