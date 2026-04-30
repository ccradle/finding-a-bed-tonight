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
        Instant updatedAt,
        // transitional-reentry-support task 5.3 (slice 4 prereq, warroom C1
        // promotion). The slice-2 entity additions need to round-trip through
        // the GET surface so the admin edit form can pre-populate existing
        // values. Without these fields a save would silently lose the
        // shelterType/county/requiresVerificationCall the user previously
        // entered.
        String shelterType,
        String county,
        boolean requiresVerificationCall
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
                shelter.getUpdatedAt(),
                shelter.getShelterType() != null ? shelter.getShelterType().name() : null,
                shelter.getCounty(),
                shelter.isRequiresVerificationCall()
        );
    }
}
