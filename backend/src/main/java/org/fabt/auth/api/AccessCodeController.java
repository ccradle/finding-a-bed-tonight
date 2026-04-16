package org.fabt.auth.api;

import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import org.fabt.auth.domain.User;
import org.fabt.auth.service.AccessCodeService;
import org.fabt.auth.service.UserService;
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
    private final UserService userService;

    public AccessCodeController(AccessCodeService accessCodeService, UserService userService) {
        this.accessCodeService = accessCodeService;
        this.userService = userService;
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

        // Task 2.5.1 (VULN-MED): userService.getUser pulls tenantId from
        // TenantContext and throws NoSuchElementException -> 404 on cross-
        // tenant mismatch. Pre-fix, bare userRepository.findById allowed a
        // Tenant A admin POST /api/v1/users/{tenantB-user-id}/generate-
        // access-code to emit an ACCESS_CODE_GENERATED audit event in
        // Tenant B with a Tenant A admin as actor (Casey's VAWA audit-
        // trail falsification concern).
        User targetUser = userService.getUser(id);

        // DV safeguard (D6): only DV-authorized admins can reset DV-authorized users
        if (targetUser.isDvAccess()) {
            // Task 2.5.2 (D10): admin-self-lookup through userService for
            // consistency with D2 convention. Admin id comes from JWT so
            // practically safe, but uniform pattern preferred.
            User admin = userService.getUser(adminId);
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
