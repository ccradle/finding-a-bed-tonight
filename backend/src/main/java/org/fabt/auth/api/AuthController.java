package org.fabt.auth.api;

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
