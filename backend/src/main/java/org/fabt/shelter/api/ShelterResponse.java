package org.fabt.shelter.api;

import java.time.Instant;
import java.util.UUID;

import org.fabt.shelter.domain.Shelter;

public record ShelterResponse(
        UUID id,
        String name,
        String addressStreet,
        String addressCity,
        String addressState,
        String addressZip,
        String phone,
        Double latitude,
        Double longitude,
        boolean dvShelter,
        boolean active,
        Instant deactivatedAt,
        UUID deactivatedBy,
        String deactivationReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static ShelterResponse from(Shelter shelter) {
        return new ShelterResponse(
                shelter.getId(),
                shelter.getName(),
                shelter.getAddressStreet(),
                shelter.getAddressCity(),
                shelter.getAddressState(),
                shelter.getAddressZip(),
                shelter.getPhone(),
                shelter.getLatitude(),
                shelter.getLongitude(),
                shelter.isDvShelter(),
                shelter.isActive(),
                shelter.getDeactivatedAt(),
                shelter.getDeactivatedBy(),
                shelter.getDeactivationReason(),
                shelter.getCreatedAt(),
                shelter.getUpdatedAt()
        );
    }
}
