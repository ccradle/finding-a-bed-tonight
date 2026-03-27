package org.fabt.dataimport.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.fabt.dataimport.domain.ImportLog;
import org.fabt.dataimport.repository.ImportLogRepository;
import org.fabt.dataimport.service.ImportResult.ImportError;
import org.fabt.shared.config.JsonString;
import org.fabt.shared.web.TenantContext;
import org.fabt.shelter.api.CreateShelterRequest;
import org.fabt.shelter.api.ShelterCapacityDto;
import org.fabt.shelter.api.ShelterConstraintsDto;
import org.fabt.shelter.api.UpdateShelterRequest;
import org.fabt.shelter.domain.PopulationType;
import org.fabt.shelter.domain.Shelter;
import org.fabt.shelter.repository.ShelterRepository;
import org.fabt.shelter.service.ShelterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core import service that validates, deduplicates, and imports shelter data.
 * Cross-module dependency: depends on shelter module's ShelterService and ShelterRepository.
 */
@Service
public class ShelterImportService {

    private static final Logger log = LoggerFactory.getLogger(ShelterImportService.class);

    private final ShelterService shelterService;
    private final ShelterRepository shelterRepository;
    private final ImportLogRepository importLogRepository;
    private final ObjectMapper objectMapper;

    public ShelterImportService(ShelterService shelterService,
                                ShelterRepository shelterRepository,
                                ImportLogRepository importLogRepository,
                                ObjectMapper objectMapper) {
        this.shelterService = shelterService;
        this.shelterRepository = shelterRepository;
        this.importLogRepository = importLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * A row of shelter data to be imported. Adapters (HSDS, 211 CSV) convert their
     * format-specific data into this common representation.
     */
    public record ShelterImportRow(
            String name,
            String addressStreet,
            String addressCity,
            String addressState,
            String addressZip,
            String phone,
            Double latitude,
            Double longitude,
            Boolean dvShelter,
            Boolean sobrietyRequired,
            Boolean idRequired,
            Boolean referralRequired,
            Boolean petsAllowed,
            Boolean wheelchairAccessible,
            String curfewTime,
            Integer maxStayDays,
            String[] populationTypesServed,
            Map<String, Integer> capacityByType
    ) {
    }

    /**
     * Import a list of shelter rows. Validates, deduplicates (by name + city within tenant),
     * creates or updates shelters, and logs the import.
     *
     * @param tenantId   the tenant performing the import
     * @param importType "HSDS" or "211_CSV"
     * @param filename   original filename for audit trail
     * @param rows       parsed shelter rows
     * @return import result with counts and error details
     */
    @Transactional
    public ImportResult importShelters(UUID tenantId, String importType, String filename,
                                       List<ShelterImportRow> rows) throws Exception {
        // Set tenant context so ShelterService can resolve tenant
        return TenantContext.callWithContext(tenantId, false, () -> {
            int created = 0;
            int updated = 0;
            int skipped = 0;
            int errorCount = 0;
            List<ImportError> errorDetails = new ArrayList<>();

            for (int i = 0; i < rows.size(); i++) {
                int rowNum = i + 1;
                ShelterImportRow row = rows.get(i);

                // Validate required fields
                List<ImportError> rowErrors = validateRow(rowNum, row);
                if (!rowErrors.isEmpty()) {
                    errorDetails.addAll(rowErrors);
                    errorCount++;
                    continue;
                }

                try {
                    // Deduplicate: check if shelter with same name + city exists in tenant
                    Optional<Shelter> existing = shelterRepository.findByTenantIdAndNameAndAddressCity(
                            tenantId, row.name().trim(), row.addressCity().trim());

                    if (existing.isPresent()) {
                        // Full replace: update existing shelter with all fields from import row
                        UpdateShelterRequest updateReq = buildUpdateRequest(row);
                        shelterService.update(existing.get().getId(), updateReq);
                        updated++;
                        log.debug("Import row {}: updated existing shelter '{}' in '{}'",
                                rowNum, row.name(), row.addressCity());
                    } else {
                        // Create new shelter
                        CreateShelterRequest createReq = buildCreateRequest(row);
                        shelterService.create(createReq);
                        created++;
                        log.debug("Import row {}: created new shelter '{}' in '{}'",
                                rowNum, row.name(), row.addressCity());
                    }
                } catch (Exception e) {
                    errorDetails.add(new ImportError(rowNum, "general", e.getMessage()));
                    errorCount++;
                    log.warn("Import row {}: error processing shelter '{}': {}",
                            rowNum, row.name(), e.getMessage());
                }
            }

            // Save import log
            ImportLog importLog = new ImportLog();
            // ID left null for INSERT (Lesson 64)
            importLog.setTenantId(tenantId);
            importLog.setImportType(importType);
            importLog.setFilename(filename);
            importLog.setCreatedCount(created);
            importLog.setUpdatedCount(updated);
            importLog.setSkippedCount(skipped);
            importLog.setErrorCount(errorCount);
            importLog.setErrors(serializeErrors(errorDetails));
            importLog.setCreatedAt(Instant.now());
            importLogRepository.save(importLog);

            return new ImportResult(created, updated, skipped, errorCount, errorDetails);
        });
    }

    private List<ImportError> validateRow(int rowNum, ShelterImportRow row) {
        List<ImportError> errors = new ArrayList<>();

        if (row.name() == null || row.name().isBlank()) {
            errors.add(new ImportError(rowNum, "name", "Name is required"));
        }
        if (row.addressCity() == null || row.addressCity().isBlank()) {
            errors.add(new ImportError(rowNum, "addressCity", "City is required"));
        }

        // Validate population types if provided
        if (row.populationTypesServed() != null) {
            for (String popType : row.populationTypesServed()) {
                try {
                    PopulationType.valueOf(popType);
                } catch (IllegalArgumentException e) {
                    errors.add(new ImportError(rowNum, "populationTypesServed",
                            "Invalid population type: '" + popType + "'"));
                }
            }
        }

        // Validate capacity population types if provided
        if (row.capacityByType() != null) {
            for (String popType : row.capacityByType().keySet()) {
                try {
                    PopulationType.valueOf(popType);
                } catch (IllegalArgumentException e) {
                    errors.add(new ImportError(rowNum, "capacityByType",
                            "Invalid capacity population type: '" + popType + "'"));
                }
            }
        }

        return errors;
    }

    private CreateShelterRequest buildCreateRequest(ShelterImportRow row) {
        ShelterConstraintsDto constraints = buildConstraints(row);
        List<ShelterCapacityDto> capacities = buildCapacities(row);

        return new CreateShelterRequest(
                row.name().trim(),
                row.addressStreet() != null ? row.addressStreet().trim() : null,
                row.addressCity().trim(),
                row.addressState() != null ? row.addressState().trim() : null,
                row.addressZip() != null ? row.addressZip().trim() : null,
                row.phone() != null ? row.phone().trim() : null,
                row.latitude(),
                row.longitude(),
                row.dvShelter() != null && row.dvShelter(),
                constraints,
                capacities
        );
    }

    private UpdateShelterRequest buildUpdateRequest(ShelterImportRow row) {
        ShelterConstraintsDto constraints = buildConstraints(row);
        List<ShelterCapacityDto> capacities = buildCapacities(row);

        return new UpdateShelterRequest(
                row.name().trim(),
                row.addressStreet() != null ? row.addressStreet().trim() : null,
                row.addressCity().trim(),
                row.addressState() != null ? row.addressState().trim() : null,
                row.addressZip() != null ? row.addressZip().trim() : null,
                row.phone() != null ? row.phone().trim() : null,
                row.latitude(),
                row.longitude(),
                constraints,
                capacities
        );
    }

    private ShelterConstraintsDto buildConstraints(ShelterImportRow row) {
        boolean hasSobriety = row.sobrietyRequired() != null;
        boolean hasId = row.idRequired() != null;
        boolean hasReferral = row.referralRequired() != null;
        boolean hasPets = row.petsAllowed() != null;
        boolean hasWheelchair = row.wheelchairAccessible() != null;
        boolean hasPopTypes = row.populationTypesServed() != null;
        boolean hasCurfew = row.curfewTime() != null;
        boolean hasMaxStay = row.maxStayDays() != null;

        // Only create constraints if at least one constraint field is present
        if (!hasSobriety && !hasId && !hasReferral && !hasPets && !hasWheelchair
                && !hasPopTypes && !hasCurfew && !hasMaxStay) {
            return null;
        }

        return new ShelterConstraintsDto(
                row.sobrietyRequired() != null && row.sobrietyRequired(),
                row.idRequired() != null && row.idRequired(),
                row.referralRequired() != null && row.referralRequired(),
                row.petsAllowed() != null && row.petsAllowed(),
                row.wheelchairAccessible() != null && row.wheelchairAccessible(),
                row.curfewTime(),
                row.maxStayDays(),
                row.populationTypesServed()
        );
    }

    private List<ShelterCapacityDto> buildCapacities(ShelterImportRow row) {
        if (row.capacityByType() == null || row.capacityByType().isEmpty()) {
            return null;
        }

        return row.capacityByType().entrySet().stream()
                .map(entry -> new ShelterCapacityDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private JsonString serializeErrors(List<ImportError> errors) {
        if (errors.isEmpty()) {
            return JsonString.of("[]");
        }
        try {
            return JsonString.of(objectMapper.writeValueAsString(errors));
        } catch (JacksonException e) {
            log.error("Failed to serialize import errors", e);
            return JsonString.of("[]");
        }
    }
}
