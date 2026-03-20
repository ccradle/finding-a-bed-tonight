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
import org.fabt.shared.cache.CacheNames;
import org.fabt.shared.cache.CacheService;
import org.fabt.shared.event.DomainEvent;
import org.fabt.shared.event.EventBus;
import org.fabt.shared.web.TenantContext;
import org.fabt.shelter.domain.Shelter;
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
                    ba.getSnapshotTs(), ageSeconds,
                    DataFreshness.fromAgeSeconds(ageSeconds).name(),
                    ba.getUpdatedBy(), ba.getNotes()
            );
        }
    }

    private final BedAvailabilityRepository repository;
    private final ShelterService shelterService;
    private final CacheService cacheService;
    private final EventBus eventBus;

    public AvailabilityService(BedAvailabilityRepository repository,
                               ShelterService shelterService,
                               CacheService cacheService,
                               EventBus eventBus) {
        this.repository = repository;
        this.shelterService = shelterService;
        this.cacheService = cacheService;
        this.eventBus = eventBus;
    }

    @Transactional
    public AvailabilitySnapshot createSnapshot(UUID shelterId, String populationType,
                                                int bedsTotal, int bedsOccupied, int bedsOnHold,
                                                boolean acceptingNewGuests, String notes,
                                                String updatedBy) {
        UUID tenantId = TenantContext.getTenantId();

        // Verify shelter exists in tenant
        Shelter shelter = shelterService.findById(shelterId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Shelter not found: " + shelterId));

        // Get previous snapshot for event payload
        BedAvailability previous = repository.findPreviousByShelterId(shelterId, populationType);
        // Actually, we need the current latest BEFORE inserting the new one
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
        BedAvailability saved = repository.insert(ba);

        // Synchronous cache invalidation BEFORE returning 200 (Design rule D6)
        cacheService.evict(CacheNames.SHELTER_AVAILABILITY, shelterId.toString());
        cacheService.evict(CacheNames.SHELTER_PROFILE, shelterId.toString());
        cacheService.evict(CacheNames.SHELTER_LIST, tenantId.toString());

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
