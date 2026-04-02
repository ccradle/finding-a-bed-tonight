package org.fabt.analytics.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.fabt.availability.service.AvailabilityService;
import org.fabt.availability.service.AvailabilityService.AvailabilitySnapshot;
import org.fabt.shared.web.TenantContext;
import org.fabt.shelter.domain.Shelter;
import org.fabt.shelter.service.ShelterService;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Generates HIC and PIT CSV export data aligned with HUD Inventory.csv schema (FY2024+).
 *
 * HIC (Housing Inventory Count): Columns match HUD LSA Data Dictionary Inventory.csv.
 * PIT (Point-in-Time): Sheltered count by project type and household category.
 *   NOTE: PIT data is submitted via direct entry in HDX 2.0, not CSV upload.
 *   This PIT CSV is a working document for CoC administrators, not a HUD submission file.
 *
 * DV shelters are aggregated (Design D18) — suppressed if fewer than 3 distinct DV shelters.
 * DV shelters use HMISParticipation=2 (Comparable Database) per HUD requirement.
 *
 * References:
 * - HUD LSA Data Dictionary: github.com/HMIS/LSASampleCode
 * - HMIS CSV Format Specifications FY2024
 * - FY2024 Emergency Shelter split: ProjectType 0 (Entry/Exit) and 1 (Night-by-Night)
 */
@Service
public class HicPitExportService {

    private static final Logger log = LoggerFactory.getLogger(HicPitExportService.class);

    // HUD coded values (FY2024+ LSA Data Dictionary)
    private static final int PROJECT_TYPE_ES_ENTRY_EXIT = 0;
    private static final int HOUSEHOLD_WITHOUT_CHILDREN = 1;
    private static final int HOUSEHOLD_WITH_ADULTS_AND_CHILDREN = 3;
    private static final int HOUSEHOLD_CHILDREN_ONLY = 4;
    private static final int AVAILABILITY_YEAR_ROUND = 1;
    private static final int ES_BED_TYPE_FACILITY = 1;
    private static final int TARGET_POP_DV = 1;
    private static final int TARGET_POP_NA = 4;
    private static final int HMIS_PARTICIPATING = 1;
    private static final int HMIS_COMPARABLE_DB = 2;

