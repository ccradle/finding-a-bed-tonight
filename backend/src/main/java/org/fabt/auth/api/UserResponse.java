package org.fabt.auth.api;

import java.time.Instant;
import java.util.UUID;

import org.fabt.auth.domain.User;

public record UserResponse(
        UUID id,
        String email,
        String displayName,
        String[] roles,
        boolean dvAccess,
        String status,
        Instant createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRoles(),
                user.isDvAccess(),
                user.getStatus(),
                user.getCreatedAt()
        );
    }
}
