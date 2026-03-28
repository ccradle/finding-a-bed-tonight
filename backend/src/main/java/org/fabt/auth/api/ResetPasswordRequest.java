package org.fabt.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank @Size(min = 12, message = "Password must be at least 12 characters") String newPassword
) {
}
