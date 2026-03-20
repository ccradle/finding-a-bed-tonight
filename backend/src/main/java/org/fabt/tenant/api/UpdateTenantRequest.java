package org.fabt.tenant.api;

import jakarta.validation.constraints.NotBlank;

public record UpdateTenantRequest(
        @NotBlank(message = "Name is required") String name
) {}
