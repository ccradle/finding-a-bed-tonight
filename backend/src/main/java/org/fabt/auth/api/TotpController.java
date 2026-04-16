package org.fabt.auth.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.annotations.Operation;
import org.fabt.auth.domain.User;
import org.fabt.auth.repository.UserRepository;
import org.fabt.auth.service.TotpService;
import org.fabt.auth.service.UserService;
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.web.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * TOTP two-factor authentication enrollment and management.
 *
 * Enrollment flow: enroll-totp → confirm-totp-enrollment (with first TOTP code).
 * Secret is NOT stored until confirmed. Backup codes generated at confirmation.
 * User-facing language: "sign-in verification" not "2FA."
 */
@RestController
@RequestMapping("/api/v1/auth")
public class TotpController {

    private static final Logger log = LoggerFactory.getLogger(TotpController.class);
    private final TotpService totpService;
    private final UserRepository userRepository;
    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public TotpController(TotpService totpService, UserRepository userRepository,
                          UserService userService, ObjectMapper objectMapper,
                          ApplicationEventPublisher eventPublisher) {
        this.totpService = totpService;
        this.userRepository = userRepository;
        this.userService = userService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @Operation(summary = "Start TOTP enrollment",
            description = "Returns a QR code URI and base32 secret for scanning with an authenticator app. "
                    + "The secret is NOT stored until confirmed via confirm-totp-enrollment.")
    @PostMapping("/enroll-totp")
    public ResponseEntity<?> enrollTotp(Authentication auth) {
        if (!totpService.isEncryptionConfigured()) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "totp_unavailable",
                    "message", "Sign-in verification is not configured on this server."));
        }

        UUID userId = UUID.fromString(auth.getName());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("User not found"));

        if (user.isTotpEnabled()) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "totp_already_enabled",
                    "message", "Sign-in verification is already enabled. Disable it first to re-enroll."));
        }

        String secret = totpService.generateSecret();
        String qrUri = totpService.generateQrUri(secret, user.getEmail());

        // Store pending secret (encrypted) — will be activated on confirmation
        // This allows only one pending enrollment at a time (concurrent enrollment replaces previous)
        user.setTotpSecretEncrypted(totpService.encryptSecret(secret));
        user.setUpdatedAt(java.time.Instant.now());
        userRepository.save(user);

        log.info("TOTP enrollment initiated for user {} (tenant {})", userId, user.getTenantId());

        return ResponseEntity.ok(Map.of(
                "qrUri", qrUri,
                "secret", secret
        ));
    }

    @Operation(summary = "Confirm TOTP enrollment",
            description = "Verifies the first TOTP code from the authenticator app. On success, "
                    + "enables 2FA and returns 8 single-use backup codes (displayed once).")
    @PostMapping("/confirm-totp-enrollment")
    public ResponseEntity<?> confirmTotpEnrollment(
            Authentication auth,
            @RequestBody Map<String, String> body) {

        String code = body.get("code");
        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "missing_code",
                    "message", "Please enter the 6-digit code from your authenticator app."));
        }

        UUID userId = UUID.fromString(auth.getName());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("User not found"));

        if (user.getTotpSecretEncrypted() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "no_pending_enrollment",
                    "message", "No enrollment in progress. Start with POST /api/v1/auth/enroll-totp."));
        }

        // Decrypt and verify
        String secret = totpService.decryptSecret(user.getTotpSecretEncrypted());
        if (!totpService.verifyCode(secret, code)) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "invalid_code",
                    "message", "The code is incorrect. Make sure your authenticator app is synced and try again."));
        }

        // Generate backup codes
        TotpService.BackupCodes backupCodes = totpService.generateBackupCodes();

        // Activate 2FA
        user.setTotpEnabled(true);
        try {
            user.setRecoveryCodes(objectMapper.writeValueAsString(backupCodes.hashed()));
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to serialize backup codes", e);
        }
        user.setTokenVersion(user.getTokenVersion() + 1); // invalidate existing tokens
        user.setUpdatedAt(java.time.Instant.now());
        userRepository.save(user);

        log.warn("TOTP enabled for user {} (tenant {}) — 2FA now active", userId, user.getTenantId());
        eventPublisher.publishEvent(new AuditEventRecord(userId, userId, "TOTP_ENABLED", null, null));

        return ResponseEntity.ok(Map.of(
                "enabled", true,
                "backupCodes", backupCodes.plaintext()
        ));
    }

    @Operation(summary = "Regenerate backup codes",
            description = "Invalidates all previous backup codes and returns 8 new ones.")
    @PostMapping("/regenerate-recovery-codes")
    public ResponseEntity<?> regenerateRecoveryCodes(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("User not found"));

        if (!user.isTotpEnabled()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "totp_not_enabled",
                    "message", "Sign-in verification is not enabled."));
        }

        TotpService.BackupCodes backupCodes = totpService.generateBackupCodes();
        try {
            user.setRecoveryCodes(objectMapper.writeValueAsString(backupCodes.hashed()));
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to serialize backup codes", e);
        }
        user.setUpdatedAt(java.time.Instant.now());
        userRepository.save(user);

        log.warn("Backup codes regenerated for user {} (self-service)", userId);
        eventPublisher.publishEvent(new AuditEventRecord(userId, userId, "BACKUP_CODES_REGENERATED", null, null));

        return ResponseEntity.ok(Map.of("backupCodes", backupCodes.plaintext()));
    }

    @Operation(summary = "Admin disables user's 2FA",
            description = "Clears TOTP secret and disables 2FA for a user (e.g., lost device). Audit-logged.")
    @DeleteMapping("/totp/{id}")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<?> disableUserTotp(@PathVariable UUID id) {
        // D1/D3: userService.getUser(id) pulls tenantId from TenantContext
        // and throws NoSuchElementException -> 404 on cross-tenant access.
        // Pre-fix, bare userRepository.findById(id) allowed a CoC admin in
        // Tenant A to disable 2FA for a Tenant B user — account-takeover
        // precursor (Marcus Webb, VULN-HIGH).
        User user = userService.getUser(id);

        if (!user.isTotpEnabled()) {
            return ResponseEntity.ok(Map.of("message", "Sign-in verification was not enabled."));
        }

        user.setTotpEnabled(false);
        user.setTotpSecretEncrypted(null);
        user.setRecoveryCodes(null);
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setUpdatedAt(java.time.Instant.now());
        userRepository.save(user);

        log.warn("TOTP disabled for user {} by admin (tenant {})", id, user.getTenantId());
        eventPublisher.publishEvent(new AuditEventRecord(null, id, "TOTP_DISABLED_BY_ADMIN", null, null));

        return ResponseEntity.ok(Map.of("message", "Sign-in verification disabled."));
    }

    @Operation(summary = "Admin regenerates user's backup codes",
            description = "Invalidates all previous codes and returns new ones for verbal communication to the user.")
    @PostMapping("/totp/{id}/regenerate-recovery-codes")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<?> adminRegenerateRecoveryCodes(@PathVariable UUID id) {
        // D1/D3: userService.getUser(id) pulls tenantId from TenantContext
        // and throws NoSuchElementException -> 404 on cross-tenant access.
        // Pre-fix, bare userRepository.findById(id) allowed a CoC admin in
        // Tenant A to regenerate backup codes for a Tenant B user — and
        // the response body returned those new codes (VULN-HIGH: direct
        // account takeover, no additional steps required).
        User user = userService.getUser(id);

        if (!user.isTotpEnabled()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "totp_not_enabled",
                    "message", "User does not have sign-in verification enabled."));
        }

        TotpService.BackupCodes backupCodes = totpService.generateBackupCodes();
        try {
            user.setRecoveryCodes(objectMapper.writeValueAsString(backupCodes.hashed()));
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to serialize backup codes", e);
        }
        user.setUpdatedAt(java.time.Instant.now());
        userRepository.save(user);

        log.warn("Backup codes regenerated for user {} by admin", id);
        eventPublisher.publishEvent(new AuditEventRecord(null, id, "BACKUP_CODES_REGENERATED_BY_ADMIN", null, null));

        return ResponseEntity.ok(Map.of("backupCodes", backupCodes.plaintext()));
    }
}
