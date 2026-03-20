package org.fabt.shelter.domain;

import java.time.LocalTime;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Shelter constraints entity. The ID (shelterId) is a FK to shelter, not auto-generated.
 * Implements Persistable to control isNew() — since the ID is always set before save,
 * Spring Data JDBC would otherwise always attempt UPDATE. We use a transient flag to
 * indicate whether this is a new entity (INSERT) or existing (UPDATE).
 */
@Table("shelter_constraints")
public class ShelterConstraints implements Persistable<UUID> {

    @Id
    private UUID shelterId;

    @Transient
    private boolean isNew = true;
    private boolean sobrietyRequired;
    private boolean idRequired;
    private boolean referralRequired;
    private boolean petsAllowed;
    private boolean wheelchairAccessible;
    private LocalTime curfewTime;
    private Integer maxStayDays;
    private String[] populationTypesServed;

    public ShelterConstraints() {
    }

    public UUID getShelterId() {
        return shelterId;
    }

    public void setShelterId(UUID shelterId) {
        this.shelterId = shelterId;
    }

    public boolean isSobrietyRequired() {
        return sobrietyRequired;
    }

    public void setSobrietyRequired(boolean sobrietyRequired) {
        this.sobrietyRequired = sobrietyRequired;
    }

    public boolean isIdRequired() {
        return idRequired;
    }

    public void setIdRequired(boolean idRequired) {
        this.idRequired = idRequired;
    }

    public boolean isReferralRequired() {
        return referralRequired;
    }

    public void setReferralRequired(boolean referralRequired) {
        this.referralRequired = referralRequired;
    }

    public boolean isPetsAllowed() {
        return petsAllowed;
    }

    public void setPetsAllowed(boolean petsAllowed) {
        this.petsAllowed = petsAllowed;
    }

    public boolean isWheelchairAccessible() {
        return wheelchairAccessible;
    }

    public void setWheelchairAccessible(boolean wheelchairAccessible) {
        this.wheelchairAccessible = wheelchairAccessible;
    }

    public LocalTime getCurfewTime() {
        return curfewTime;
    }

    public void setCurfewTime(LocalTime curfewTime) {
        this.curfewTime = curfewTime;
    }

    public Integer getMaxStayDays() {
        return maxStayDays;
    }

    public void setMaxStayDays(Integer maxStayDays) {
        this.maxStayDays = maxStayDays;
    }

    public String[] getPopulationTypesServed() {
        return populationTypesServed;
    }

    public void setPopulationTypesServed(String[] populationTypesServed) {
        this.populationTypesServed = populationTypesServed;
    }

    @Override
    public UUID getId() {
        return shelterId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public void markNotNew() {
        this.isNew = false;
    }
}
