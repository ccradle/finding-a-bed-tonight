package org.fabt.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/v1/auth/reset-password.
 * Matches validation pattern of ChangePasswordRequest and ResetPasswordRequest.
 */
public record EmailResetRequest(
        @NotBlank String token,
        @NotBlank @Size(min = 12, message = "Password must be at least 12 characters") String newPassword
) {}
