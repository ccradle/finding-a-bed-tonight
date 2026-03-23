package org.fabt.shelter.api;

import java.time.Instant;
import java.util.List;

import org.fabt.shelter.domain.ShelterConstraints;
import org.fabt.shelter.service.ShelterService;

public record ShelterDetailResponse(
        ShelterResponse shelter,
        ShelterConstraintsDto constraints,
        List<ShelterCapacityDto> capacities,
        List<AvailabilityDto> availability
) {
    public record AvailabilityDto(
            String populationType,
            int bedsTotal,
            int bedsOccupied,
            int bedsOnHold,
            int bedsAvailable,
            boolean acceptingNewGuests,
            Instant snapshotTs,
            Long dataAgeSeconds,
            String dataFreshness
    ) {}

    public static ShelterDetailResponse from(ShelterService.ShelterDetail detail) {
        ShelterResponse shelterResponse = ShelterResponse.from(detail.shelter());

        ShelterConstraintsDto constraintsDto = null;
        if (detail.constraints() != null) {
            ShelterConstraints c = detail.constraints();
            constraintsDto = new ShelterConstraintsDto(
                    c.isSobrietyRequired(),
                    c.isIdRequired(),
                    c.isReferralRequired(),
                    c.isPetsAllowed(),
                    c.isWheelchairAccessible(),
                    c.getCurfewTime() != null ? c.getCurfewTime().toString() : null,
                    c.getMaxStayDays(),
                    c.getPopulationTypesServed()
            );
        }

        List<ShelterCapacityDto> capacityDtos = null;
        if (detail.capacities() != null) {
            capacityDtos = detail.capacities().stream()
                    .map(cap -> new ShelterCapacityDto(cap.populationType(), cap.bedsTotal()))
                    .toList();
        }

        List<AvailabilityDto> availabilityDtos = null;
        if (detail.availability() != null) {
            availabilityDtos = detail.availability();
        }

        return new ShelterDetailResponse(shelterResponse, constraintsDto, capacityDtos, availabilityDtos);
    }
}
