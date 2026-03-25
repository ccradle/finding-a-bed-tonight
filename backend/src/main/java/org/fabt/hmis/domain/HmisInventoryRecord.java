package org.fabt.hmis.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * HMIS Element 2.07 Bed and Unit Inventory record.
 * Transformed from FABT's bed_availability snapshots.
 * Contains NO client PII — project-level descriptor only.
 */
public record HmisInventoryRecord(
        UUID projectId,
        String projectName,
        String householdType,
        int bedInventory,
        int bedsOccupied,
        double utilizationPercent,
        Instant inventoryDate,
        boolean isDvAggregated
) {}
