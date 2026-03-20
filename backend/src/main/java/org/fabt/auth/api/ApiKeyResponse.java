package org.fabt.auth.api;

import java.time.Instant;
import java.util.UUID;

import org.fabt.auth.domain.ApiKey;

public record ApiKeyResponse(
        UUID id,
        String suffix,
        String label,
        String role,
        UUID shelterId,
        boolean active,
        Instant createdAt
) {
    public static ApiKeyResponse from(ApiKey apiKey) {
        return new ApiKeyResponse(
                apiKey.getId(),
                apiKey.getKeySuffix(),
                apiKey.getLabel(),
                apiKey.getRole(),
                apiKey.getShelterId(),
                apiKey.isActive(),
                apiKey.getCreatedAt()
        );
    }
}
