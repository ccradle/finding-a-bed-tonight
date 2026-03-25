package org.fabt.analytics.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.fabt.availability.service.AvailabilityService;
import org.fabt.availability.service.AvailabilityService.AvailabilitySnapshot;
import org.fabt.shared.web.TenantContext;
import org.fabt.shelter.domain.Shelter;
import org.fabt.shelter.service.ShelterService;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantService;
import org.springframework.stereotype.Service;

/**
 * Generates HIC and PIT CSV export data (Design D6, D7).
 *
 * HIC (Housing Inventory Count): CSV with HUD format columns.
 * PIT (Point-in-Time): sheltered count by project type and household.
 *
 * DV shelters included in HIC but with suppressed address. DV beds aggregated in PIT.
 */
@Service
public class HicPitExportService {

    private final ShelterService shelterService;
    private final AvailabilityService availabilityService;
    private final TenantService tenantService;

    public HicPitExportService(ShelterService shelterService,
                                AvailabilityService availabilityService,
                                TenantService tenantService) {
        this.shelterService = shelterService;
        this.availabilityService = availabilityService;
        this.tenantService = tenantService;
    }

    /**
     * Generate HIC CSV data for a tenant on a given date.
     * Returns CSV string with HUD HIC submission columns.
     */
    public String generateHic(UUID tenantId, java.time.LocalDate date) {
        TenantContext.setTenantId(tenantId);
        TenantContext.setDvAccess(true);

        try {
            List<Shelter> shelters = shelterService.findByTenantId();
            List<AvailabilitySnapshot> snapshots = availabilityService.getLatestByTenantId(tenantId);

            StringBuilder csv = new StringBuilder();
            csv.append("ProjectID,ProjectName,ProjectType,HouseholdType,BedType,")
               .append("AvailabilityCategory,BedInventory,UnitInventory,TargetPopulation,HMISParticipation\n");

            // DV aggregation accumulators
            int dvTotalBeds = 0;
            int dvShelterCount = 0; // D18: count distinct DV shelters

            for (Shelter shelter : shelters) {
                List<AvailabilitySnapshot> shelterSnaps = snapshots.stream()
                        .filter(s -> s.shelterId().equals(shelter.getId()))
                        .toList();

                if (shelter.isDvShelter()) {
                    dvShelterCount++;
                    for (AvailabilitySnapshot snap : shelterSnaps) {
                        if (snap.bedsTotal() > 0) {
                            dvTotalBeds += snap.bedsTotal();
                        }
                    }
                } else {
                    for (AvailabilitySnapshot snap : shelterSnaps) {
                        if (snap.bedsTotal() <= 0) continue;
                        if ("DV_SURVIVOR".equals(snap.populationType())) continue;

                        csv.append(escCsv(shelter.getId().toString())).append(',')
                           .append(escCsv(shelter.getName())).append(',')
                           .append("ES,") // Emergency Shelter
                           .append(mapHouseholdType(snap.populationType())).append(',')
                           .append("Facility-Based,")
                           .append("Year-Round,")
                           .append(snap.bedsTotal()).append(',')
                           .append(snap.bedsTotal()).append(',') // unit = bed for ES
                           .append(escCsv(snap.populationType())).append(',')
                           .append("Yes\n");
                    }
                }
            }

            // DV aggregated row — suppressed if < 3 distinct shelters (Design D18)
            if (dvTotalBeds > 0 && dvShelterCount >= 3) {
                csv.append(",DV Shelters (Aggregated),ES,DV,Facility-Based,Year-Round,")
                   .append(dvTotalBeds).append(',')
                   .append(dvTotalBeds).append(",DV_SURVIVOR,Yes\n");
            }

            return csv.toString();
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Generate sheltered PIT count CSV for a tenant on a given date.
     * Returns CSV string with HUD PIT submission columns.
     */
    public String generatePit(UUID tenantId, java.time.LocalDate date) {
        TenantContext.setTenantId(tenantId);
        TenantContext.setDvAccess(true);

        try {
            Tenant tenant = tenantService.findById(tenantId).orElseThrow();
            List<Shelter> shelters = shelterService.findByTenantId();
            List<AvailabilitySnapshot> snapshots = availabilityService.getLatestByTenantId(tenantId);

            StringBuilder csv = new StringBuilder();
            csv.append("CoCCode,ProjectType,HouseholdType,TotalPersons\n");

            // Aggregate by population type across all shelters
            // DV shelters aggregated together per D4, suppressed if < 3 shelters (D18)
            java.util.Map<String, Integer> countsByPopType = new java.util.LinkedHashMap<>();
            int dvTotal = 0;
            int dvShelterCount = 0;

            for (Shelter shelter : shelters) {
                List<AvailabilitySnapshot> shelterSnaps = snapshots.stream()
                        .filter(s -> s.shelterId().equals(shelter.getId()))
                        .toList();

                if (shelter.isDvShelter()) {
                    dvShelterCount++;
                    for (AvailabilitySnapshot snap : shelterSnaps) {
                        dvTotal += snap.bedsOccupied();
                    }
                } else {
                    for (AvailabilitySnapshot snap : shelterSnaps) {
                        if ("DV_SURVIVOR".equals(snap.populationType())) continue;
                        countsByPopType.merge(snap.populationType(), snap.bedsOccupied(), Integer::sum);
                    }
                }
            }

            String cocCode = tenant.getSlug() != null ? tenant.getSlug() : tenantId.toString();

            for (var entry : countsByPopType.entrySet()) {
                csv.append(escCsv(cocCode)).append(",ES,")
                   .append(mapHouseholdType(entry.getKey())).append(',')
                   .append(entry.getValue()).append('\n');
            }

            // DV aggregated — suppressed if < 3 distinct shelters (Design D18)
            if (dvTotal > 0 && dvShelterCount >= 3) {
                csv.append(escCsv(cocCode)).append(",ES,DV,")
                   .append(dvTotal).append('\n');
            }

            return csv.toString();
        } finally {
            TenantContext.clear();
        }
    }

    private String mapHouseholdType(String populationType) {
        return switch (populationType) {
            case "FAMILY_WITH_CHILDREN" -> "Families";
            case "SINGLE_ADULT" -> "Adults Only";
            case "WOMEN_ONLY" -> "Adults Only";
            case "VETERAN" -> "Veterans";
            case "YOUTH_18_24" -> "Children Only";
            case "YOUTH_UNDER_18" -> "Children Only";
            case "DV_SURVIVOR" -> "DV";
            default -> populationType;
        };
    }

    private String escCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