    // DV aggregation thresholds (Design D18)
    private static final int DV_MIN_SHELTER_COUNT = 3;

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
     * Generate HIC CSV matching HUD Inventory.csv schema (FY2024+).
     */
    public String generateHic(UUID tenantId, LocalDate date) throws Exception {
        return TenantContext.callWithContext(tenantId, true, () -> {
            Tenant tenant = tenantService.findById(tenantId).orElseThrow();
            String cocCode = tenant.getSlug() != null ? tenant.getSlug() : tenantId.toString();
            List<Shelter> shelters = shelterService.findByTenantId();
            List<AvailabilitySnapshot> snapshots = availabilityService.getLatestByTenantId(tenantId);

            StringBuilder csv = new StringBuilder();
            // HUD Inventory.csv header (FY2024+ LSA Data Dictionary)
            csv.append("InventoryID,ProjectID,CoCCode,HouseholdType,Availability,")
               .append("UnitInventory,BedInventory,")
               .append("CHVetBedInventory,YouthVetBedInventory,VetBedInventory,")
               .append("CHYouthBedInventory,YouthBedInventory,CHBedInventory,OtherBedInventory,")
               .append("ESBedType,InventoryStartDate,InventoryEndDate\n");

            // DV aggregation accumulators
            int dvTotalBeds = 0;
            int dvShelterCount = 0;
            int dvVetBeds = 0;
            java.time.Instant dvEarliestStart = null;

            for (Shelter shelter : shelters) {
                List<AvailabilitySnapshot> shelterSnaps = snapshots.stream()
                        .filter(s -> s.shelterId().equals(shelter.getId()))
                        .toList();

                if (shelter.isDvShelter()) {
                    dvShelterCount++;
                    for (AvailabilitySnapshot snap : shelterSnaps) {
                        if (snap.bedsTotal() > 0) {
                            dvTotalBeds += snap.bedsTotal();
                            if ("VETERAN".equals(snap.populationType())) {
                                dvVetBeds += snap.bedsTotal();
                            }
                        }
                    }
                    if (dvEarliestStart == null || (shelter.getCreatedAt() != null
                            && shelter.getCreatedAt().isBefore(dvEarliestStart))) {
                        dvEarliestStart = shelter.getCreatedAt();
                    }
                } else {
                    for (AvailabilitySnapshot snap : shelterSnaps) {
                        if (snap.bedsTotal() <= 0) continue;
                        if (snap.populationType() == null) {
                            log.warn("HIC export: skipping snapshot with null populationType for shelter {}", shelter.getId());
                            continue;
                        }
                        if ("DV_SURVIVOR".equals(snap.populationType())) continue;

                        int householdType = mapHouseholdTypeCode(snap.populationType());
                        int vetBeds = "VETERAN".equals(snap.populationType()) ? snap.bedsTotal() : 0;
                        int otherBeds = snap.bedsTotal() - vetBeds;
                        String inventoryId = generateInventoryId(shelter.getId(), snap.populationType());
                        String startDate = shelter.getCreatedAt() != null
                                ? shelter.getCreatedAt().toString().substring(0, 10) : date.toString();

                        csv.append(escCsv(inventoryId)).append(',')
                           .append(escCsv(shelter.getId().toString())).append(',')
                           .append(escCsv(cocCode)).append(',')
                           .append(householdType).append(',')
                           .append(AVAILABILITY_YEAR_ROUND).append(',')
                           .append(snap.bedsTotal()).append(',')  // UnitInventory (= beds for ES)
                           .append(snap.bedsTotal()).append(',')  // BedInventory
                           .append(0).append(',')  // CHVetBedInventory
                           .append(0).append(',')  // YouthVetBedInventory
                           .append(vetBeds).append(',')  // VetBedInventory
                           .append(0).append(',')  // CHYouthBedInventory
                           .append(0).append(',')  // YouthBedInventory
                           .append(0).append(',')  // CHBedInventory
                           .append(otherBeds).append(',')  // OtherBedInventory
                           .append(ES_BED_TYPE_FACILITY).append(',')
                           .append(startDate).append(',')  // InventoryStartDate
                           .append('\n');  // InventoryEndDate (empty = active)
                    }
                }
            }

            // DV aggregated row — suppressed if < DV_MIN_SHELTER_COUNT distinct shelters (Design D18)
            if (dvTotalBeds > 0 && dvShelterCount >= DV_MIN_SHELTER_COUNT) {
                String dvStartDate = dvEarliestStart != null
                        ? dvEarliestStart.toString().substring(0, 10) : date.toString();
                int dvOtherBeds = dvTotalBeds - dvVetBeds;

                csv.append(',')  // InventoryID (none for aggregate)
                   .append(',')  // ProjectID (none for aggregate)
                   .append(escCsv(cocCode)).append(',')
                   .append(HOUSEHOLD_WITH_ADULTS_AND_CHILDREN).append(',')  // HouseholdType
                   .append(AVAILABILITY_YEAR_ROUND).append(',')
                   .append(dvTotalBeds).append(',')  // UnitInventory
                   .append(dvTotalBeds).append(',')  // BedInventory
                   .append(0).append(',')  // CHVetBedInventory
                   .append(0).append(',')  // YouthVetBedInventory
                   .append(dvVetBeds).append(',')  // VetBedInventory
                   .append(0).append(',')  // CHYouthBedInventory
                   .append(0).append(',')  // YouthBedInventory
                   .append(0).append(',')  // CHBedInventory
                   .append(dvOtherBeds).append(',')  // OtherBedInventory
                   .append(ES_BED_TYPE_FACILITY).append(',')
                   .append(dvStartDate).append(',')
                   .append('\n');
            }

            return csv.toString();
        });
    }

