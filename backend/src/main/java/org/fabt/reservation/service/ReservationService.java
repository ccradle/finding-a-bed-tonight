package org.fabt.reservation.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.audit.AuditEventType;
import org.fabt.shared.event.DomainEvent;
import org.fabt.shared.event.EventBus;
import org.fabt.shared.web.TenantContext;
import org.fabt.shelter.service.ShelterService;
import org.fabt.tenant.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.fabt.shared.security.TenantScopedByConstruction;
import org.fabt.shared.security.TenantUnscoped;
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
    /**
     * v0.55 §13.A — used to emit the three new RESERVATION_PII_* audit
     * events (write-side at hold creation, throttled read-side at
     * decryption, purge-side at scheduled erasure). Spring's standard
     * publisher is the same path TENANT_CONFIG_UPDATED already uses
     * (ReservationConfigController); audit listeners pick the record up
     * via @EventListener(AuditEventRecord.class) → AuditEventService.
     */
    private final ApplicationEventPublisher auditEventPublisher;

    /**
     * v0.55 §13.A.3 — read-side audit throttle. Key format
     * "userId:shelterId:epochHour" — at most one
     * {@code RESERVATION_PII_DECRYPTED_ON_READ} audit row per coordinator
     * × shelter × hour, regardless of how many reservations were read in
     * that hour. Without this throttle, a coordinator polling their
     * dashboard would generate one audit row per visible hold per
     * refresh — the meaningful read-activity signal is "first visit per
     * hour", not per-row volume.
     *
     * <p>Tenant-scoped by construction: the key includes the user's
     * userId (tied to a single tenant via app_user.tenant_id) and a
     * shelterId resolved through tenant-scoped JOINs. RLS prevents a
     * cross-tenant key collision (a userId from tenant A cannot read a
     * shelterId from tenant B), so the cache cannot leak across tenants.
     * 90-minute TTL bounds memory while comfortably outliving the 60-min
     * throttle window.
     */
    @TenantScopedByConstruction(
        "Cache<String, Boolean> keyed on \"userId:shelterId:epochHour\". userId resolves to a single tenant via app_user.tenant_id; shelterId is read under tenant-scoped JOINs so cross-tenant keys cannot occur. v0.55 §13.A.3 audit-throttle.")
    private final Cache<String, Boolean> piiReadAuditThrottle = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(java.time.Duration.ofMinutes(90))
            .build();

    public ReservationService(ReservationRepository reservationRepository,
                              BedAvailabilityRepository availabilityRepository,
                              AvailabilityService availabilityService,
                              ShelterService shelterService,
                              TenantService tenantService,
                              EventBus eventBus,
                              ObjectMapper objectMapper,
                              ObservabilityMetrics metrics,
                              ApplicationEventPublisher auditEventPublisher) {
        this.reservationRepository = reservationRepository;
        this.availabilityRepository = availabilityRepository;
        this.availabilityService = availabilityService;
        this.shelterService = shelterService;
        this.tenantService = tenantService;
        this.eventBus = eventBus;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.auditEventPublisher = auditEventPublisher;
    }

    @Transactional
    public Reservation createReservation(UUID shelterId, String populationType, String notes, UUID userId) {
        return createReservation(shelterId, populationType, notes, userId, null);
    }

    @Transactional
    public Reservation createReservation(UUID shelterId, String populationType, String notes, UUID userId, String idempotencyKey) {
        return createReservation(shelterId, populationType, notes, userId, idempotencyKey, null, null, null);
    }

    /**
     * Hold-attribution-aware overload (transitional-reentry-support task 4.4,
     * slice 2C). Adds the third-party navigator hold attribution fields
     * (heldForClientName, heldForClientDob, holdNotes). All three optional —
     * null means "not provided" and the resulting Reservation row leaves the
     * corresponding {@code _encrypted} column NULL.
     *
     * <p>Validation runs on PLAINTEXT before encryption (per task 4.4
     * wording — encrypting an invalid value would just trade a 400 for a
     * 500 + ciphertext that fails decrypt asymmetrically). Caller is
     * responsible for surface-level validation (Bean Validation on the
     * request DTO catches null/blank/length); this method enforces the
     * business invariants Bean Validation can't:
     * <ul>
     *   <li>{@code heldForClientDob > 1900-01-01} — Bean Validation has
     *       {@code @Past} but no native lower bound; without this guard,
     *       a navigator typing "1850" would persist (e.g., a typo for
     *       "1985"). 1900-01-01 is the conservative real-people lower
     *       bound the spec resolved to.</li>
     * </ul>
     */
    @Transactional
    public Reservation createReservation(UUID shelterId, String populationType, String notes, UUID userId,
                                          String idempotencyKey,
                                          String heldForClientName, java.time.LocalDate heldForClientDob,
                                          String holdNotes) {
        // Plaintext validation — runs BEFORE encryption per task 4.4.
        validateHoldClientDob(heldForClientDob);
        return doCreateReservation(shelterId, populationType, notes, userId, idempotencyKey,
            heldForClientName, heldForClientDob, holdNotes);
    }

    /**
     * Hold-attribution DOB business invariant: must be on or after 1900-01-01.
     * Bean Validation's {@code @Past} on the DTO catches future dates;
     * this method catches the silently-invalid "1850s" typo case.
     *
     * <p>v0.55 §13.B (BLOCKER I-2): the exception message intentionally
     * does NOT include the user-supplied {@code dob} value. Including it
     * was a v0.54 PII-leak risk — the supplied DOB flows into the 400
     * response body, JSON access logs (Logback {@code %msg}), and any
     * exception forwarder. A typo of a real DOB is still a real DOB. The
     * error message names the constraint, not the violator; callers can
     * surface their own user-friendly retry copy in the UI.
     */
    static void validateHoldClientDob(java.time.LocalDate dob) {
        if (dob == null) return;
        java.time.LocalDate floor = java.time.LocalDate.of(1900, 1, 1);
        if (dob.isBefore(floor)) {
            throw new IllegalArgumentException(
                "heldForClientDob must be on or after 1900-01-01");
        }
    }

    @Transactional
    private Reservation doCreateReservation(UUID shelterId, String populationType, String notes, UUID userId,
                                             String idempotencyKey,
                                             String heldForClientName, java.time.LocalDate heldForClientDob,
                                             String holdNotes) {
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

        // Verify shelter exists and is active
        var shelter = shelterService.findById(shelterId)
                .orElseThrow(() -> new NoSuchElementException("Shelter not found: " + shelterId));
        if (!shelter.isActive()) {
            throw new IllegalStateException(
                    "Cannot hold a bed at this shelter right now — it may be temporarily closed. Try another shelter.");
        }

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
        // transitional-reentry-support task 4.4 (slice 2C): set hold-
        // attribution plaintext fields. Repository.insert encrypts before
        // SQL write (slice 2A wiring) — plaintext never touches disk.
        reservation.setHeldForClientName(heldForClientName);
        reservation.setHeldForClientDob(heldForClientDob);
        reservation.setHoldNotes(holdNotes);
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

        // v0.55 §13.A.2 — emit RESERVATION_HELD_FOR_CLIENT_RECORDED if any
        // hold-attribution PII field was populated. Fires AFTER the row
        // commits, INSIDE the @Transactional context per existing audit
        // pattern (TENANT_CONFIG_UPDATED, the rest of the suite).
        // Detail blob: reservation_id + which fields were populated. NO
        // plaintext, NO ciphertext — counts of populated fields are the
        // operational signal "did the navigator enter PII on this hold?",
        // sufficient to answer the chain-of-custody question without
        // re-creating the PII in the audit trail.
        if (heldForClientName != null || heldForClientDob != null || holdNotes != null) {
            List<String> fieldsRecorded = new ArrayList<>();
            if (heldForClientName != null) fieldsRecorded.add("clientName");
            if (heldForClientDob != null) fieldsRecorded.add("clientDob");
            if (holdNotes != null) fieldsRecorded.add("holdNotes");
            Map<String, Object> auditDetails = new HashMap<>();
            auditDetails.put("reservation_id", saved.getId().toString());
            auditDetails.put("fields_recorded", fieldsRecorded);
            auditEventPublisher.publishEvent(new AuditEventRecord(
                    userId, null,
                    AuditEventType.RESERVATION_HELD_FOR_CLIENT_RECORDED,
                    auditDetails, null));
        }

        return saved;
    }

    @Transactional
    public Reservation confirmReservation(UUID reservationId, UUID userId) {
        UUID tenantId = TenantContext.getTenantId();

        Reservation reservation = reservationRepository.findByIdAndActiveTenantId(reservationId, tenantId)
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

        Reservation reservation = reservationRepository.findByIdAndActiveTenantId(reservationId, tenantId)
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

    @TenantUnscoped("system-scheduled reservation expiry runs platform-wide; tenant context is set from the fetched row")
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

    /**
     * Service-layer entry point for the hold-attribution PII purge
     * (transitional-reentry-support task 4.6, slice 2C; v0.55 §13.A.4 +
     * §13.C.2 hardening). Delegates to
     * {@code ReservationRepository.purgeExpiredHoldAttribution(cutoff, limit)}
     * but lives on the service to satisfy the modular-monolith boundary
     * (`feedback_modular_monolith.md`): cross-module callers
     * (e.g. {@code ReferralTokenPurgeService}) MUST go through service-
     * layer interfaces, not directly into another module's repository.
     *
     * <p>v0.55 §13.C.2 — drives the LIMIT-bounded UPDATE in a loop until
     * the batch returns 0 rows for the current cutoff. Each loop iteration
     * is a separate transaction (via the underlying {@code JdbcTemplate}
     * call), so a backlog larger than a single batch produces multiple
     * short-lived locks rather than one long-held row-lock blanket.
     * Worst-case lock-hold time is bounded by the time to update
     * {@code DEFAULT_PURGE_BATCH_LIMIT} rows.
     *
     * <p>v0.55 §13.A.4 — emits exactly one
     * {@code RESERVATION_PII_PURGED} audit event per scheduled invocation,
     * regardless of {@code purgedCount} (including 0). The detail blob
     * carries {@code purgedCount} (sum across all batches) and
     * {@code batches} (loop iteration count). Auditing on every invocation
     * means an absent audit row signals job failure — exactly the forensic
     * property required for the "no later than 25 hours" claim to be
     * verifiable.
     *
     * @param cutoff resolution / expiry timestamp before which rows are eligible
     * @return total number of reservation rows whose ciphertext was nulled
     *         across all batches
     */
    public int purgeExpiredHoldAttribution(java.time.Instant cutoff) {
        // v0.55 §13.C.2 — bounded loop. The repository method runs one
        // LIMIT-bounded UPDATE per call; we loop until it returns 0,
        // accumulating purgedCount and counting batches for the audit row.
        int totalPurged = 0;
        int batches = 0;
        int batch;
        do {
            batch = reservationRepository.purgeExpiredHoldAttribution(
                    cutoff, ReservationRepository.DEFAULT_PURGE_BATCH_LIMIT);
            totalPurged += batch;
            batches++;
            // Cap defensively at 100 iterations so a misconfigured cutoff
            // (e.g. far future) cannot spin forever. 100 × 10K = 1M rows
            // is well above any realistic backlog and gives operators a
            // clear signal to investigate via the audit row's batches=100.
            if (batches >= 100) {
                log.warn("purgeExpiredHoldAttribution: hit safety cap of 100 batches "
                        + "with totalPurged={}; investigate cutoff or backlog size.",
                        totalPurged);
                break;
            }
        } while (batch > 0);

        // v0.55 §13.A.4 — single audit row per scheduled invocation. Emit
        // EVEN when totalPurged=0 so the absence of activity is auditable —
        // a scheduled job that silently stops emitting rows is the failure
        // mode the audit trail must catch.
        Map<String, Object> details = new HashMap<>();
        details.put("purgedCount", totalPurged);
        details.put("batches", batches);
        details.put("cutoff", cutoff.toString());
        auditEventPublisher.publishEvent(new AuditEventRecord(
                null,  // actor: system-driven (scheduled job)
                null,  // target: platform-scope, not a single user/entity
                AuditEventType.RESERVATION_PII_PURGED,
                details, null));

        return totalPurged;
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

    /**
     * Extended user-reservation query for the My Past Holds view (Phase 3
     * of notification-deep-linking, task 8.1). Both filters are optional —
     * {@code null} means "no filter on this dimension" — so the existing
     * HELD-only semantics of the bare {@code /api/v1/reservations} endpoint
     * can be preserved by defaulting the statuses parameter in the
     * controller.
     *
     * @param userId    the authenticated user whose reservations to list
     * @param statuses  reservation statuses to include (e.g., HELD,
     *                  CANCELLED, EXPIRED, CONFIRMED,
     *                  CANCELLED_SHELTER_DEACTIVATED); null or empty = no
     *                  status filter
     * @param sinceDays inclusive age window in days computed server-side
     *                  via {@code NOW() - make_interval(days => ?)}; null
     *                  = no date filter
     */
    @Transactional(readOnly = true)
    public List<Reservation> getReservationsForUser(
            UUID userId, String[] statuses, Integer sinceDays) {
        UUID tenantId = TenantContext.getTenantId();
        return reservationRepository.findByUserIdAndStatusesSince(
                tenantId, userId, statuses, sinceDays);
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
     * Find all HELD reservations for a shelter (all population types).
     * Used by shelter deactivation cascade (Issue #108).
     */
    @Transactional(readOnly = true)
    public List<Reservation> findHeldByShelterId(UUID shelterId) {
        return reservationRepository.findHeldByShelterId(shelterId);
    }

    /**
     * v0.55 §13.A.3 — emit a throttled {@code RESERVATION_PII_DECRYPTED_ON_READ}
     * audit event when a coordinator-facing read surfaced at least one
     * reservation row whose decrypted PII fields are non-null. The throttle
     * key is {@code userId:shelterId:epochHour}; duplicate reads inside the
     * same hour produce no additional audit rows.
     *
     * <p>NO {@code reservation_id} appears in the audit detail payload,
     * even though the read is per-reservation — adding {@code reservation_id}
     * would break the throttle. The forensic question this row answers is
     * "did this user view this shelter's hold-attribution PII in this hour?";
     * exact-reservation forensics are answered by HTTP access logs at the
     * reverse proxy.
     *
     * <p>Idempotent: callers may invoke this with an empty list or a list
     * that contains no PII-bearing rows; the method returns silently in
     * both cases.
     *
     * <p><b>Caller contract:</b> this method is the sanctioned entry point
     * for emitting {@code RESERVATION_PII_DECRYPTED_ON_READ}. Only
     * controller-layer call sites that have just performed a tenant-scoped
     * read of {@link Reservation} rows on behalf of an authenticated user
     * SHOULD invoke it. Calls with fabricated rows would generate
     * misleading audit entries; tests that need a synthetic emit SHOULD
     * use {@link AuditEventType#TEST_PROBE} instead.
     */
    public void recordPiiReadIfPresent(UUID userId, UUID shelterId, List<Reservation> rows) {
        if (userId == null || shelterId == null || rows == null || rows.isEmpty()) {
            return;
        }
        boolean anyPii = rows.stream().anyMatch(r ->
                r.getHeldForClientName() != null
                        || r.getHeldForClientDob() != null
                        || r.getHoldNotes() != null);
        if (!anyPii) return;

        long epochHour = Instant.now().getEpochSecond() / 3600L;
        String throttleKey = userId + ":" + shelterId + ":" + epochHour;
        // Caffeine ConcurrentMap-style atomic put-if-absent: returns the
        // existing value (true) if the key was already present; otherwise
        // installs ours and returns null. Only emit when null — a duplicate
        // call inside the throttle window short-circuits.
        Boolean existing = piiReadAuditThrottle.asMap().putIfAbsent(throttleKey, Boolean.TRUE);
        if (existing != null) return;

        // v0.55 §13.A.3 (warroom Round 2 MEDIUM-A1): timestamp is the
        // START of the throttle hour, not the moment of cache-miss. A
        // read at 14:59:30 sets first_seen_at to 14:00:00 (the window
        // it represents), not 14:59:30. Operators reading the audit
        // trail know "this user touched this shelter's PII at some
        // point in the 14:00 hour"; the exact moment is in the row's
        // own timestamp column.
        Instant windowStart = Instant.ofEpochSecond(epochHour * 3600L);

        Map<String, Object> details = new HashMap<>();
        details.put("shelter_id", shelterId.toString());
        details.put("throttle_key", throttleKey);
        details.put("first_seen_at", windowStart.toString());
        auditEventPublisher.publishEvent(new AuditEventRecord(
                userId, null,
                AuditEventType.RESERVATION_PII_DECRYPTED_ON_READ,
                details, null));
    }

    /**
     * Summary of a cancelled hold — boundary-safe DTO for cross-module callers.
     * Avoids exposing Reservation domain entity across module boundaries (ArchUnit).
     */
    public record CancelledHoldSummary(UUID reservationId, UUID userId) {}

    /**
     * Cancel all HELD reservations for a shelter due to deactivation (Issue #108).
     * Transitions each to CANCELLED_SHELTER_DEACTIVATED, recomputes beds_on_hold
     * per affected population type, and returns summaries for notification.
     */
    @Transactional
    public List<CancelledHoldSummary> cancelHeldForShelterDeactivation(UUID shelterId) {
        List<Reservation> held = reservationRepository.findHeldByShelterId(shelterId);
        if (held.isEmpty()) return List.of();

        List<CancelledHoldSummary> summaries = new ArrayList<>();

        // Cancel each reservation
        for (Reservation reservation : held) {
            reservationRepository.updateStatus(reservation.getId(), ReservationStatus.CANCELLED_SHELTER_DEACTIVATED);
            summaries.add(new CancelledHoldSummary(reservation.getId(), reservation.getUserId()));
        }

        // Recompute beds_on_hold per affected population type (all holds cancelled → 0)
        Set<String> affectedPopTypes = held.stream()
                .map(Reservation::getPopulationType)
                .collect(Collectors.toSet());
        for (String popType : affectedPopTypes) {
            applyRecompute(shelterId, popType, 0, "shelter-deactivated-hold-cancel", "system:shelter-deactivate");
        }

        return summaries;
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
