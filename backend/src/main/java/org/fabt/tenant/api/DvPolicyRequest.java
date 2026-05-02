package org.fabt.tenant.api;

import jakarta.validation.constraints.NotNull;

/**
 * PATCH body for {@code /api/v1/admin/tenants/{tenantId}/dv-policy}
 * (dv-policy-tenant-flag OpenSpec change task §4.1).
 *
 * <p>The single field {@code dvPolicyEnabled} is the new value the
 * COC_ADMIN wants to set. The Bean Validation {@link NotNull} ensures the
 * controller never has to handle a missing-field case — a 400 with the
 * standard validation error shape lands before any service-layer code
 * runs.
 *
 * <p><b>JSON-key casing intentional split (mirrors the slice-2C
 * HoldDurationRequest pattern):</b> the DTO field name
 * {@code dvPolicyEnabled} stays camelCase per Java + REST convention. At
 * the persistence boundary
 * ({@link org.fabt.tenant.service.TenantService#setDvPolicyEnabled}) the
 * value is written under the snake_case key {@code dv_policy_enabled}
 * inside {@code tenant.config} JSONB. The split is consistent — the
 * caller never sees the snake_case form; the JSONB-stored config is its
 * own surface with its own conventions.
 */
public record DvPolicyRequest(
        @NotNull(message = "dvPolicyEnabled is required")
        Boolean dvPolicyEnabled
) {
}
