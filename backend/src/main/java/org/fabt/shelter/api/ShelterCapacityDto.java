package org.fabt.shelter.api;

/**
 * Capacity per population type for API requests and responses.
 * Backed by bed_availability snapshots (single source of truth — D10).
 *
 * <p>Issue #65: added {@code bedsOccupied} so CSV import can set current
 * occupancy at onboarding time (Marcus Okafor: "day one shouldn't show
 * 100% availability"). Defaults to 0 for backward compat — existing
 * callers that construct with 2 args use the convenience constructor.</p>
 */
public record ShelterCapacityDto(
        String populationType,
        int bedsTotal,
        int bedsOccupied
) {
    /** Backward-compatible constructor: bedsOccupied defaults to 0. */
    public ShelterCapacityDto(String populationType, int bedsTotal) {
        this(populationType, bedsTotal, 0);
    }
}
