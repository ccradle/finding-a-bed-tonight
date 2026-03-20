package org.fabt.shelter.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.fabt.shelter.domain.Shelter;
import org.fabt.shelter.domain.ShelterCapacity;
import org.fabt.shelter.domain.ShelterConstraints;
import org.springframework.stereotype.Service;

/**
 * Maps a ShelterDetail to HSDS 3.0 JSON structure with FABT extensions
 * for constraints and capacity data.
 */
@Service
public class ShelterHsdsMapper {

    public Map<String, Object> toHsds(ShelterService.ShelterDetail detail) {
        Shelter shelter = detail.shelter();
        ShelterConstraints constraints = detail.constraints();
        List<ShelterCapacity> capacities = detail.capacities();

        Map<String, Object> hsds = new LinkedHashMap<>();

        // organization
        Map<String, Object> organization = new LinkedHashMap<>();
        organization.put("id", shelter.getId().toString());
        organization.put("name", shelter.getName());
        organization.put("description", "Emergency shelter managed via FABT");
        hsds.put("organization", organization);

        // service
        Map<String, Object> service = new LinkedHashMap<>();
        service.put("id", shelter.getId().toString());
        service.put("name", shelter.getName());
        service.put("organization_id", shelter.getId().toString());
        hsds.put("service", service);

        // location
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("id", shelter.getId().toString());
        location.put("latitude", shelter.getLatitude());
        location.put("longitude", shelter.getLongitude());

        // physical_address
        Map<String, Object> physicalAddress = new LinkedHashMap<>();
        physicalAddress.put("address_1", shelter.getAddressStreet());
        physicalAddress.put("city", shelter.getAddressCity());
        physicalAddress.put("state_province", shelter.getAddressState());
        physicalAddress.put("postal_code", shelter.getAddressZip());
        physicalAddress.put("country", "US");
        location.put("physical_address", physicalAddress);

        hsds.put("location", location);

        // fabt:constraints extension
        if (constraints != null) {
            Map<String, Object> fabtConstraints = new LinkedHashMap<>();
            fabtConstraints.put("sobriety_required", constraints.isSobrietyRequired());
            fabtConstraints.put("id_required", constraints.isIdRequired());
            fabtConstraints.put("referral_required", constraints.isReferralRequired());
            fabtConstraints.put("pets_allowed", constraints.isPetsAllowed());
            fabtConstraints.put("wheelchair_accessible", constraints.isWheelchairAccessible());
            fabtConstraints.put("curfew_time", constraints.getCurfewTime() != null
                    ? constraints.getCurfewTime().toString() : null);
            fabtConstraints.put("max_stay_days", constraints.getMaxStayDays());
            fabtConstraints.put("population_types_served", constraints.getPopulationTypesServed());
            hsds.put("fabt:constraints", fabtConstraints);
        }

        // fabt:capacity extension
        if (capacities != null && !capacities.isEmpty()) {
            List<Map<String, Object>> fabtCapacities = new ArrayList<>();
            for (ShelterCapacity cap : capacities) {
                Map<String, Object> capMap = new LinkedHashMap<>();
                capMap.put("population_type", cap.getPopulationType());
                capMap.put("beds_total", cap.getBedsTotal());
                fabtCapacities.add(capMap);
            }
            hsds.put("fabt:capacity", fabtCapacities);
        }

        return hsds;
    }
}