    /**
     * Generate sheltered PIT count CSV for a tenant on a given date.
     * NOTE: PIT data is submitted via direct entry in HDX 2.0, not CSV upload.
     * This CSV is a working document for CoC administrators.
     */
    public String generatePit(UUID tenantId, LocalDate date) throws Exception {
        return TenantContext.callWithContext(tenantId, true, () -> {
            Tenant tenant = tenantService.findById(tenantId).orElseThrow();
            List<Shelter> shelters = shelterService.findByTenantId();
            List<AvailabilitySnapshot> snapshots = availabilityService.getLatestByTenantId(tenantId);

            StringBuilder csv = new StringBuilder();
            csv.append("CoCCode,ProjectType,HouseholdType,TotalPersons\n");

            java.util.Map<Integer, Integer> countsByHouseholdType = new java.util.LinkedHashMap<>();
            int dvTotal = 0;
            int dvShelterCount = 0;

            String cocCode = tenant.getSlug() != null ? tenant.getSlug() : tenantId.toString();

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
                        if (snap.populationType() == null) {
                            log.warn("PIT export: skipping snapshot with null populationType for shelter {}", shelter.getId());
                            continue;
                        }
                        if ("DV_SURVIVOR".equals(snap.populationType())) continue;
                        int hhType = mapHouseholdTypeCode(snap.populationType());
                        countsByHouseholdType.merge(hhType, snap.bedsOccupied(), Integer::sum);
                    }
                }
            }

            for (var entry : countsByHouseholdType.entrySet()) {
                csv.append(escCsv(cocCode)).append(',')
                   .append(PROJECT_TYPE_ES_ENTRY_EXIT).append(',')
                   .append(entry.getKey()).append(',')
                   .append(entry.getValue()).append('\n');
            }

            // DV aggregated — suppressed if < DV_MIN_SHELTER_COUNT distinct shelters (Design D18)
            if (dvTotal > 0 && dvShelterCount >= DV_MIN_SHELTER_COUNT) {
                csv.append(escCsv(cocCode)).append(',')
                   .append(PROJECT_TYPE_ES_ENTRY_EXIT).append(',')
                   .append(HOUSEHOLD_WITH_ADULTS_AND_CHILDREN).append(',')
                   .append(dvTotal).append('\n');
            }

            return csv.toString();
        });
    }

    /**
     * Map FABT population type to HUD HouseholdType integer code.
     * HUD FY2024+: 1=without children, 3=adult+child, 4=children only.
     * Throws on unknown types to prevent silent HUD data corruption.
     */
    int mapHouseholdTypeCode(String populationType) {
        return switch (populationType) {
            case "FAMILY_WITH_CHILDREN" -> HOUSEHOLD_WITH_ADULTS_AND_CHILDREN;
            case "SINGLE_ADULT", "WOMEN_ONLY", "VETERAN" -> HOUSEHOLD_WITHOUT_CHILDREN;
            case "YOUTH_18_24" -> HOUSEHOLD_WITHOUT_CHILDREN;  // 18-24 are adults per HUD
            case "YOUTH_UNDER_18" -> HOUSEHOLD_CHILDREN_ONLY;
            case "DV_SURVIVOR" -> HOUSEHOLD_WITH_ADULTS_AND_CHILDREN;  // DV aggregate default
            default -> throw new IllegalArgumentException(
                    "Unmapped population type for HUD HouseholdType: '" + populationType
                    + "'. Add mapping before exporting.");
        };
    }

    /**
     * Generate deterministic InventoryID from shelterId + populationType.
     * HUD requires a unique identifier per inventory record.
     */
    private String generateInventoryId(UUID shelterId, String populationType) {
        return UUID.nameUUIDFromBytes(
                (shelterId.toString() + ":" + populationType).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        ).toString();
    }

    /**
     * CSV escape with OWASP CSV injection protection.
     * Prefixes cells starting with formula characters (=, +, -, @) with tab.
     */
    private String escCsv(String value) {
        if (value == null) return "";
        String safe = value;
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) {
            safe = "\t" + safe;
        }
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\t")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }
}
