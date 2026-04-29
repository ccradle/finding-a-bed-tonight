package org.fabt.shelter.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * PATCH body for {@code /api/v1/admin/tenants/{tenantId}/hold-duration}
 * (transitional-reentry-support task 5.6 / slice 2C).
 *
 * <p>Range bounds per design D5:
 * <ul>
 *   <li>Minimum 30 minutes — tighter than this and a typo could create
 *       a hold so short the operator can't even arrive at the shelter
 *       to occupy it.</li>
 *   <li>Maximum 480 minutes (8 hours) — covers the worst-case release-day
 *       transport scenario (prison bus drop + multi-county transport +
 *       paperwork delays). Beyond this and the hold blocks bed turnover
 *       for genuine same-day arrivals.</li>
 * </ul>
 *
 * <p>The Bean Validation constraints below produce a 400 Bad Request with
 * a clear error before the request reaches the service layer; ShelterService
 * doesn't need to re-validate.
 */
public record HoldDurationRequest(
        @NotNull(message = "holdDurationMinutes is required")
        @Min(value = 30, message = "holdDurationMinutes must be >= 30")
        @Max(value = 480, message = "holdDurationMinutes must be <= 480")
        Integer holdDurationMinutes
) {
}
