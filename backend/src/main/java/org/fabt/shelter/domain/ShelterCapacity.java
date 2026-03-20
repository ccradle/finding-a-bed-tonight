package org.fabt.shelter.domain;

import java.util.UUID;

/**
 * Plain POJO for the shelter_capacity table which has a composite primary key
 * (shelter_id, population_type). Spring Data JDBC does not natively support
 * composite keys, so CRUD operations use JdbcTemplate directly.
 */
public class ShelterCapacity {

    private UUID shelterId;
    private String populationType;
    private int bedsTotal;

    public ShelterCapacity() {
    }

    public ShelterCapacity(UUID shelterId, String populationType, int bedsTotal) {
        this.shelterId = shelterId;
        this.populationType = populationType;
        this.bedsTotal = bedsTotal;
    }

    public UUID getShelterId() {
        return shelterId;
    }

    public void setShelterId(UUID shelterId) {
        this.shelterId = shelterId;
    }

    public String getPopulationType() {
        return populationType;
    }

    public void setPopulationType(String populationType) {
        this.populationType = populationType;
    }

    public int getBedsTotal() {
        return bedsTotal;
    }

    public void setBedsTotal(int bedsTotal) {
        this.bedsTotal = bedsTotal;
    }
}
