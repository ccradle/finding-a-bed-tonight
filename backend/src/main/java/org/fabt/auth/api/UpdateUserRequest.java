package org.fabt.auth.api;

public record UpdateUserRequest(
        String displayName,
        String email,
        String[] roles,
        Boolean dvAccess
) {
}
