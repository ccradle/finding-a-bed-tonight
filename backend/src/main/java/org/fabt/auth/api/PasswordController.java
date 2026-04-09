package org.fabt.auth.api;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.fabt.auth.domain.User;
import org.fabt.auth.repository.UserRepository;
import org.fabt.auth.service.PasswordService;
import org.fabt.shared.web.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PasswordController {

    private static final Logger log = LoggerFactory.getLogger(PasswordController.class);

    private final UserRepository userRepository;
    private final PasswordService passwordService;
    private final MeterRegistry meterRegistry;

    public PasswordController(UserRepository userRepository,
                              PasswordService passwordService,
                              MeterRegistry meterRegistry) {
        this.userRepository = userRepository;
        this.passwordService = passwordService;
        this.meterRegistry = meterRegistry;
    }

    @Operation(
            summary = "Change the authenticated user's password",
            description = "Allows the currently authenticated user to change their own password. " +
                    "Requires the current password for verification and a new password (minimum 12 characters " +
                    "per NIST 800-63B). On success, all existing JWT tokens for this user are invalidated — " +
                    "the user must sign in again with the new password. Returns 409 if the user authenticates " +
                    "only via SSO and has no local password. Returns 401 if the current password is incorrect. " +
                    "Returns 422 if the new password does not meet strength requirements. " +
                    "Rate limited: 5 attempts per 15 minutes."
    )
    @PutMapping("/auth/password")
    @Transactional
    public ResponseEntity<?> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("User not found"));

        // SSO-only users have no local password
        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            counter("fabt.auth.password_change.count", "outcome", "sso_only").increment();
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new MessageBody("Password is managed by your SSO provider"));
        }

        if (!passwordService.matches(request.currentPassword(), user.getPasswordHash())) {
            counter("fabt.auth.password_change.count", "outcome", "wrong_password").increment();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageBody("Current password is incorrect"));
        }

        user.setPasswordHash(passwordService.hash(request.newPassword()));
        user.setTokenVersion(user.getTokenVersion() + 1); // D5: invalidate all existing JWTs
        user.setPasswordChangedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        counter("fabt.auth.password_change.count", "outcome", "success").increment();
        counter("fabt.auth.token_invalidated.count", "reason", "password_change").increment();
        log.info("Password changed for user {} in tenant {} (tokenVersion now {})",
                userId, user.getTenantId(), user.getTokenVersion());

        return ResponseEntity.ok(new MessageBody("Password changed. Please sign in again."));
    }

    @Operation(
            summary = "Reset a user's password (admin action)",
            description = "Allows a COC_ADMIN or PLATFORM_ADMIN to reset the password for any user " +
                    "within their tenant. The admin sets a temporary password (minimum 12 characters) " +
                    "which should be communicated to the user out-of-band. The user's existing JWT tokens " +
                    "are invalidated and they must sign in with the new password. Returns 404 if the user " +
                    "does not exist or belongs to a different tenant. Returns 409 if the user is SSO-only. " +
                    "Rate limited: 10 attempts per 15 minutes."
    )
    @PostMapping("/users/{id}/reset-password")
    @Transactional
    public ResponseEntity<?> resetPassword(
            @Parameter(description = "UUID of the user whose password to reset") @PathVariable UUID id,
            @Valid @RequestBody ResetPasswordRequest request,
            Authentication authentication) {
        UUID tenantId = TenantContext.getTenantId();

        User user = userRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));

        if (!user.getTenantId().equals(tenantId)) {
            throw new NoSuchElementException("User not found: " + id);
        }

        // SSO-only users have no local password
        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new MessageBody("This user authenticates via SSO. Password cannot be reset here."));
        }

        String adminRole = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .findFirst()
                .orElse("UNKNOWN");

        user.setPasswordHash(passwordService.hash(request.newPassword()));
        user.setTokenVersion(user.getTokenVersion() + 1); // D5: invalidate all existing JWTs
        user.setPasswordChangedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        counter("fabt.auth.password_reset.count", "admin_role", adminRole).increment();
        counter("fabt.auth.token_invalidated.count", "reason", "admin_reset").increment();
        log.info("Password reset by admin for user {} in tenant {} (admin role: {}, tokenVersion now {})",
                id, tenantId, adminRole, user.getTokenVersion());

        return ResponseEntity.ok(new MessageBody("Password reset. The user will need to sign in again."));
    }

    private Counter counter(String name, String tagKey, String tagValue) {
        return Counter.builder(name).tag(tagKey, tagValue).register(meterRegistry);
    }

    private record MessageBody(String message) {
    }
}
