package org.fabt.shelter.api;

import java.util.List;

public record UpdateShelterRequest(
        String name,
        String addressStreet,
        String addressCity,
        String addressState,
        String addressZip,
        String phone,
        Double latitude,
        Double longitude,
        ShelterConstraintsDto constraints,
        List<ShelterCapacityDto> capacities
) {
}
