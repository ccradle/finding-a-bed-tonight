package org.fabt.availability.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.availability.domain.BedAvailability;
import org.fabt.availability.repository.BedAvailabilityRepository;
import org.fabt.observability.DataFreshness;
import org.fabt.observability.ObservabilityMetrics;
import org.fabt.shared.cache.CacheNames;
import org.fabt.shared.cache.TenantScopedCacheService;
import org.fabt.shared.event.DomainEvent;
import org.fabt.shared.event.EventBus;
import org.fabt.shared.web.TenantContext;
import org.fabt.shelter.domain.Shelter;
import org.fabt.shelter.domain.ShelterConstraints;
import org.fabt.shelter.repository.ShelterConstraintsRepository;
import org.fabt.shelter.service.ShelterService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AvailabilityService {

    public record AvailabilitySnapshot(
            UUID id,
            UUID shelterId,
            String populationType,
            int bedsTotal,
            int bedsOccupied,
            int bedsOnHold,
            int bedsAvailable,
            boolean acceptingNewGuests,
            int overflowBeds,
            Instant snapshotTs,
            long dataAgeSeconds,
            String dataFreshness,
            String updatedBy,
            String notes
    ) {
        public static AvailabilitySnapshot from(BedAvailability ba) {
            long ageSeconds = ba.getSnapshotTs() != null
                    ? Duration.between(ba.getSnapshotTs(), Instant.now()).getSeconds()
                    : 0;
            return new AvailabilitySnapshot(
                    ba.getId(), ba.getShelterId(), ba.getPopulationType(),
                    ba.getBedsTotal(), ba.getBedsOccupied(), ba.getBedsOnHold(),
                    ba.getBedsAvailable(), ba.isAcceptingNewGuests(),
                    ba.getOverflowBeds() != null ? ba.getOverflowBeds() : 0,
                    ba.getSnapshotTs(), ageSeconds,
                    DataFreshness.fromAgeSeconds(ageSeconds).name(),
                    ba.getUpdatedBy(), ba.getNotes()
            );
        }
    }

    private final BedAvailabilityRepository repository;
    private final ShelterService shelterService;
    private final ShelterConstraintsRepository constraintsRepository;
    private final TenantScopedCacheService cacheService;
    private final EventBus eventBus;
    private final ObservabilityMetrics metrics;

    public AvailabilityService(BedAvailabilityRepository repository,
                               ShelterService shelterService,
                               ShelterConstraintsRepository constraintsRepository,
                               TenantScopedCacheService cacheService,
                               EventBus eventBus,
                               ObservabilityMetrics metrics) {
        this.repository = repository;
        this.shelterService = shelterService;
        this.constraintsRepository = constraintsRepository;
        this.cacheService = cacheService;
        this.eventBus = eventBus;
        this.metrics = metrics;
    }

    /**
     * Create an availability snapshot. Controllers should use
     * {@link AvailabilityRetryService#createSnapshotWithRetry} for automatic retry on transient failures.
     */
    @Transactional
    public AvailabilitySnapshot createSnapshot(UUID shelterId, String populationType,
                                                int bedsTotal, int bedsOccupied, int bedsOnHold,
                                                boolean acceptingNewGuests, String notes,
                                                String updatedBy) {
        return createSnapshot(shelterId, populationType, bedsTotal, bedsOccupied, bedsOnHold,
                acceptingNewGuests, notes, updatedBy, 0);
    }

    @Transactional
    public AvailabilitySnapshot createSnapshot(UUID shelterId, String populationType,
                                                int bedsTotal, int bedsOccupied, int bedsOnHold,
                                                boolean acceptingNewGuests, String notes,
                                                String updatedBy, int overflowBeds) {
        io.micrometer.core.instrument.Timer.Sample timerSample = io.micrometer.core.instrument.Timer.start();
        UUID tenantId = TenantContext.getTenantId();

        // Verify shelter exists in tenant
        Shelter shelter = shelterService.findById(shelterId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Shelter not found: " + shelterId));

        // Validate population type against shelter constraints (#34)
        // NULL or empty constraints = permissive (accept any population type)
        ShelterConstraints constraints = constraintsRepository.findById(shelterId).orElse(null);
        if (constraints != null && constraints.getPopulationTypesServed() != null
                && constraints.getPopulationTypesServed().length > 0) {
            boolean matched = java.util.Arrays.asList(constraints.getPopulationTypesServed())
                    .contains(populationType);
            if (!matched) {
                throw new AvailabilityInvariantViolation(
                        "Population type '" + populationType + "' is not served by this shelter. "
                        + "Accepted types: " + String.join(", ", constraints.getPopulationTypesServed()));
            }
        }

        // Enforce bed availability invariants (QA briefing INV-1 through INV-5)
        if (bedsTotal < 0) {
            throw new AvailabilityInvariantViolation("beds_total cannot be negative (INV-4)");
        }
        if (bedsOccupied < 0) {
            throw new AvailabilityInvariantViolation("beds_occupied cannot be negative");
        }
        if (bedsOnHold < 0) {
            throw new AvailabilityInvariantViolation("beds_on_hold cannot be negative");
        }
        if (bedsOccupied > bedsTotal) {
            throw new AvailabilityInvariantViolation(
                    "beds_occupied (" + bedsOccupied + ") cannot exceed beds_total (" + bedsTotal + ") (INV-2)");
        }
        if (bedsOccupied + bedsOnHold > bedsTotal + overflowBeds) {
            throw new AvailabilityInvariantViolation(
                    "beds_occupied (" + bedsOccupied + ") + beds_on_hold (" + bedsOnHold
                            + ") cannot exceed beds_total (" + bedsTotal + ") + overflow (" + overflowBeds + ") (INV-5)");
        }

        // Get current latest BEFORE inserting the new one (for event payload delta)
        List<BedAvailability> currentLatest = repository.findLatestByShelterId(shelterId);
        BedAvailability previousForType = currentLatest.stream()
                .filter(ba -> ba.getPopulationType().equals(populationType))
                .findFirst()
                .orElse(null);

        // Insert new snapshot (append-only)
        BedAvailability ba = new BedAvailability(
                shelterId, tenantId, populationType,
                bedsTotal, bedsOccupied, bedsOnHold,
                acceptingNewGuests, updatedBy, notes
        );
        ba.setOverflowBeds(overflowBeds);
        BedAvailability saved = repository.insert(ba);

        // Synchronous cache invalidation BEFORE returning 200 (Design rule D6).
        // Per D-4.b-2: caller-side tenant prefix stripped; wrapper re-prefixes.
        // SHELTER_AVAILABILITY is the singleton-per-tenant snapshot key written
        // by BedSearchService.doSearch — match its new "latest" logical key.
        cacheService.evict(CacheNames.SHELTER_AVAILABILITY, "latest");
        // Evict-only today — see D-4.b-3 orphan-cache posture (design-c-cache-isolation.md).
        // SHELTER_PROFILE has no production put-site; keep the evict as defensive
        // posture for future put paths + to document the intended invalidation
        // boundary. Caller-side key stays as the shelter UUID (logical id); the
        // wrapper adds the tenant prefix.
        cacheService.evict(CacheNames.SHELTER_PROFILE, shelterId.toString());
        // Evict-only today — see D-4.b-3 orphan-cache posture (design-c-cache-isolation.md).
        cacheService.evict(CacheNames.SHELTER_LIST, "latest");

        // Publish availability.updated event
        int bedsAvailable = bedsTotal - bedsOccupied - bedsOnHold;
        Integer bedsAvailablePrevious = previousForType != null
                ? previousForType.getBedsAvailable()
                : null;

        Map<String, Object> payload = new HashMap<>();
        payload.put("shelter_id", shelterId.toString());
        payload.put("tenant_id", tenantId.toString());
        payload.put("coc_id", tenantId.toString());
        payload.put("shelter_name", shelter.getName());
        payload.put("population_type", populationType);
        payload.put("beds_available", bedsAvailable);
        payload.put("beds_available_previous", bedsAvailablePrevious);
        payload.put("snapshot_ts", saved.getSnapshotTs() != null ? saved.getSnapshotTs().toString() : Instant.now().toString());
        payload.put("data_age_seconds", 0);

        eventBus.publish(new DomainEvent("availability.updated", tenantId, payload));

        metrics.availabilityUpdateCounter(updatedBy).increment();
        timerSample.stop(metrics.availabilityUpdateTimer());

        return AvailabilitySnapshot.from(saved);
    }

    @Transactional(readOnly = true)
    public List<AvailabilitySnapshot> getLatestByShelterId(UUID shelterId) {
        return repository.findLatestByShelterId(shelterId).stream()
                .map(AvailabilitySnapshot::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AvailabilitySnapshot> getLatestByTenantId(UUID tenantId) {
        return repository.findLatestByTenantId(tenantId).stream()
                .map(AvailabilitySnapshot::from)
                .toList();
    }
}
