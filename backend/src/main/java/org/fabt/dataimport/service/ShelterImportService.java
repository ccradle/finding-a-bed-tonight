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
            Map<String, Integer> capacityByType,
            // Issue #65: bedsOccupied imported alongside bedsTotal so onboarding
            // reflects current state, not 100% availability. Marcus Okafor: "Starting
            // every shelter at zero occupied misleads outreach workers on day one."
            Integer bedsOccupied
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
    // NOT @Transactional — TenantContext.callWithContext MUST wrap the DB operations.
    // @Transactional acquires the connection at method entry, but the DelegatingDataSource
    // reads TenantContext.getDvAccess() at connection-acquisition time via set_config().
    // If @Transactional runs before callWithContext sets dvAccess=true, the connection
    // gets dvAccess=false baked in → INSERT RETURNING * on DV shelters triggers RLS
    // SELECT policy rejection (SQL state 42501 INSUFFICIENT PRIVILEGE, mapped by Spring
    // to the misleading "bad SQL grammar" error). Individual shelterService.create/update
    // are @Transactional themselves. Portfolio lessons #60, #62, #79. War room 2026-04-13.
    public ImportResult importShelters(UUID tenantId, String importType, String filename,
                                       List<ShelterImportRow> rows) throws Exception {
        // Always dvAccess=true for admin imports. The import endpoint is
        // COC_ADMIN/PLATFORM_ADMIN only. Setting it here — BEFORE any connection
        // acquisition — ensures the DelegatingDataSource bakes the correct session
        // variable into every connection used within this scope.
        return TenantContext.callWithContext(tenantId, true, () -> {
            int created = 0;
            int updated = 0;
            int skipped = 0;
            int errorCount = 0;
            List<ImportError> errorDetails = new ArrayList<>();

            for (int i = 0; i < rows.size(); i++) {
                int rowNum = i + 1;
                ShelterImportRow row = sanitizeCoordinates(rows.get(i), rowNum);

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
                        // DV flag change detection (issue #65, Decision 6, Marcus Webb)
                        Shelter existingShelter = existing.get();
                        if (row.dvShelter() != null && row.dvShelter() != existingShelter.isDvShelter()) {
                            log.warn("Import row {}: DV flag change detected for '{}' — {} → {}. "
                                    + "This changes RLS visibility. Review for safety.",
                                    rowNum, row.name(),
                                    existingShelter.isDvShelter(), row.dvShelter());
                            errorDetails.add(new ImportError(rowNum, "dvShelter",
                                    "Safety notice: CSV has dvShelter=" + row.dvShelter()
                                    + " but existing shelter has dvShelter=" + existingShelter.isDvShelter()
                                    + " — DV flag is NOT changed by re-import. Update manually in the admin panel if needed."));
                        }

                        // Full replace: update existing shelter with all fields from import row
                        UpdateShelterRequest updateReq = buildUpdateRequest(row);
                        shelterService.update(existingShelter.getId(), updateReq);
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

    /**
     * Dry-run preview: validates rows and counts how many will be created vs. updated,
     * WITHOUT committing any changes. Used by the frontend to show
     * "Will update: N / Will create: M" before the user clicks Import.
     */
    public ImportResult previewImport(UUID tenantId, List<ShelterImportRow> rows) {
        // Always dvAccess=true — preview reads shelter data for dedup matching,
        // which includes DV shelters hidden by RLS without dvAccess.
        return TenantContext.callWithContext(tenantId, true, () -> {
            int willCreate = 0;
            int willUpdate = 0;
            int errorCount = 0;
            List<ImportError> errorDetails = new ArrayList<>();

            for (int i = 0; i < rows.size(); i++) {
                int rowNum = i + 1;
                ShelterImportRow row = sanitizeCoordinates(rows.get(i), rowNum);

                List<ImportError> rowErrors = validateRow(rowNum, row);
                if (!rowErrors.isEmpty()) {
                    errorDetails.addAll(rowErrors);
                    errorCount++;
                    continue;
                }

                Optional<Shelter> existing = shelterRepository.findByTenantIdAndNameAndAddressCity(
                        tenantId, row.name().trim(), row.addressCity().trim());

                if (existing.isPresent()) {
                    willUpdate++;
                    // DV flag change detection for preview
                    if (row.dvShelter() != null && row.dvShelter() != existing.get().isDvShelter()) {
                        errorDetails.add(new ImportError(rowNum, "dvShelter",
                                "Safety notice: CSV has dvShelter=" + row.dvShelter()
                                + " but existing shelter has dvShelter=" + existing.get().isDvShelter()
                                + " — DV flag is NOT changed by re-import. Update manually in the admin panel if needed."));
                    }
                } else {
                    willCreate++;
                }
            }

            return new ImportResult(willCreate, willUpdate, 0, errorCount, errorDetails);
        });
    }

    private List<ImportError> validateRow(int rowNum, ShelterImportRow row) {
        List<ImportError> errors = new ArrayList<>();

        if (row.name() == null || row.name().isBlank()) {
            errors.add(new ImportError(rowNum, "name", "Shelter name is required — please add a name for this shelter"));
        }
        if (row.addressCity() == null || row.addressCity().isBlank()) {
            errors.add(new ImportError(rowNum, "addressCity", "City is required — we need the city to identify unique shelters"));
        }

        // Field length validation (matches DB column sizes)
        validateLength(errors, rowNum, "name", row.name(), 255);
        validateLength(errors, rowNum, "addressStreet", row.addressStreet(), 500);
        validateLength(errors, rowNum, "addressCity", row.addressCity(), 255);
        validateLength(errors, rowNum, "addressState", row.addressState(), 50);
        validateLength(errors, rowNum, "addressZip", row.addressZip(), 10);
        validateLength(errors, rowNum, "phone", row.phone(), 50);

        // Validate population types if provided
        if (row.populationTypesServed() != null) {
            for (String popType : row.populationTypesServed()) {
                try {
                    PopulationType.valueOf(popType);
                } catch (IllegalArgumentException e) {
                    errors.add(new ImportError(rowNum, "populationTypesServed",
                            "'" + popType + "' is not a recognized population type — expected one of: "
                            + java.util.Arrays.toString(PopulationType.values())));
                }
            }
        }

        // Capacity conflict: bedsOccupied cannot exceed bedsTotal (issue #65).
        // The CSV has a single bedsTotal and bedsOccupied — not per population type.
        // If both are provided, validate the global constraint.
        if (row.capacityByType() != null && !row.capacityByType().isEmpty()) {
            // First entry holds bedsTotal (others are 0 per the adapter's first-type-gets-all strategy)
            int bedsTotal = row.capacityByType().values().iterator().next();
            if (bedsTotal < 0) {
                errors.add(new ImportError(rowNum, "bedsTotal",
                        "Total beds cannot be a negative number"));
            }
            if (row.bedsOccupied() != null) {
                if (row.bedsOccupied() > bedsTotal) {
                    errors.add(new ImportError(rowNum, "bedsOccupied",
                            "You listed " + row.bedsOccupied() + " occupied beds but only " + bedsTotal + " total beds — occupied can't be more than total"));
                }
                if (row.bedsOccupied() < 0) {
                    errors.add(new ImportError(rowNum, "bedsOccupied",
                            "Occupied beds cannot be a negative number"));
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
                            "'" + popType + "' is not a recognized population type for capacity — expected one of: "
                            + java.util.Arrays.toString(PopulationType.values())));
                }
            }
        }

        return errors;
    }

    private void validateLength(List<ImportError> errors, int rowNum, String field, String value, int maxLength) {
        if (value != null && value.length() > maxLength) {
            errors.add(new ImportError(rowNum, field,
                    field + " cannot exceed " + maxLength + " characters (was " + value.length() + ")"));
        }
    }

    /**
     * Sanitize coordinates: null out values outside valid ranges.
     * Lat: -90 to 90, Lng: -180 to 180. Logs a warning for invalid values.
     */
    private ShelterImportRow sanitizeCoordinates(ShelterImportRow row, int rowNum) {
        Double lat = row.latitude();
        Double lng = row.longitude();
        boolean changed = false;

        if (lat != null && (lat < -90 || lat > 90)) {
            log.warn("Import row {}: latitude {} out of range (-90..90), setting to null", rowNum, lat);
            lat = null;
            changed = true;
        }
        if (lng != null && (lng < -180 || lng > 180)) {
            log.warn("Import row {}: longitude {} out of range (-180..180), setting to null", rowNum, lng);
            lng = null;
            changed = true;
        }

        if (!changed) return row;

        return new ShelterImportRow(
                row.name(), row.addressStreet(), row.addressCity(), row.addressState(),
                row.addressZip(), row.phone(), lat, lng,
                row.dvShelter(), row.sobrietyRequired(), row.idRequired(), row.referralRequired(),
                row.petsAllowed(), row.wheelchairAccessible(), row.curfewTime(), row.maxStayDays(),
                row.populationTypesServed(), row.capacityByType(), row.bedsOccupied()
        );
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
                null, // dvShelter — imports don't set DV flag
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

        // Issue #65: the CSV has a single bedsOccupied for the whole shelter.
        // Assign it to the FIRST population type; others get 0. The coordinator
        // can refine per-type occupancy after import. This matches how HIC/PIT
        // reports handle shared-capacity shelters.
        int remainingOccupied = row.bedsOccupied() != null ? row.bedsOccupied() : 0;
        List<ShelterCapacityDto> capacities = new ArrayList<>();

        for (var entry : row.capacityByType().entrySet()) {
            int occupied = Math.min(remainingOccupied, entry.getValue());
            capacities.add(new ShelterCapacityDto(entry.getKey(), entry.getValue(), occupied));
            remainingOccupied -= occupied;
        }

        return capacities;
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
