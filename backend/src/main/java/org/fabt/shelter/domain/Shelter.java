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
    /**
     * Supervision-geography boundary (transitional-reentry-support task 3.2,
     * V91 column). Free-text VARCHAR(100) at the DB layer; per design D3
     * (warroom H2 revision 2026-04-28) values are validated app-layer against
     * {@code tenant.config.active_counties}, NOT a DB-level enum. Default
     * seed for {@code active_counties} is the NC 100-county list
     * ({@code NcCountyDefaults}); PLATFORM_OPERATOR can override per tenant.
     *
     * <p>Used by the reentry-navigator bed-search filter — supervision is
     * jurisdictional (county/district authority), not distance-based, so a
     * shelter 2 mi away in the wrong county is a supervision violation while
     * 40 mi away in the right county is valid placement.
     *
     * <p>Nullable: most existing shelters launch with null; the
     * {@link #requiresVerificationCall} sentinel covers the gap by surfacing
     * a "call to verify" badge to navigators rather than silence.
     */
    private String county;
    /**
     * Sentinel flag (transitional-reentry-support task 3.3, V94 column):
     * shelter requires a direct call before eligibility can be assumed,
     * regardless of what {@code eligibility_criteria} says. Two roles:
     * <ul>
     *   <li>UI: search results render a "call to verify" badge when true.</li>
     *   <li>BedSearchService: the H1 three-way {@code acceptsFelonies=true}
     *       filter logic (design D1 revision) INCLUDES shelters with null
     *       {@code eligibility_criteria} ONLY when this flag is true.</li>
     * </ul>
     * Default false (V94 column default).
     */
    private boolean requiresVerificationCall = false;
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

    public String getCounty() {
        return county;
    }

    public void setCounty(String county) {
        this.county = county;
    }

    public boolean isRequiresVerificationCall() {
        return requiresVerificationCall;
    }

    public void setRequiresVerificationCall(boolean requiresVerificationCall) {
        this.requiresVerificationCall = requiresVerificationCall;
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
