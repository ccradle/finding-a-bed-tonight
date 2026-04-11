package org.fabt.reservation.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.fabt.availability.domain.BedAvailability;
import org.fabt.availability.repository.BedAvailabilityRepository;
import org.fabt.availability.service.AvailabilityInvariantViolation;
import org.fabt.availability.service.AvailabilityService;
import org.fabt.observability.ObservabilityMetrics;
import org.fabt.reservation.domain.Reservation;
import org.fabt.reservation.domain.ReservationStatus;
import org.fabt.reservation.repository.ReservationRepository;
import org.fabt.shared.api.HeldReservationCleaner;
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
public class ReservationService implements HeldReservationCleaner {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private static final int DEFAULT_HOLD_DURATION_MINUTES = 90;

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
        return createReservation(shelterId, populationType, notes, userId, null);
    }

    @Transactional
    public Reservation createReservation(UUID shelterId, String populationType, String notes, UUID userId, String idempotencyKey) {
        UUID tenantId = TenantContext.getTenantId();

        // Idempotency check: if key provided, return existing active hold instead of creating duplicate
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<Reservation> existing = reservationRepository.findActiveByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotent hold replay: returning existing reservation {} for key {}",
                        existing.get().getId(), idempotencyKey);
                Reservation existingRes = existing.get();
                existingRes.setIdempotentMatch(true);
                return existingRes;
            }
        }

        // Acquire advisory lock to prevent concurrent double-hold (TC-3.2).
        // Only one transaction can hold this lock per shelter+populationType at a time.
        reservationRepository.acquireAdvisoryLock(shelterId, populationType);

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
        int overflow = current != null && current.getOverflowBeds() != null
                ? current.getOverflowBeds() : 0;
        int effectiveAvailable = bedsAvailable + overflow;
        if (effectiveAvailable <= 0) {
            throw new IllegalStateException("No beds available for population type: " + populationType);
        }

        // Calculate expiry
        int holdMinutes = getHoldDurationMinutes(tenantId);
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(holdMinutes));

        // Create reservation
        Reservation reservation = new Reservation(shelterId, tenantId, populationType, userId, expiresAt, notes);
        reservation.setIdempotencyKey(idempotencyKey);
        Reservation saved = reservationRepository.insert(reservation);

        // Recompute beds_on_hold from the source of truth (the reservation table, which now
        // includes the just-inserted row). The recompute path replaces the previous delta-math
        // approach and is the single write path for beds_on_hold (Issue #102 RCA).
        // INV-5 enforcement happens inside createSnapshot — if the truth would exceed capacity,
        // an AvailabilityInvariantViolation is thrown. We translate it to the existing
        // IllegalStateException for backward compat with callers/tests, and explicitly mark
        // the reservation CANCELLED before rethrowing (the @Transactional boundary will roll
        // both back; the explicit cancel mirrors the prior code's defensive shape).
        try {
            applyRecompute(shelterId, populationType, 0, "reservation:create", "system:reservation");
        } catch (AvailabilityInvariantViolation e) {
            reservationRepository.updateStatus(saved.getId(), ReservationStatus.CANCELLED);
            throw new IllegalStateException("No beds available for population type: " + populationType);
        }

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

        // Reservation is now CONFIRMED — the active HELD count is one less. Recompute
        // beds_on_hold from the reservation table and apply +1 to beds_occupied. The hold
        // decrement is implicit in the recompute (no delta math); the occupied increment is
        // explicit because beds_occupied has no source-of-truth count — it is accumulated
        // state, only mutated through reservation confirms, surge updates, and coordinator
        // PATCH calls.
        applyRecompute(reservation.getShelterId(), reservation.getPopulationType(),
                +1, "reservation:confirm", "system:reservation");

        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setConfirmedAt(Instant.now());
        publishEvent("reservation.confirmed", tenantId, reservation);
        metrics.reservationCounter("CONFIRMED").increment();

        return reservation;
    }

    /**
     * Create an "offline hold" — a coordinator-driven manual hold for off-system
     * bed allocations such as phone reservations or expected guests. Inserts a real
     * {@code reservation} row through the standard lifecycle so all downstream
     * invariants apply (recompute, auto-expiry, audit).
     *
     * <p>Idempotency is provided by a derived key
     * {@code (userId, shelterId, populationType, "manual-hold", current_minute)}
     * so that accidental duplicate clicks within the same minute return the
     * existing hold instead of creating a duplicate. This protection is local to
     * the manual-hold path and does not interfere with the X-Idempotency-Key
     * header used by the regular reservation create flow.</p>
     */
    @Transactional
    public Reservation createManualHold(UUID shelterId, String populationType, UUID userId, String reason) {
        // Derive an idempotency key from (user, shelter, population, "manual-hold", current_minute)
        // so duplicate clicks within the same minute return the existing hold instead of stacking.
        // The reservation.idempotency_key column is VARCHAR(36), so we hash the derived material
        // into a deterministic UUID via UUID#nameUUIDFromBytes (RFC 4122 v3, MD5-based — collision
        // resistance is sufficient for a same-minute idempotency key).
        long minuteBucket = Instant.now().getEpochSecond() / 60;
        String derivedSource = "manual-hold:" + userId + ":" + shelterId + ":" + populationType
                + ":" + minuteBucket;
        String idempotencyKey = UUID.nameUUIDFromBytes(
                derivedSource.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();

        String notes;
        if (reason != null && !reason.isBlank()) {
            notes = "Manual offline hold: " + reason;
        } else {
            notes = "Manual offline hold";
        }

        // Delegate to the standard create path. It handles the advisory lock,
        // capacity check, the source-of-truth recompute, and idempotent replay
        // via the existing X-Idempotency-Key plumbing in the repository.
        return createReservation(shelterId, populationType, notes, userId, idempotencyKey);
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

        // Reservation is now CANCELLED — recompute beds_on_hold from the reservation table.
        applyRecompute(reservation.getShelterId(), reservation.getPopulationType(),
                0, "reservation:cancel", "system:reservation");

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
        TenantContext.runWithContext(reservation.getTenantId(), false, () -> {
            // Reservation is now EXPIRED — recompute beds_on_hold from the reservation table.
            applyRecompute(reservation.getShelterId(), reservation.getPopulationType(),
                    0, "reservation:expire", "system:reservation");
            publishEvent("reservation.expired", reservation.getTenantId(), reservation);
            metrics.reservationCounter("EXPIRED").increment();
        });

        log.info("Reservation {} expired for shelter {} / {}",
                reservationId, reservation.getShelterId(), reservation.getPopulationType());
    }

    @Override
    @Transactional
    public int cancelAllHeldReservations() {
        List<Reservation> held = reservationRepository.findAllHeld();
        int cancelled = 0;
        for (Reservation reservation : held) {
            try {
                expireReservation(reservation.getId());
                cancelled++;
            } catch (Exception e) {
                log.debug("Could not expire reservation {} during cleanup: {}",
                        reservation.getId(), e.getMessage());
            }
        }
        return cancelled;
    }

    @Transactional(readOnly = true)
    public List<Reservation> getActiveReservations(UUID userId) {
        UUID tenantId = TenantContext.getTenantId();
        return reservationRepository.findActiveByUserId(tenantId, userId);
    }

    /**
     * Count active HELD reservations for a shelter and population type.
     * Used by AvailabilityController to enforce hold protection — coordinators
     * cannot reduce beds_on_hold below this count.
     */
    @Transactional(readOnly = true)
    public int countActiveHolds(UUID shelterId, String populationType) {
        return reservationRepository.countActiveByShelterId(shelterId, populationType);
    }

    /**
     * Single write path for {@code bed_availability.beds_on_hold} (Issue #102 RCA).
     *
     * <p>Reads the actual count of {@code HELD} reservations from the source-of-truth
     * {@code reservation} table and writes a fresh availability snapshot, preserving
     * {@code beds_total}, {@code beds_occupied}, {@code accepting_new_guests}, and
     * {@code overflow_beds} from the latest snapshot. No delta math on
     * {@code beds_on_hold} ever — the snapshot value equals the count, by construction.</p>
     *
     * <p>Public so the bed-holds reconciliation tasklet and any future call site can
     * reach this method through the Spring proxy boundary. Internal callers in this
     * service should call {@link #applyRecompute} directly to avoid self-invocation
     * proxy bypass.</p>
     *
     * <p>Throws {@link AvailabilityInvariantViolation} (via {@code createSnapshot})
     * if the actual count would violate INV-5
     * ({@code beds_occupied + beds_on_hold &lt;= beds_total + overflow_beds}). The
     * caller's {@code @Transactional} boundary will roll back any reservation insert
     * that triggered this state.</p>
     */
    @Transactional
    public void recomputeBedsOnHold(UUID shelterId, String populationType,
                                    String notes, String updatedBy) {
        applyRecompute(shelterId, populationType, 0, notes, updatedBy);
    }

    /**
     * Recompute beds_on_hold from the reservation table and apply an explicit delta to
     * beds_occupied. The hold value is sourced from {@code countActiveByShelterId} (truth)
     * — never from delta math against the cached snapshot. The occupied delta is the only
     * way to mutate {@code beds_occupied}, which has no source-of-truth count: it is
     * accumulated state, only changed by reservation confirms, surge updates, and
     * coordinator PATCH calls.
     *
     * <p>Private and called from internal lifecycle methods (createReservation,
     * confirmReservation, cancelReservation, expireReservation) so that Spring's
     * self-invocation rules do not bypass the surrounding transactional boundary.</p>
     */
    private void applyRecompute(UUID shelterId, String populationType,
                                int occupiedDelta, String notes, String updatedBy) {
        int actualHeldCount = reservationRepository.countActiveByShelterId(shelterId, populationType);

        List<BedAvailability> latest = availabilityRepository.findLatestByShelterId(shelterId);
        BedAvailability current = latest.stream()
                .filter(ba -> ba.getPopulationType().equals(populationType))
                .findFirst()
                .orElse(null);

        int bedsTotal = current != null ? current.getBedsTotal() : 0;
        int bedsOccupied = (current != null ? current.getBedsOccupied() : 0) + occupiedDelta;
        boolean accepting = current != null ? current.isAcceptingNewGuests() : true;
        int currentOverflow = current != null && current.getOverflowBeds() != null
                ? current.getOverflowBeds() : 0;

        availabilityService.createSnapshot(
                shelterId, populationType,
                bedsTotal, bedsOccupied, actualHeldCount,
                accepting, notes, updatedBy,
                currentOverflow
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
                        } catch (tools.jackson.core.JacksonException e) {
                            log.warn("Failed to read hold duration from tenant config, using default: {}", e.getMessage());
                            return DEFAULT_HOLD_DURATION_MINUTES;
                        }
                    })
                    .orElse(DEFAULT_HOLD_DURATION_MINUTES);
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("Failed to read hold duration from tenant config, using default: {}", e.getMessage());
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
