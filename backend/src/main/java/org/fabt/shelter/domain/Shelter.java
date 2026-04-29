package org.fabt.shelter.domain;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("shelter")
public class Shelter {

    @Id
    private UUID id;
    private UUID tenantId;
    private String name;
    private String addressStreet;
    private String addressCity;
    private String addressState;
    private String addressZip;
    private String phone;
    private Double latitude;
    private Double longitude;
    private boolean dvShelter;
    /**
     * Controlled vocabulary classification (transitional-reentry-support task 3.2,
     * V91 column). Coupled to {@link #dvShelter} via the V91
     * {@code shelter_dv_implies_dv_type} CHECK constraint:
     * {@code dvShelter=true} implies {@code shelterType=ShelterType.DV}.
     * {@link org.fabt.shelter.service.ShelterService} keeps the two fields in
     * lockstep on every write. Defaults to {@link ShelterType#EMERGENCY} for new
     * non-DV shelters per V91's column DEFAULT.
     */
    private ShelterType shelterType = ShelterType.EMERGENCY;
    /** When false, shelter is hidden from bed search but still loaded for DV referral safety checks. */
    private boolean active = true;
    private Instant deactivatedAt;
    private UUID deactivatedBy;
    private String deactivationReason;
    private Instant createdAt;
    private Instant updatedAt;

    public Shelter() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddressStreet() {
        return addressStreet;
    }

    public void setAddressStreet(String addressStreet) {
        this.addressStreet = addressStreet;
    }

    public String getAddressCity() {
        return addressCity;
    }

    public void setAddressCity(String addressCity) {
        this.addressCity = addressCity;
    }

    public String getAddressState() {
        return addressState;
    }

    public void setAddressState(String addressState) {
        this.addressState = addressState;
    }

    public String getAddressZip() {
        return addressZip;
    }

    public void setAddressZip(String addressZip) {
        this.addressZip = addressZip;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public boolean isDvShelter() {
        return dvShelter;
    }

    public void setDvShelter(boolean dvShelter) {
        this.dvShelter = dvShelter;
    }

    public ShelterType getShelterType() {
        return shelterType;
    }

    public void setShelterType(ShelterType shelterType) {
        this.shelterType = shelterType;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getDeactivatedAt() {
        return deactivatedAt;
    }

    public void setDeactivatedAt(Instant deactivatedAt) {
        this.deactivatedAt = deactivatedAt;
    }

    public UUID getDeactivatedBy() {
        return deactivatedBy;
    }

    public void setDeactivatedBy(UUID deactivatedBy) {
        this.deactivatedBy = deactivatedBy;
    }

    public String getDeactivationReason() {
        return deactivationReason;
    }

    public void setDeactivationReason(String deactivationReason) {
        this.deactivationReason = deactivationReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
