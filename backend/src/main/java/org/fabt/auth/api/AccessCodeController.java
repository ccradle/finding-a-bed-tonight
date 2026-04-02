package org.fabt.auth.api;

import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import org.fabt.auth.domain.User;
import org.fabt.auth.repository.UserRepository;
import org.fabt.auth.service.AccessCodeService;
import org.fabt.shared.web.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Admin-generated one-time access codes for password recovery.
 *
 * Primary recovery path for field workers without email access.
 * Admin generates a code, communicates it verbally or by phone.
 * Worker enters code on login screen, then must set new password.
 */
@RestController
@RequestMapping("/api/v1/users")
public class AccessCodeController {

    private final AccessCodeService accessCodeService;
    private final UserRepository userRepository;

    public AccessCodeController(AccessCodeService accessCodeService, UserRepository userRepository) {
        this.accessCodeService = accessCodeService;
        this.userRepository = userRepository;
    }

    @Operation(
            summary = "Generate a one-time access code for a user",
            description = "Admin generates a temporary access code for a locked-out user. "
                    + "Code expires in 15 minutes, single-use. Returns plaintext code once. "
                    + "DV safeguard: generating a code for a dvAccess=true user requires the admin to also have dvAccess.")
    @PostMapping("/{id}/generate-access-code")
    @PreAuthorize("hasAnyRole('COC_ADMIN', 'PLATFORM_ADMIN')")
    public ResponseEntity<?> generateAccessCode(@PathVariable UUID id, Authentication auth) {
        UUID adminId = UUID.fromString(auth.getName());
        UUID tenantId = TenantContext.getTenantId();

        // DV safeguard (D6): only DV-authorized admins can reset DV-authorized users
        User targetUser = userRepository.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("User not found"));

        if (targetUser.isDvAccess()) {
            User admin = userRepository.findById(adminId)
                    .orElseThrow(() -> new IllegalStateException("Admin user not found"));
            if (!admin.isDvAccess()) {
                return ResponseEntity.status(403).body(Map.of(
                        "error", "dv_access_required",
                        "message", "Only DV-authorized administrators can generate access codes for DV-authorized users."));
            }
        }

        String code = accessCodeService.generateCode(id, adminId, tenantId);

        return ResponseEntity.ok(Map.of(
                "code", code,
                "expiresInMinutes", 15,
                "message", "Communicate this code to the user verbally or by phone. It expires in 15 minutes and can only be used once."
        ));
    }
}
