package org.fabt.shelter.repository;

import java.util.UUID;

import org.fabt.shelter.domain.ShelterConstraints;
import org.springframework.data.repository.CrudRepository;

/**
 * Repository for ShelterConstraints. Since shelterId is the @Id (PK),
 * use findById(shelterId) to look up constraints for a given shelter.
 */
public interface ShelterConstraintsRepository extends CrudRepository<ShelterConstraints, UUID> {
}
