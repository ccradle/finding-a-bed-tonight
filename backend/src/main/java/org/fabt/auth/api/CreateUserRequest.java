package org.fabt.auth.api;

import jakarta.validation.constraints.NotBlank;

public record CreateUserRequest(
        @NotBlank String email,
        @NotBlank String displayName,
        String password,
        String[] roles,
        Boolean dvAccess
) {
}
