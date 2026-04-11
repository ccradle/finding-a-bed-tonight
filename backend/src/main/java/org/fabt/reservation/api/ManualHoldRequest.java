package org.fabt.reservation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/shelters/{shelterId}/manual-hold}.
 *
 * <p>An "offline hold" represents a bed allocation made off-system — typically a
 * phone reservation, an expected guest, or another out-of-band situation where a
 * coordinator needs to mark a bed as held without going through the standard
 * outreach worker hold flow. The endpoint creates a real {@code reservation} row
 * with {@code status = 'HELD'} and the requesting coordinator as {@code user_id},
 * so all downstream invariants and lifecycle behaviors apply automatically
 * (auto-expiry, recompute, audit, etc.).</p>
 */
public record ManualHoldRequest(
        @NotBlank String populationType,
        @Size(max = 200) String reason
) {}
