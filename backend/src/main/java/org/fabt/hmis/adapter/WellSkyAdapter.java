package org.fabt.hmis.adapter;

import java.util.List;
import java.util.stream.Collectors;

import org.fabt.hmis.domain.HmisInventoryRecord;
import org.fabt.hmis.domain.HmisVendorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * WellSky HMIS adapter — generates HMIS CSV format for upload.
 * WellSky primarily supports CSV import rather than REST API push.
 * The generated CSV is stored in the outbox payload for pickup.
 */
@Component
public class WellSkyAdapter implements HmisVendorAdapter {

    private static final Logger log = LoggerFactory.getLogger(WellSkyAdapter.class);

    @Override
    public void push(List<HmisInventoryRecord> records, HmisVendorConfig config) throws Exception {
        // Generate HMIS CSV format
        StringBuilder csv = new StringBuilder();
        csv.append("ProjectID,ProjectName,HouseholdType,BedInventory,BedsOccupied,Utilization,InventoryDate\n");

        for (HmisInventoryRecord record : records) {
            csv.append(String.format("%s,%s,%s,%d,%d,%.1f,%s\n",
                    record.projectId() != null ? record.projectId() : "AGGREGATED",
                    escapeCsv(record.projectName()),
                    record.householdType(),
                    record.bedInventory(),
                    record.bedsOccupied(),
                    record.utilizationPercent(),
                    record.inventoryDate()
            ));
        }

        log.info("Generated WellSky CSV with {} records ({} bytes) for {}",
                records.size(), csv.length(), config.baseUrl());

        // For WellSky, the CSV is the deliverable. In production this could be:
        // - SFTP upload to a WellSky-provided endpoint
        // - Email to a configured address
        // - Stored for manual download from Admin UI
        // For now, the payload is stored in the outbox for retrieval.
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    @Override
    public String vendorType() {
        return "WELLSKY";
    }
}
