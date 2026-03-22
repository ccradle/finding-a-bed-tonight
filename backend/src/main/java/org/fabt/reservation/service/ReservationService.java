package org.fabt.reservation.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fabt.availability.domain.BedAvailability;
import org.fabt.availability.repository.BedAvailabilityRepository;
import org.fabt.availability.service.AvailabilityService;
import org.fabt.observability.ObservabilityMetrics;
import org.fabt.reservation.domain.Reservation;
import org.fabt.reservation.domain.ReservationStatus;
import org.fabt.reservation.repository.ReservationRepository;
import org.fabt.shared.event.DomainEvent;
import org.fabt.shared.event.EventBus;
import org.fabt.shared.web.TenantContext;
import org.fabt.shelter.service.ShelterService;
import org.fabt.tenant.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private static final int DEFAULT_HOLD_DURATION_MINUTES = 45;

    private final ReservationRepository reservationRepository;
    private final BedAvailabilityRepository availabilityRepository;
    private final AvailabilityService availabilityService;
    private final ShelterService shelterService;
    private final TenantService tenantService;
    private final EventBus eventBus;
    private final ObjectMapper objectMapper;
    private final ObservabilityMetrics metrics;

    public ReservationService(ReservationRepository reservationRepository,
                              BedAvailabilityRepository availabilityRepository,
                              AvailabilityService availabilityService,
                              ShelterService shelterService,
                              TenantService tenantService,
                              EventBus eventBus,
                              ObjectMapper objectMapper,
                              ObservabilityMetrics metrics) {
        this.reservationRepository = reservationRepository;
        this.availabilityRepository = availabilityRepository;
        this.availabilityService = availabilityService;
        this.shelterService = shelterService;
        this.tenantService = tenantService;
        this.eventBus = eventBus;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Transactional
    public Reservation createReservation(UUID shelterId, String populationType, String notes, UUID userId) {
        UUID tenantId = TenantContext.getTenantId();

        // Verify shelter exists
        shelterService.findById(shelterId)
                .orElseThrow(() -> new NoSuchElementException("Shelter not found: " + shelterId));

        // Check current availability
        List<BedAvailability> latest = availabilityRepository.findLatestByShelterId(shelterId);
        BedAvailability current = latest.stream()
                .filter(ba -> ba.getPopulationType().equals(populationType))
                .findFirst()
                .orElse(null);

        int bedsAvailable = current != null ? current.getBedsAvailable() : 0;
        if (bedsAvailable <= 0) {
            throw new IllegalStateException("No beds available for population type: " + populationType);
        }

        // Calculate expiry
        int holdMinutes = getHoldDurationMinutes(tenantId);
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(holdMinutes));

        // Create reservation
        Reservation reservation = new Reservation(shelterId, tenantId, populationType, userId, expiresAt, notes);
        Reservation saved = reservationRepository.insert(reservation);

        // Create availability snapshot with beds_on_hold incremented
        int newBedsOnHold = (current != null ? current.getBedsOnHold() : 0) + 1;
        availabilityService.createSnapshot(
                shelterId, populationType,
                current != null ? current.getBedsTotal() : 0,
                current != null ? current.getBedsOccupied() : 0,
                newBedsOnHold,
                current != null ? current.isAcceptingNewGuests() : true,
                "reservation:create",
                "system:reservation"
        );

        // Publish event
        publishEvent("reservation.created", tenantId, saved);
        metrics.reservationCounter("CREATED").increment();

        return saved;
    }

    @Transactional
    public Reservation confirmReservation(UUID reservationId, UUID userId) {
        UUID tenantId = TenantContext.getTenantId();

        Reservation reservation = reservationRepository.findByIdAndTenantId(reservationId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Reservation not found: " + reservationId));

        if (!reservation.isHeld()) {
            throw new IllegalStateException("Reservation has " + reservation.getStatus().name().toLowerCase());
        }

        // Verify ownership (creator or admin)
        if (!reservation.getUserId().equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Only the reservation creator can confirm this reservation");
        }

        int updated = reservationRepository.updateStatus(reservationId, ReservationStatus.CONFIRMED);
        if (updated == 0) {
            throw new IllegalStateException("Reservation already transitioned");
        }

        // Create availability snapshot: beds_on_hold -1, beds_occupied +1
        adjustAvailability(reservation, -1, +1, "reservation:confirm");

        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setConfirmedAt(Instant.now());
        publishEvent("reservation.confirmed", tenantId, reservation);
        metrics.reservationCounter("CONFIRMED").increment();

        return reservation;
    }

    @Transactional
    public Reservation cancelReservation(UUID reservationId, UUID userId) {
        UUID tenantId = TenantContext.getTenantId();

        Reservation reservation = reservationRepository.findByIdAndTenantId(reservationId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Reservation not found: " + reservationId));

        if (!reservation.isHeld()) {
            throw new IllegalStateException("Reservation has " + reservation.getStatus().name().toLowerCase());
        }

        if (!reservation.getUserId().equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Only the reservation creator can cancel this reservation");
        }

        int updated = reservationRepository.updateStatus(reservationId, ReservationStatus.CANCELLED);
        if (updated == 0) {
            throw new IllegalStateException("Reservation already transitioned");
        }

        // Create availability snapshot: beds_on_hold -1
        adjustAvailability(reservation, -1, 0, "reservation:cancel");

        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setCancelledAt(Instant.now());
        publishEvent("reservation.cancelled", tenantId, reservation);
        metrics.reservationCounter("CANCELLED").increment();

        return reservation;
    }

    @Transactional
    public void expireReservation(UUID reservationId) {
        // Expiry runs as a system process — look up without tenant filter
        Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null || !reservation.isHeld()) {
            return; // Already processed or doesn't exist
        }

        int updated = reservationRepository.updateStatus(reservationId, ReservationStatus.EXPIRED);
        if (updated == 0) {
            return; // Already transitioned — idempotent
        }

        // Set tenant context for downstream calls
        TenantContext.setTenantId(reservation.getTenantId());
        try {
            adjustAvailability(reservation, -1, 0, "reservation:expire");
            publishEvent("reservation.expired", reservation.getTenantId(), reservation);
            metrics.reservationCounter("EXPIRED").increment();
        } finally {
            TenantContext.clear();
        }

        log.info("Reservation {} expired for shelter {} / {}",
                reservationId, reservation.getShelterId(), reservation.getPopulationType());
    }

    @Transactional(readOnly = true)
    public List<Reservation> getActiveReservations(UUID userId) {
        UUID tenantId = TenantContext.getTenantId();
        return reservationRepository.findActiveByUserId(tenantId, userId);
    }

    private void adjustAvailability(Reservation reservation, int holdDelta, int occupiedDelta, String actor) {
        List<BedAvailability> latest = availabilityRepository.findLatestByShelterId(reservation.getShelterId());
        BedAvailability current = latest.stream()
                .filter(ba -> ba.getPopulationType().equals(reservation.getPopulationType()))
                .findFirst()
                .orElse(null);

        int bedsTotal = current != null ? current.getBedsTotal() : 0;
        int bedsOccupied = (current != null ? current.getBedsOccupied() : 0) + occupiedDelta;
        int bedsOnHold = Math.max(0, (current != null ? current.getBedsOnHold() : 0) + holdDelta);
        boolean accepting = current != null ? current.isAcceptingNewGuests() : true;

        availabilityService.createSnapshot(
                reservation.getShelterId(), reservation.getPopulationType(),
                bedsTotal, bedsOccupied, bedsOnHold, accepting, actor, "system:reservation"
        );
    }

    private int getHoldDurationMinutes(UUID tenantId) {
        try {
            return tenantService.findById(tenantId)
                    .filter(t -> t.getConfig() != null && t.getConfig().value() != null)
                    .map(t -> {
                        try {
                            JsonNode node = objectMapper.readTree(t.getConfig().value());
                            JsonNode holdDuration = node.get("hold_duration_minutes");
                            return holdDuration != null ? holdDuration.asInt(DEFAULT_HOLD_DURATION_MINUTES) : DEFAULT_HOLD_DURATION_MINUTES;
                        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                            return DEFAULT_HOLD_DURATION_MINUTES;
                        }
                    })
                    .orElse(DEFAULT_HOLD_DURATION_MINUTES);
        } catch (org.springframework.dao.DataAccessException e) {
            return DEFAULT_HOLD_DURATION_MINUTES;
        }
    }

    private void publishEvent(String eventType, UUID tenantId, Reservation reservation) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("reservation_id", reservation.getId() != null ? reservation.getId().toString() : null);
        payload.put("shelter_id", reservation.getShelterId().toString());
        payload.put("tenant_id", tenantId.toString());
        payload.put("population_type", reservation.getPopulationType());
        payload.put("user_id", reservation.getUserId().toString());
        payload.put("status", reservation.getStatus().name());
        if (reservation.getExpiresAt() != null) {
            payload.put("expires_at", reservation.getExpiresAt().toString());
        }
        eventBus.publish(new DomainEvent(eventType, tenantId, payload));
    }
}
