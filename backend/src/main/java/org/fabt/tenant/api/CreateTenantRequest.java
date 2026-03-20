package org.fabt.tenant.api;

import jakarta.validation.constraints.NotBlank;

public record CreateTenantRequest(
        @NotBlank(message = "Name is required") String name,
        @NotBlank(message = "Slug is required") String slug
) {}
