package org.fabt.auth.api;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.fabt.auth.domain.User;
import org.fabt.auth.repository.UserRepository;
import org.fabt.auth.service.JwtService;
import org.fabt.auth.service.PasswordService;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final TenantService tenantService;
    private final UserRepository userRepository;
    private final PasswordService passwordService;
    private final JwtService jwtService;

    public AuthController(TenantService tenantService,
                          UserRepository userRepository,
                          PasswordService passwordService,
                          JwtService jwtService) {
        this.tenantService = tenantService;
        this.userRepository = userRepository;
        this.passwordService = passwordService;
        this.jwtService = jwtService;
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

        // Verify password
        if (!passwordService.matches(request.password(), user.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorBody("Invalid credentials"));
        }

        // Generate tokens
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        return ResponseEntity.ok(new TokenResponse(accessToken, refreshToken));
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

        return ResponseEntity.ok(new TokenResponse(accessToken, request.refreshToken()));
    }

    private record ErrorBody(String message) {
    }
}
