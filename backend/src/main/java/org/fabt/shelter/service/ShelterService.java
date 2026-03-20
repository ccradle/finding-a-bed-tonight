package org.fabt.shelter.service;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.fabt.shelter.api.CreateShelterRequest;
import org.fabt.shelter.api.ShelterCapacityDto;
import org.fabt.shelter.api.ShelterConstraintsDto;
import org.fabt.shelter.api.UpdateShelterRequest;
import org.fabt.shelter.domain.PopulationType;
import org.fabt.shelter.domain.Shelter;
import org.fabt.shelter.domain.ShelterCapacity;
import org.fabt.shelter.domain.ShelterConstraints;
import org.fabt.shelter.repository.ShelterCapacityRepository;
import org.fabt.shelter.repository.ShelterConstraintsRepository;
import org.fabt.shelter.repository.ShelterRepository;
import org.fabt.shared.web.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShelterService {

    public record ShelterDetail(Shelter shelter, ShelterConstraints constraints, List<ShelterCapacity> capacities) {
    }

    public record ShelterFilterCriteria(Boolean petsAllowed, Boolean wheelchairAccessible,
                                        Boolean sobrietyRequired, String populationType) {
    }

    private final ShelterRepository shelterRepository;
    private final ShelterConstraintsRepository constraintsRepository;
    private final ShelterCapacityRepository capacityRepository;
    private final JdbcTemplate jdbcTemplate;

    public ShelterService(ShelterRepository shelterRepository,
                          ShelterConstraintsRepository constraintsRepository,
                          ShelterCapacityRepository capacityRepository,
                          JdbcTemplate jdbcTemplate) {
        this.shelterRepository = shelterRepository;
        this.constraintsRepository = constraintsRepository;
        this.capacityRepository = capacityRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Shelter create(CreateShelterRequest req) {
        UUID tenantId = TenantContext.getTenantId();

        // Validate population types in constraints
        if (req.constraints() != null && req.constraints().populationTypesServed() != null) {
            validatePopulationTypes(req.constraints().populationTypesServed());
        }

        // Validate population types in capacities
        if (req.capacities() != null) {
            for (ShelterCapacityDto cap : req.capacities()) {
                validatePopulationType(cap.populationType());
            }
        }

        // Create shelter — ID left null for INSERT (Lesson 64)
        Shelter shelter = new Shelter();
        shelter.setTenantId(tenantId);
        shelter.setName(req.name());
        shelter.setAddressStreet(req.addressStreet());
        shelter.setAddressCity(req.addressCity());
        shelter.setAddressState(req.addressState());
        shelter.setAddressZip(req.addressZip());
        shelter.setPhone(req.phone());
        shelter.setLatitude(req.latitude());
        shelter.setLongitude(req.longitude());
        shelter.setDvShelter(req.dvShelter());
        shelter.setCreatedAt(Instant.now());
        shelter.setUpdatedAt(Instant.now());

        Shelter saved = shelterRepository.save(shelter);

        // Save constraints if provided
        if (req.constraints() != null) {
            ShelterConstraints constraints = mapConstraints(saved.getId(), req.constraints());
            constraintsRepository.save(constraints);
        }

        // Save capacities if provided
        if (req.capacities() != null) {
            for (ShelterCapacityDto cap : req.capacities()) {
                capacityRepository.save(saved.getId(), cap.populationType(), cap.bedsTotal());
            }
        }

        return saved;
    }

    @Transactional
    public Shelter update(UUID id, UpdateShelterRequest req) {
        UUID tenantId = TenantContext.getTenantId();

        Shelter shelter = shelterRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new NoSuchElementException("Shelter not found: " + id));

        // Validate population types in constraints
        if (req.constraints() != null && req.constraints().populationTypesServed() != null) {
            validatePopulationTypes(req.constraints().populationTypesServed());
        }

        // Validate population types in capacities
        if (req.capacities() != null) {
            for (ShelterCapacityDto cap : req.capacities()) {
                validatePopulationType(cap.populationType());
            }
        }

        if (req.name() != null) shelter.setName(req.name());
        if (req.addressStreet() != null) shelter.setAddressStreet(req.addressStreet());
        if (req.addressCity() != null) shelter.setAddressCity(req.addressCity());
        if (req.addressState() != null) shelter.setAddressState(req.addressState());
        if (req.addressZip() != null) shelter.setAddressZip(req.addressZip());
        if (req.phone() != null) shelter.setPhone(req.phone());
        if (req.latitude() != null) shelter.setLatitude(req.latitude());
        if (req.longitude() != null) shelter.setLongitude(req.longitude());
        shelter.setUpdatedAt(Instant.now());

        Shelter saved = shelterRepository.save(shelter);

        // Update constraints if provided
        if (req.constraints() != null) {
            ShelterConstraints constraints = mapConstraints(saved.getId(), req.constraints());
            // Check if constraints already exist — if so, mark as not new (UPDATE vs INSERT)
            if (constraintsRepository.findById(saved.getId()).isPresent()) {
                constraints.markNotNew();
            }
            constraintsRepository.save(constraints);
        }

        // Update capacities if provided — delete and re-insert
        if (req.capacities() != null) {
            capacityRepository.deleteByShelterId(saved.getId());
            for (ShelterCapacityDto cap : req.capacities()) {
                capacityRepository.save(saved.getId(), cap.populationType(), cap.bedsTotal());
            }
        }

        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<Shelter> findById(UUID id) {
        UUID tenantId = TenantContext.getTenantId();
        return shelterRepository.findByTenantIdAndId(tenantId, id);
    }

    @Transactional(readOnly = true)
    public List<Shelter> findByTenantId() {
        UUID tenantId = TenantContext.getTenantId();
        return shelterRepository.findByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    public List<Shelter> findFiltered(ShelterFilterCriteria criteria) {
        UUID tenantId = TenantContext.getTenantId();

        StringBuilder sql = new StringBuilder(
                "SELECT s.* FROM shelter s JOIN shelter_constraints sc ON s.id = sc.shelter_id WHERE s.tenant_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (criteria.petsAllowed() != null) {
            sql.append(" AND sc.pets_allowed = ?");
            params.add(criteria.petsAllowed());
        }
        if (criteria.wheelchairAccessible() != null) {
            sql.append(" AND sc.wheelchair_accessible = ?");
            params.add(criteria.wheelchairAccessible());
        }
        if (criteria.sobrietyRequired() != null) {
            sql.append(" AND sc.sobriety_required = ?");
            params.add(criteria.sobrietyRequired());
        }
        if (criteria.populationType() != null) {
            validatePopulationType(criteria.populationType());
            sql.append(" AND ? = ANY(sc.population_types_served)");
            params.add(criteria.populationType());
        }

        sql.append(" ORDER BY s.name");

        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> {
                    Shelter shelter = new Shelter();
                    shelter.setId(rs.getObject("id", UUID.class));
                    shelter.setTenantId(rs.getObject("tenant_id", UUID.class));
                    shelter.setName(rs.getString("name"));
                    shelter.setAddressStreet(rs.getString("address_street"));
                    shelter.setAddressCity(rs.getString("address_city"));
                    shelter.setAddressState(rs.getString("address_state"));
                    shelter.setAddressZip(rs.getString("address_zip"));
                    shelter.setPhone(rs.getString("phone"));
                    shelter.setLatitude(rs.getObject("latitude", Double.class));
                    shelter.setLongitude(rs.getObject("longitude", Double.class));
                    shelter.setDvShelter(rs.getBoolean("dv_shelter"));
                    shelter.setCreatedAt(rs.getTimestamp("created_at") != null
                            ? rs.getTimestamp("created_at").toInstant() : null);
                    shelter.setUpdatedAt(rs.getTimestamp("updated_at") != null
                            ? rs.getTimestamp("updated_at").toInstant() : null);
                    return shelter;
                },
                params.toArray()
        );
    }

    @Transactional
    public void delete(UUID id) {
        UUID tenantId = TenantContext.getTenantId();

        Shelter shelter = shelterRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new NoSuchElementException("Shelter not found: " + id));

        capacityRepository.deleteByShelterId(shelter.getId());
        constraintsRepository.deleteById(shelter.getId());
        shelterRepository.delete(shelter);
    }

    @Transactional(readOnly = true)
    public ShelterDetail getDetail(UUID id) {
        UUID tenantId = TenantContext.getTenantId();

        Shelter shelter = shelterRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new NoSuchElementException("Shelter not found: " + id));

        ShelterConstraints constraints = constraintsRepository.findById(shelter.getId()).orElse(null);
        List<ShelterCapacity> capacities = capacityRepository.findByShelterId(shelter.getId());

        return new ShelterDetail(shelter, constraints, capacities);
    }

    private ShelterConstraints mapConstraints(UUID shelterId, ShelterConstraintsDto dto) {
        ShelterConstraints constraints = new ShelterConstraints();
        constraints.setShelterId(shelterId);
        constraints.setSobrietyRequired(dto.sobrietyRequired());
        constraints.setIdRequired(dto.idRequired());
        constraints.setReferralRequired(dto.referralRequired());
        constraints.setPetsAllowed(dto.petsAllowed());
        constraints.setWheelchairAccessible(dto.wheelchairAccessible());
        if (dto.curfewTime() != null && !dto.curfewTime().isBlank()) {
            constraints.setCurfewTime(LocalTime.parse(dto.curfewTime()));
        }
        constraints.setMaxStayDays(dto.maxStayDays());
        constraints.setPopulationTypesServed(dto.populationTypesServed());
        return constraints;
    }

    private void validatePopulationTypes(String[] types) {
        for (String type : types) {
            validatePopulationType(type);
        }
    }

    private void validatePopulationType(String type) {
        try {
            PopulationType.valueOf(type);
        } catch (IllegalArgumentException e) {
            String valid = Arrays.stream(PopulationType.values())
                    .map(Enum::name)
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException(
                    "Invalid population type: '" + type + "'. Valid types are: " + valid);
        }
    }
}
