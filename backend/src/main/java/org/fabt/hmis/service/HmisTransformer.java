package org.fabt.hmis.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.fabt.availability.service.AvailabilityService;
import org.fabt.availability.service.AvailabilityService.AvailabilitySnapshot;
import org.fabt.hmis.domain.HmisInventoryRecord;
import org.fabt.shelter.domain.Shelter;
import org.fabt.shelter.service.ShelterService;
import org.fabt.shared.web.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Transforms FABT bed_availability snapshots into HMIS Element 2.07 inventory records.
 * DV shelters are aggregated — never individual occupancy (small-n inference risk).
 */
@Service
public class HmisTransformer {

    private final ShelterService shelterService;
    private final AvailabilityService availabilityService;

    public HmisTransformer(ShelterService shelterService, AvailabilityService availabilityService) {
        this.shelterService = shelterService;
        this.availabilityService = availabilityService;
    }

    /**
     * Build HMIS inventory records for a tenant.
     * Non-DV shelters: one record per shelter/population.
     * DV shelters: aggregated into a single record per population type.
     */
    public List<HmisInventoryRecord> buildInventory(UUID tenantId) {
        TenantContext.setTenantId(tenantId);
        TenantContext.setDvAccess(true); // need to see DV shelters for aggregation

        try {
            List<Shelter> shelters = shelterService.findByTenantId();
            List<AvailabilitySnapshot> allSnapshots = availabilityService.getLatestByTenantId(tenantId);

            List<HmisInventoryRecord> records = new ArrayList<>();

            // Accumulators for DV aggregation
            int dvTotalBeds = 0;
            int dvOccupied = 0;
            int dvShelterCount = 0; // D18: count distinct DV shelters for suppression check

            for (Shelter shelter : shelters) {
                List<AvailabilitySnapshot> shelterSnapshots = allSnapshots.stream()
                        .filter(s -> s.shelterId().equals(shelter.getId()))
                        .toList();

                if (shelter.isDvShelter()) {
                    dvShelterCount++;
                    // Aggregate DV — sum totals, don't create individual records
                    for (AvailabilitySnapshot snap : shelterSnapshots) {
                        dvTotalBeds += snap.bedsTotal();
                        dvOccupied += snap.bedsOccupied();
                    }
                } else {
                    // Non-DV — one record per population type
                    for (AvailabilitySnapshot snap : shelterSnapshots) {
                        // Skip zero-bed rows — nothing to report to HMIS
                        if (snap.bedsTotal() <= 0) continue;
                        // Non-DV shelters must never show DV_SURVIVOR population type
                        // (stale data from UI may have created zero-bed DV rows)
                        if ("DV_SURVIVOR".equals(snap.populationType())) continue;

                        double utilization = (double) snap.bedsOccupied() / snap.bedsTotal() * 100.0;
                        records.add(new HmisInventoryRecord(
                                shelter.getId(),
                                shelter.getName(),
                                snap.populationType(),
                                snap.bedsTotal(),
                                snap.bedsOccupied(),
                                utilization,
                                snap.snapshotTs(),
                                false
                        ));
                    }
                }
            }

            // Add aggregated DV record only if enough DV shelters exist (Design D18).
            // If a CoC has fewer than 3 DV shelters, the aggregate count could identify
            // individual shelters — suppress entirely to prevent re-identification.
            if (dvTotalBeds > 0 && dvShelterCount >= 3) {
                double dvUtilization = (double) dvOccupied / dvTotalBeds * 100.0;
                records.add(new HmisInventoryRecord(
                        null, // no individual project ID
                        "DV Shelters (Aggregated)",
                        "DV_SURVIVOR",
                        dvTotalBeds,
                        dvOccupied,
                        dvUtilization,
                        Instant.now(),
                        true
                ));
            }

            return records;
        } finally {
            TenantContext.clear();
        }
    }
}
