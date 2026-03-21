package org.fabt.surge.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.fabt.availability.domain.BedAvailability;
import org.fabt.availability.repository.BedAvailabilityRepository;
import org.fabt.shared.event.DomainEvent;
import org.fabt.shared.event.EventBus;
import org.fabt.shared.web.TenantContext;
import org.fabt.shelter.service.ShelterService;
import org.fabt.surge.domain.SurgeEvent;
import org.fabt.surge.domain.SurgeEventStatus;
import org.fabt.surge.repository.SurgeEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SurgeEventService {

    private final SurgeEventRepository repository;
    private final ShelterService shelterService;
    private final BedAvailabilityRepository availabilityRepository;
    private final EventBus eventBus;

    public SurgeEventService(SurgeEventRepository repository,
                             ShelterService shelterService,
                             BedAvailabilityRepository availabilityRepository,
                             EventBus eventBus) {
        this.repository = repository;
        this.shelterService = shelterService;
        this.availabilityRepository = availabilityRepository;
        this.eventBus = eventBus;
    }

    @Transactional
    public SurgeEvent activate(String reason, String boundingBox, Instant scheduledEnd, UUID activatedBy) {
        UUID tenantId = TenantContext.getTenantId();

        // Only one active surge per tenant
        Optional<SurgeEvent> existing = repository.findActiveByTenantId(tenantId);
        if (existing.isPresent()) {
            throw new IllegalStateException("A surge event is already active");
        }

        SurgeEvent event = new SurgeEvent(tenantId, reason, boundingBox, activatedBy, scheduledEnd);
        SurgeEvent saved = repository.insert(event);

        // Compute affected shelter count and estimated overflow beds
        int affectedShelterCount = shelterService.findByTenantId().size();
        List<BedAvailability> latestAvail = availabilityRepository.findLatestByTenantId(tenantId);
        int estimatedOverflowBeds = latestAvail.stream()
                .mapToInt(ba -> ba.getOverflowBeds() != null ? ba.getOverflowBeds() : 0)
                .sum();

        // Publish surge.activated event
        Map<String, Object> payload = new HashMap<>();
        payload.put("surge_event_id", saved.getId().toString());
        payload.put("coc_id", tenantId.toString());
        payload.put("reason", reason);
        payload.put("bounding_box", boundingBox);
        payload.put("activated_by", activatedBy.toString());
        payload.put("activated_at", saved.getActivatedAt() != null ? saved.getActivatedAt().toString() : Instant.now().toString());
        payload.put("affected_shelter_count", affectedShelterCount);
        payload.put("estimated_overflow_beds", estimatedOverflowBeds > 0 ? estimatedOverflowBeds : null);

        eventBus.publish(new DomainEvent("surge.activated", tenantId, payload));

        return saved;
    }

    @Transactional
    public SurgeEvent deactivate(UUID surgeEventId, UUID deactivatedBy) {
        UUID tenantId = TenantContext.getTenantId();

        SurgeEvent event = repository.findByIdAndTenantId(surgeEventId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Surge event not found: " + surgeEventId));

        if (!event.isActive()) {
            throw new IllegalStateException("Surge event is not active");
        }

        int updated = repository.updateStatus(surgeEventId, SurgeEventStatus.DEACTIVATED, deactivatedBy);
        if (updated == 0) {
            throw new IllegalStateException("Surge event already deactivated");
        }

        // Publish surge.deactivated event
        Map<String, Object> payload = new HashMap<>();
        payload.put("surge_event_id", surgeEventId.toString());
        payload.put("coc_id", tenantId.toString());
        payload.put("deactivated_at", Instant.now().toString());

        eventBus.publish(new DomainEvent("surge.deactivated", tenantId, payload));

        event.setStatus(SurgeEventStatus.DEACTIVATED);
        event.setDeactivatedAt(Instant.now());
        event.setDeactivatedBy(deactivatedBy);
        return event;
    }

    @Transactional
    public void expireSurge(UUID surgeEventId) {
        repository.updateStatus(surgeEventId, SurgeEventStatus.EXPIRED, null);

        // Need tenant context for event publishing
        SurgeEvent event = repository.findByIdAndTenantId(surgeEventId, null).orElse(null);
        if (event != null) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("surge_event_id", surgeEventId.toString());
            payload.put("coc_id", event.getTenantId().toString());
            payload.put("deactivated_at", Instant.now().toString());
            eventBus.publish(new DomainEvent("surge.deactivated", event.getTenantId(), payload));
        }
    }

    @Transactional(readOnly = true)
    public Optional<SurgeEvent> getActive() {
        UUID tenantId = TenantContext.getTenantId();
        return repository.findActiveByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    public List<SurgeEvent> list() {
        UUID tenantId = TenantContext.getTenantId();
        return repository.findByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    public Optional<SurgeEvent> findById(UUID id) {
        UUID tenantId = TenantContext.getTenantId();
        return repository.findByIdAndTenantId(id, tenantId);
    }
}
