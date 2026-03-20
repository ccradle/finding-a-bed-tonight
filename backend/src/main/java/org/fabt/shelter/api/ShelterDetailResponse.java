package org.fabt.shelter.api;

import java.util.List;

import org.fabt.shelter.domain.ShelterCapacity;
import org.fabt.shelter.domain.ShelterConstraints;
import org.fabt.shelter.service.ShelterService;

public record ShelterDetailResponse(
        ShelterResponse shelter,
        ShelterConstraintsDto constraints,
        List<ShelterCapacityDto> capacities
) {
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
                    .map(cap -> new ShelterCapacityDto(cap.getPopulationType(), cap.getBedsTotal()))
                    .toList();
        }

        return new ShelterDetailResponse(shelterResponse, constraintsDto, capacityDtos);
    }
}
