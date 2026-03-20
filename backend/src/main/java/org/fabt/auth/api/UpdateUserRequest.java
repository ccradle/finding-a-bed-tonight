package org.fabt.auth.api;

public record UpdateUserRequest(
        String displayName,
        String[] roles,
        Boolean dvAccess
) {
}
