package org.fabt.availability.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only availability snapshot. Each row represents a point-in-time reading
 * of bed occupancy for one shelter + population type. Never updated or deleted.
 */
public class BedAvailability {

    private UUID id;
    private UUID shelterId;
    private UUID tenantId;
    private String populationType;
    private int bedsTotal;
    private int bedsOccupied;
    private int bedsOnHold;
    private boolean acceptingNewGuests;
    private Instant snapshotTs;
    private String updatedBy;
    private String notes;

    public BedAvailability() {
    }

    public BedAvailability(UUID shelterId, UUID tenantId, String populationType,
                           int bedsTotal, int bedsOccupied, int bedsOnHold,
                           boolean acceptingNewGuests, String updatedBy, String notes) {
        this.shelterId = shelterId;
        this.tenantId = tenantId;
        this.populationType = populationType;
        this.bedsTotal = bedsTotal;
        this.bedsOccupied = bedsOccupied;
        this.bedsOnHold = bedsOnHold;
        this.acceptingNewGuests = acceptingNewGuests;
        this.updatedBy = updatedBy;
        this.notes = notes;
    }

    /** Derived value — never stored. */
    public int getBedsAvailable() {
        return bedsTotal - bedsOccupied - bedsOnHold;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getShelterId() { return shelterId; }
    public void setShelterId(UUID shelterId) { this.shelterId = shelterId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public String getPopulationType() { return populationType; }
    public void setPopulationType(String populationType) { this.populationType = populationType; }

    public int getBedsTotal() { return bedsTotal; }
    public void setBedsTotal(int bedsTotal) { this.bedsTotal = bedsTotal; }

    public int getBedsOccupied() { return bedsOccupied; }
    public void setBedsOccupied(int bedsOccupied) { this.bedsOccupied = bedsOccupied; }

    public int getBedsOnHold() { return bedsOnHold; }
    public void setBedsOnHold(int bedsOnHold) { this.bedsOnHold = bedsOnHold; }

    public boolean isAcceptingNewGuests() { return acceptingNewGuests; }
    public void setAcceptingNewGuests(boolean acceptingNewGuests) { this.acceptingNewGuests = acceptingNewGuests; }

    public Instant getSnapshotTs() { return snapshotTs; }
    public void setSnapshotTs(Instant snapshotTs) { this.snapshotTs = snapshotTs; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    private Integer overflowBeds;

    public Integer getOverflowBeds() { return overflowBeds; }
    public void setOverflowBeds(Integer overflowBeds) { this.overflowBeds = overflowBeds; }
}
