package org.fabt.shelter.api;

/**
 * Capacity per population type for API requests and responses.
 * Backed by bed_availability snapshots (single source of truth — D10).
 */
public record ShelterCapacityDto(
        String populationType,
        int bedsTotal
) {
}
