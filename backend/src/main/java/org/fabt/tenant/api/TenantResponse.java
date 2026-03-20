package org.fabt.tenant.api;

import java.time.Instant;
import java.util.UUID;

import org.fabt.tenant.domain.Tenant;

public record TenantResponse(
        UUID id,
        String name,
        String slug,
        Instant createdAt,
        Instant updatedAt
) {
    public static TenantResponse from(Tenant tenant) {
        return new TenantResponse(
                tenant.getId(),
                tenant.getName(),
                tenant.getSlug(),
                tenant.getCreatedAt(),
                tenant.getUpdatedAt()
        );
    }
}
